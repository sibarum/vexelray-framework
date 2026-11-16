package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Stalled;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Topic;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A wedged component is noticed, named, and can be acted on — with no window and no GPU.
 *
 * <p>The wedge is a subscriber that blocks until the test lets it go, which is what a component that loops or
 * deadlocks looks like from outside: a lane inside one delivery for too long. The policy is the application's, so
 * the tests hand one back the way a wiring does.
 */
final class LivenessTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);
    private static final Topic<String> WORK = Topic.edge("test.liveness.work", String.class);

    private static Shell shell() {
        return new Shell(Launch.parse(new String[0], "demo", Set.of()), DEMO);
    }

    @Test
    void aWedgedLaneIsHandedToTheApplicationsPolicyByName() throws Exception {
        Shell shell = shell();
        try {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch wedged = new CountDownLatch(1);
            AtomicReference<LivenessPolicy.Stall> seen = new AtomicReference<>();
            CountDownLatch reported = new CountDownLatch(1);
            shell.liveness(new LivenessPolicy() {
                @Override
                public Duration stallThreshold() {
                    return Duration.ofMillis(50);
                }

                @Override
                public void onStall(Stall stall, Context context) {
                    seen.set(stall);
                    reported.countDown();
                }
            });
            shell.inbox("app.Compose", "compose").subscribe(WORK, msg -> {
                wedged.countDown();
                await(release);
            }, 1);

            shell.startComponents();
            shell.bus().publish(WORK, "hangs");

            assertTrue(reported.await(5, TimeUnit.SECONDS), "a wedged lane was never reported");
            assertEquals("compose", seen.get().lane());
            assertTrue(seen.get().stalledFor().toMillis() >= 50);
            release.countDown();
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aStallIsReportedAsAMessageOnTheApplicationsBus() throws Exception {
        Shell shell = shell();
        try {
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Stalled> heard = new AtomicReference<>();
            CountDownLatch reported = new CountDownLatch(1);
            shell.liveness(new LivenessPolicy() {
                @Override
                public Duration stallThreshold() {
                    return Duration.ofMillis(50);
                }

                @Override
                public void onStall(Stall stall, Context context) {
                }
            });
            shell.bus().subscribe(Stalled.TOPIC, s -> {
                heard.set(s);
                reported.countDown();
            });
            shell.inbox("app.Compose", "compose").subscribe(WORK, msg -> await(release), 1);

            shell.startComponents();
            shell.bus().publish(WORK, "hangs");

            assertTrue(reported.await(5, TimeUnit.SECONDS), "a wedged lane was never published");
            assertEquals("compose", heard.get().lane());
            assertTrue(heard.get().stalledFor().toMillis() >= 50);
            release.countDown();
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aPolicyCanInterruptTheLaneItWasToldAbout() throws Exception {
        Shell shell = shell();
        try {
            CountDownLatch interrupted = new CountDownLatch(1);
            shell.liveness(new LivenessPolicy() {
                @Override
                public Duration stallThreshold() {
                    return Duration.ofMillis(50);
                }

                @Override
                public void onStall(Stall stall, Context context) {
                    context.interrupt(stall.lane());
                }
            });
            shell.inbox("app.Compose", "compose").subscribe(WORK, msg -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            }, 1);

            shell.startComponents();
            shell.bus().publish(WORK, "hangs");

            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the policy's interrupt never reached the lane");
        } finally {
            shell.disposer().close();
        }
    }

    /**
     * A component that declares no lane runs on the logic lane, whose stall says {@code "logic"}; its declaration says
     * {@code Component.DEFAULT_LANE}. Interrupt takes either, because the first is what the stall reports and the
     * second is the only one an application can spell from its own code.
     */
    @Test
    void theLogicLaneIsReportedAsLogicAndInterruptedByEitherName() throws Exception {
        Shell shell = shell();
        try {
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicReference<String> reportedAs = new AtomicReference<>();
            AtomicReference<Boolean> found = new AtomicReference<>();
            shell.liveness(new LivenessPolicy() {
                @Override
                public Duration stallThreshold() {
                    return Duration.ofMillis(50);
                }

                @Override
                public void onStall(Stall stall, Context context) {
                    reportedAs.set(stall.lane());
                    found.set(context.interrupt(dev.vexelray.framework.api.Component.DEFAULT_LANE)
                            && context.interrupt(LivenessPolicy.LOGIC_LANE));
                }
            });
            shell.inbox("app.Compose").subscribe(WORK, msg -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            }, 1);

            shell.startComponents();
            shell.bus().publish(WORK, "hangs");

            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "neither name reached the logic lane");
            assertEquals(LivenessPolicy.LOGIC_LANE, reportedAs.get());
            assertTrue(found.get());
        } finally {
            shell.disposer().close();
        }
    }

    // ---- restart --------------------------------------------------------------------------------------------

    /** A shell whose halts and process starts are recorded, launched with {@code args}. */
    private static Shell recording(List<String> events, CountDownLatch halted, String... args) {
        return new Shell(Launch.parse(args, "demo", Set.of()), DEMO, status -> {
            events.add("halt " + status);
            halted.countDown();
        }, how -> events.add("start " + how.command()));
    }

    /** A policy that restarts on a lane's stall, with {@code grace} for the window to close first. */
    private static LivenessPolicy restarting(Duration grace) {
        return new LivenessPolicy() {
            @Override
            public Duration stallThreshold() {
                return Duration.ofMillis(50);
            }

            @Override
            public Duration exitGrace() {
                return grace;
            }

            @Override
            public void onStall(Stall stall, Context context) {
                context.restart();
            }
        };
    }

    /** A wedged logic lane, released by {@code release}. */
    private static void wedge(Shell shell, CountDownLatch release) {
        shell.inbox("app.Compose").subscribe(WORK, msg -> await(release), 1);
        shell.startComponents();
        shell.bus().publish(WORK, "hangs");
    }

    private static void assumeRestartable(Shell shell) {
        assumeFalse(Logs.driven(shell.launch()), "this test JVM is itself an automation run");
        assumeTrue(shell.relaunch().unavailable().isEmpty(), "this JVM cannot say how it was started");
    }

    @Test
    void aRestartStartsThisProcessAgainOnceTheWindowHasClosed() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        Shell shell = recording(events, new CountDownLatch(1));
        try {
            assumeRestartable(shell);
            shell.liveness(restarting(Duration.ofHours(1)));
            wedge(shell, release);
            Thread.sleep(400);
            assertTrue(events.isEmpty(), "a restart started before the window closed: " + events);

            shell.startRestart();   // the loop has ended: the window closed through its gate
            shell.startRestart();   // and the watchdog's way out finds nothing left to start
            assertEquals(List.of("start " + shell.relaunch().command()), events,
                    "a restart is the same command, started once");
        } finally {
            release.countDown();
            shell.disposer().close();
        }
    }

    @Test
    void aRestartWhoseGraceRunsOutStartsTheNewProcessBeforeTheHalt() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch halted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Shell shell = recording(events, halted);
        try {
            assumeRestartable(shell);
            shell.liveness(restarting(Duration.ofMillis(100)));
            wedge(shell, release);
            assertTrue(halted.await(10, TimeUnit.SECONDS), "the grace ran out and nothing ended the process");
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.get(0).startsWith("start "), "a save prompt left open turned a restart into an exit");
            assertEquals("halt 1", events.get(1));
        } finally {
            release.countDown();
            shell.disposer().close();
        }
    }

    @Test
    void anAutomationRunExitsInsteadOfRestarting() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch halted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Shell shell = recording(events, halted, "--automation=0");
        try {
            shell.liveness(restarting(Duration.ofMillis(100)));
            wedge(shell, release);
            assertTrue(halted.await(10, TimeUnit.SECONDS));
            assertEquals(List.of("halt 1"), events, "a harness was left with a process it did not start");
        } finally {
            release.countDown();
            shell.disposer().close();
        }
    }

    // ---- the main thread ------------------------------------------------------------------------------------

    /** A policy with a short main-thread threshold that hands {@code act} each main-thread stall. */
    private static LivenessPolicy onMain(java.util.function.BiConsumer<LivenessPolicy.Stall,
            LivenessPolicy.MainThreadContext> act, List<String> laneStalls) {
        return new LivenessPolicy() {
            @Override
            public Duration mainThreadStallThreshold() {
                return Duration.ofMillis(50);
            }

            @Override
            public void onStall(Stall stall, Context context) {
                laneStalls.add(stall.lane());
            }

            @Override
            public void onMainThreadStall(Stall stall, MainThreadContext context) {
                act.accept(stall, context);
            }
        };
    }

    @Test
    void aStuckMainThreadGoesToItsOwnCallbackAndNeverToOnStall() throws Exception {
        List<String> laneStalls = new CopyOnWriteArrayList<>();
        AtomicReference<LivenessPolicy.Stall> seen = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        Shell shell = recording(new CopyOnWriteArrayList<>(), new CountDownLatch(1));
        try {
            shell.liveness(onMain((stall, context) -> {
                seen.set(stall);
                reported.countDown();
            }, laneStalls));
            shell.startComponents();
            shell.frame();   // a frame begins and never finishes: no park, no next frame

            assertTrue(reported.await(5, TimeUnit.SECONDS), "a stuck main thread was never reported");
            assertEquals(Stalled.MAIN, seen.get().lane());
            assertTrue(laneStalls.isEmpty(), "a policy written for lanes was handed the main thread");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aParkedMainThreadIsNeverAStall() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        Shell shell = recording(new CopyOnWriteArrayList<>(), new CountDownLatch(1));
        try {
            shell.liveness(onMain((stall, context) -> reported.countDown(), new CopyOnWriteArrayList<>()));
            shell.startComponents();
            shell.frame();
            shell.nanosUntilNextFrame();   // the loop parks: render on demand, with nothing due
            assertFalse(reported.await(400, TimeUnit.MILLISECONDS), "a quiet window was taken for a wedge");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void theDefaultForAStuckMainThreadIsAHalt() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch halted = new CountDownLatch(1);
        Shell shell = recording(events, halted);
        try {
            shell.liveness(new LivenessPolicy() {
                @Override
                public Duration mainThreadStallThreshold() {
                    return Duration.ofMillis(50);
                }
            });
            shell.startComponents();
            shell.frame();
            assertTrue(halted.await(10, TimeUnit.SECONDS), "a stuck main thread was not ended");
            assertEquals(List.of("halt 1"), events, "the default for a stuck main thread is a halt and nothing else");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aMainThreadRestartStartsTheNewProcessThenHalts() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch halted = new CountDownLatch(1);
        Shell shell = recording(events, halted);
        try {
            assumeRestartable(shell);
            shell.liveness(onMain((stall, context) -> context.restart(), new CopyOnWriteArrayList<>()));
            shell.startComponents();
            shell.frame();
            assertTrue(halted.await(10, TimeUnit.SECONDS));
            assertEquals(List.of("start " + shell.relaunch().command(), "halt 1"), events);
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void theMainThresholdFollowsTheLanesUnlessSetApart() {
        LivenessPolicy thirty = new LivenessPolicy() {
            @Override
            public Duration stallThreshold() {
                return Duration.ofSeconds(30);
            }
        };
        assertEquals(Duration.ofSeconds(30), thirty.mainThreadStallThreshold());
        assertEquals(Duration.ofSeconds(10), new LivenessPolicy() { }.mainThreadStallThreshold());
    }

    @Test
    void aSecondPolicyIsRefusedAndNullIsNoOpinion() {
        Shell shell = shell();
        try {
            shell.liveness(null);
            shell.liveness(new LivenessPolicy() { });
            assertThrows(IllegalStateException.class, () -> shell.liveness(new LivenessPolicy() { }));
        } finally {
            shell.disposer().close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

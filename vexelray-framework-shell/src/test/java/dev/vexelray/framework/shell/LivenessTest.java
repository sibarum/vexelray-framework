package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Topic;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wedged component is noticed, named, and can be acted on — with no window and no GPU.
 *
 * <p>The wedge is a subscriber that blocks until the test lets it go, which is what a component that loops or
 * deadlocks looks like from outside: a lane inside one delivery for too long. The policy is the application's, so
 * the tests hand one back the way a wiring does.
 */
final class LivenessTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);
    private static final Topic<String> WORK = Topic.of("test.liveness.work", String.class);

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
            shell.place("compose").subscribe(WORK, msg -> {
                wedged.countDown();
                await(release);
            }, 1, Backpressure.COALESCE_LATEST);

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
            shell.place("compose").subscribe(WORK, msg -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            }, 1, Backpressure.COALESCE_LATEST);

            shell.startComponents();
            shell.bus().publish(WORK, "hangs");

            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the policy's interrupt never reached the lane");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aBusyButFinishingLaneIsNotAStall() throws Exception {
        Shell shell = shell();
        try {
            AtomicInteger reports = new AtomicInteger();
            AtomicInteger delivered = new AtomicInteger();
            shell.liveness(new LivenessPolicy() {
                @Override
                public Duration stallThreshold() {
                    return Duration.ofMillis(500);
                }

                @Override
                public void onStall(Stall stall, Context context) {
                    reports.incrementAndGet();
                }
            });
            shell.place("busy").subscribe(WORK, msg -> delivered.incrementAndGet(), 64, Backpressure.BLOCK);

            shell.startComponents();
            for (int i = 0; i < 20; i++) {
                shell.bus().publish(WORK, "quick");
                Thread.sleep(20);
            }

            assertTrue(delivered.get() > 0);
            assertEquals(0, reports.get());
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void theDefaultPolicyIsTenSecondsAndAPolicyMayOverrideOnlyWhatItMeans() {
        LivenessPolicy standard = new LivenessPolicy() { };
        assertEquals(Duration.ofSeconds(10), standard.stallThreshold());
        assertEquals(Duration.ofSeconds(10), standard.closeBound());
        assertEquals(Duration.ofSeconds(30), standard.exitGrace());
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

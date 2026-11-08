package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Step;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The logic lane: components' inboxes drained by one tick, on a thread of its own, started with the components.
 *
 * <p>What a component relies on: it runs off the publisher's thread, on the same thread as every other component
 * on the logic lane and never two at once, or on its declared lane's thread in the same rounds; a ledger on its inbox
 * runs its steps there; nothing is delivered before the framework starts the lane, and what was queued is delivered
 * before it stops; a throw nothing declared ends the application; only the container makes an inbox; and where the
 * ticks run, a thread of their own or the main thread, changes nothing a component can see (T1.7).
 */
final class LogicTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    private static final Topic<String> WORK = Topic.edge("test.logic.work", String.class);
    private static final Topic<String> SHOWN = Topic.edge("test.logic.shown", String.class);

    private static Shell shell() {
        return new Shell(Launch.parse(new String[0], "demo", Set.of()), DEMO);
    }

    @Test
    void anApplicationCannotMakeAnInboxOrStartTheLaneByHand() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isPublic(
                Shell.class.getDeclaredMethod("inbox", String.class).getModifiers()), "Shell.inbox is the container's");
        assertFalse(java.lang.reflect.Modifier.isPublic(Logic.class.getDeclaredConstructors()[0].getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(Logic.class.getDeclaredMethod("start").getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(Inboxes.class.getDeclaredMethod(
                "of", Shell.class, String.class, String.class, sibarum.atchung.Topic[].class).getModifiers()),
                "the one door generated code uses");
    }

    @Test
    void componentsOnTheLogicLaneShareOneThreadThatIsNotThePublishers() throws Exception {
        Shell shell = shell();
        try {
            List<String> ranOn = new CopyOnWriteArrayList<>();
            CountDownLatch delivered = new CountDownLatch(2);
            for (String name : List.of("app.Editor", "app.Outline")) {
                shell.inbox(name).subscribe(WORK, msg -> {
                    ranOn.add(Thread.currentThread().getName());
                    delivered.countDown();
                }, 8);
            }
            assertTrue(shell.inbox("app.Editor") == shell.inbox("app.Editor"), "one inbox per name");

            shell.startComponents();
            shell.bus().publish(WORK, "an edit");

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "the logic lane never ran its tick");
            assertEquals(ranOn.get(0), ranOn.get(1), "two components on the logic lane, two threads");
            assertNotEquals(Thread.currentThread().getName(), ranOn.get(0));
            assertEquals("vexel-logic", ranOn.get(0), "T1.2: the logic lane has one name, used everywhere");
        } finally {
            shell.disposer().close();
        }
    }

    /**
     * A controller's ledger on its inbox, and a component on a lane it declared. A publish runs the step on the logic
     * lane, and what the step sends reaches the other component in the next round of the same tick, on its own
     * lane's thread.
     */
    @Test
    void aLedgersStepRunsOnTheLaneAndWhatItSendsReachesADeclaredLane() throws Exception {
        Shell shell = shell();
        try {
            Ledger.Builder<Integer> b = Ledger.of(0);
            Step<Integer, String> count = b.step("count", (n, msg, out) -> {
                out.send(SHOWN, msg + " " + (n + 1));
                return n + 1;
            });
            Ledger<Integer> counter = b.on(WORK, count).build(shell.inbox("app.Counter"), "app.counter", 8);
            AtomicReference<String> shown = new AtomicReference<>();
            AtomicReference<String> shownOn = new AtomicReference<>();
            CountDownLatch heard = new CountDownLatch(1);
            shell.inbox("app.Shower", "shower").subscribe(SHOWN, s -> {
                shown.set(s);
                shownOn.set(Thread.currentThread().getName());
                heard.countDown();
            }, 8);

            shell.startComponents();
            shell.bus().publish(WORK, "clicked");

            assertTrue(heard.await(5, TimeUnit.SECONDS), "the view never heard what the step sent");
            assertEquals("clicked 1", shown.get());
            assertEquals("vexel-component-shower", shownOn.get(), "T1.2: a declared lane's thread is named for it");
            assertEquals(1, counter.state().value());
        } finally {
            shell.disposer().close();
        }
    }

    /**
     * T5.3: nothing has to remember to wake the loop. A tick that delivered anything wakes it, because a component that
     * ran has produced work the loop could not predict; a lane with nothing arriving runs no tick and wakes nothing.
     */
    @Test
    void aTickThatDeliveredWakesTheLoopAndAnIdleLaneDoesNot() throws Exception {
        Shell shell = shell();
        Logic logic = new Logic(shell.bus(), shell.lanes());
        try {
            logic.inbox("app.Editor").subscribe(WORK, msg -> { }, 8);
            CountDownLatch woken = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger wakes = new java.util.concurrent.atomic.AtomicInteger();
            logic.onWake(() -> {
                wakes.incrementAndGet();
                woken.countDown();
            });
            logic.start();
            Thread.sleep(100);
            assertEquals(0, wakes.get(), "an idle lane woke the loop");

            shell.bus().publish(WORK, "an edit");
            assertTrue(woken.await(5, TimeUnit.SECONDS), "a tick that delivered never woke the loop");
        } finally {
            logic.close();
            shell.disposer().close();
        }
    }

    /** {@code @Hears} and the inbox's subscriptions agree, ledgers' own topics aside, or the lane does not start. */
    @Test
    void anInboxThatHearsOtherThanItDeclaredDoesNotStart() {
        Shell shell = shell();
        try {
            Ledger.Builder<Integer> b = Ledger.of(0);
            Step<Integer, String> count = b.step("count", (n, msg, out) -> n + 1);
            b.on(WORK, count).build(shell.inbox("app.Counter", List.of(SHOWN.name())), "app.counter", 8);

            IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    shell::startComponents);
            assertEquals("app.Counter's @Hears does not match what its inbox subscribed to. Heard and not declared: ["
                    + WORK.name() + "]. Declared and not heard: [" + SHOWN.name() + "]. @Hears is how the processor"
                    + " checks the channels, so list exactly what the component hears", e.getMessage());
        } finally {
            shell.disposer().close();
        }
    }

    /** A throw nothing declared goes to the fault policy, which halts in an application. */
    @Test
    void aStepThatThrowsGoesToTheFaultPolicy() throws Exception {
        AtomicReference<Throwable> fault = new AtomicReference<>();
        CountDownLatch faulted = new CountDownLatch(1);
        sibarum.atchung.Atchung.onFatal(cause -> {
            fault.set(cause);
            faulted.countDown();
        });
        Shell shell = shell();
        try {
            shell.inbox("app.Fragile").subscribe(WORK, msg -> {
                throw new IllegalArgumentException("unexpected " + msg);
            }, 8);
            shell.startComponents();
            shell.bus().publish(WORK, "first");

            assertTrue(faulted.await(5, TimeUnit.SECONDS), "a throwing step never reached the fault policy");
            assertTrue(fault.get().getMessage().contains("logic lane"), fault.get().getMessage());
        } finally {
            sibarum.atchung.Atchung.onFatal(null);
            shell.disposer().close();
        }
    }
    /**
     * A mailbox must not be delivered to before its publishers exist, and here the publisher is a wiring that has not
     * finished running: a message published before the start waits, and the start delivers it rather than dropping it.
     */
    @Test
    void nothingRunsUntilTheFrameworkStartsItAndWhatWaitedIsDelivered() throws Exception {
        Shell shell = shell();
        try {
            AtomicInteger delivered = new AtomicInteger();
            CountDownLatch once = new CountDownLatch(1);
            shell.inbox("app.Early").subscribe(WORK, msg -> {
                delivered.incrementAndGet();
                once.countDown();
            }, 8);
            shell.bus().publish(WORK, "too early");
            Thread.sleep(100);
            assertEquals(0, delivered.get(), "a component was delivered to before the framework started the lane");

            shell.startComponents();
            assertTrue(once.await(5, TimeUnit.SECONDS), "the message queued before the start was never delivered");
        } finally {
            shell.disposer().close();
        }
    }

    /** T1.3: every inbox is made by the wiring, before the first tick fixes their order; none is added later. */
    @Test
    void anInboxCannotBeAddedOnceTheLaneIsRunning() {
        Shell shell = shell();
        try {
            shell.inbox("app.First");
            shell.startComponents();
            assertThrows(IllegalStateException.class, () -> shell.inbox("app.Late"));
            assertTrue(shell.inbox("app.First") != null, "asking again for an inbox that exists is not adding one");
        } finally {
            shell.disposer().close();
        }
    }

    /** A component is on one lane: its inbox asked for on another is the wiring contradicting itself. */
    @Test
    void aComponentIsOnOneLane() {
        Shell shell = shell();
        try {
            shell.inbox("app.Physics", "physics");
            assertThrows(IllegalStateException.class, () -> shell.inbox("app.Physics"));
        } finally {
            shell.disposer().close();
        }
    }

    /**
     * Stop, then drain, never the other way: an edge queued when the application closes is delivered by the last tick,
     * on every lane, and the declared lanes' threads are gone once the lanes are closed.
     */
    @Test
    void stoppingDeliversWhatWasQueuedAndEndsTheDeclaredLanes() throws Exception {
        Shell shell = shell();
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> lane = new AtomicReference<>();
        shell.inbox("app.Drainer", "drainer").subscribe(WORK, msg -> {
            lane.set(Thread.currentThread());
            if (msg.equals("first")) {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            delivered.incrementAndGet();
        }, 8);
        shell.startComponents();
        shell.bus().publish(WORK, "first");
        assertTrue(inside.await(5, TimeUnit.SECONDS), "the declared lane never started its delivery");
        shell.bus().publish(WORK, "second");
        shell.bus().publish(WORK, "third");
        release.countDown();
        shell.disposer().close();

        assertEquals(3, delivered.get(), "a message queued at shutdown was dropped");
        assertFalse(lane.get().isAlive(), "a declared lane's thread outlived the application");
    }

    /** {@code --logic} is a launch the application honours or refuses before anything is built. */
    @Test
    void anUnknownPlaceForTheLogicIsRefusedAtLaunch() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Shell(Launch.parse(new String[]{"--logic=gpu"}, "demo", Set.of()), DEMO));
        assertTrue(e.getMessage().contains("--logic=gpu"), e.getMessage());
    }

    /**
     * T1.7, the witness: the same components, the same inputs in the same order, run with the ticks on a thread of
     * their own and with the ticks on the calling thread (single-thread mode), give the same deliveries in the same
     * order. A controller on the logic lane answers each input, a component on a declared lane hears the answer and
     * reports it, and the report comes back to the controller: three hops, two lanes, every round.
     */
    @Test
    void theSameInputsGiveTheSameDeliveriesWithTheTicksOnTheirThreadOrOnTheMainThread() throws Exception {
        List<String> threaded = witness(false);
        List<String> onMain = witness(true);
        assertEquals(9, threaded.size(), String.valueOf(threaded));
        assertEquals(threaded, onMain);
    }

    private static final Topic<String> ASKED = Topic.edge("test.logic.asked", String.class);
    private static final Topic<String> ANSWERED = Topic.edge("test.logic.answered", String.class);
    private static final Topic<String> REPORTED = Topic.edge("test.logic.reported", String.class);

    private static List<String> witness(boolean onMain) throws Exception {
        Shell shell = new Shell(Launch.parse(onMain ? new String[]{"--logic=main"} : new String[0], "demo",
                Set.of()), DEMO);
        List<String> log = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        try {
            Inbox controller = shell.inbox("app.Controller");
            sibarum.atchung.Sender<String> answer = controller.sender(ANSWERED);
            controller.subscribe(ASKED, q -> {
                log.add("controller heard " + q);
                answer.send(q + "!");
            }, 8);
            controller.subscribe(REPORTED, r -> log.add("controller heard report " + r), 8);
            Inbox reporter = shell.inbox("app.Reporter", "reporter");
            sibarum.atchung.Sender<String> report = reporter.sender(REPORTED);
            reporter.subscribe(ANSWERED, a -> {
                log.add("reporter heard " + a);
                threads.add(Thread.currentThread().getName());
                report.send(String.valueOf(a.length()));
            }, 8);
            shell.startComponents();
            for (String q : List.of("a", "bb", "ccc")) {
                shell.bus().publish(ASKED, q);
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (log.size() < 9 && System.nanoTime() < until) {
                if (onMain) {
                    shell.runLogicOnMain();
                } else {
                    Thread.sleep(5);
                }
            }
        } finally {
            shell.disposer().close();
        }
        String expected = onMain ? Thread.currentThread().getName() : "vexel-component-reporter";
        for (String t : threads) {
            assertEquals(expected, t, "the declared lane ran somewhere its mode does not put it");
        }
        return new ArrayList<>(log);
    }
}

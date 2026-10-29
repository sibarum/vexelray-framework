package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Step;
import sibarum.atchung.Topic;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The logic lane: components' inboxes drained by one tick, on one thread of their own, started with the placements.
 *
 * <p>What a component that takes its {@link Inbox} relies on: it runs off the publisher's thread, on the same thread
 * as every other such component and never two at once; a ledger on its inbox runs its steps there, and what a step
 * sends reaches a component on a lane of its own (the view) when the tick ends; a throw nothing declared ends the
 * application; and only the container makes an inbox.
 */
final class LogicTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    private static final Topic<String> WORK = Topic.of("test.logic.work", String.class);
    private static final Topic<String> SHOWN = Topic.of("test.logic.shown", String.class);

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
                "of", Shell.class, String.class).getModifiers()), "the one door generated code uses");
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
     * The template's shape: a controller's ledger on its inbox, a view on a lane of its own. The view publishes, the
     * step runs on the logic lane, and what it sends reaches the view once the tick ends.
     */
    @Test
    void aLedgersStepRunsOnTheLaneAndWhatItSendsReachesAPlacedComponent() throws Exception {
        Shell shell = shell();
        try {
            Ledger.Builder<Integer> b = Ledger.of(0);
            Step<Integer, String> count = b.step("count", (n, msg, out) -> {
                out.send(SHOWN, msg + " " + (n + 1));
                return n + 1;
            });
            Ledger<Integer> counter = b.on(WORK, count).build(shell.inbox("app.Counter"), "app.counter", 8);
            AtomicReference<String> shown = new AtomicReference<>();
            CountDownLatch heard = new CountDownLatch(1);
            shell.place("view").subscribe(SHOWN, s -> {
                shown.set(s);
                heard.countDown();
            }, 8, Backpressure.FAIL);

            shell.startComponents();
            shell.bus().publish(WORK, "clicked");

            assertTrue(heard.await(5, TimeUnit.SECONDS), "the view never heard what the step sent");
            assertEquals("clicked 1", shown.get());
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

    /** As a placement's: a throw nothing declared goes to the fault policy, which halts in an application. */
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
}

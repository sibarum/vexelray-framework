package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Topic;

import java.util.Set;
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
 * A component placed on a thread of its own — the seam the concurrency model is for, asserted without a GPU.
 *
 * <p>What the container owns here is <b>the thread</b>, and these hold the four things that are true because
 * it does. The component runs <b>off the calling thread</b>, and on one thread rather than a pool, which is
 * what makes two deliveries at once impossible rather than unlikely. <b>Construction and start are separate</b>,
 * because a mailbox must not pump before its publishers exist — the designer's hand-written component enforced
 * that by where one line sat in a constructor. <b>A stop drains first</b>, because an edge nothing downstream
 * can reconstruct must not be dropped on the floor at shutdown. And <b>the wake comes with the placement</b>,
 * because a component that publishes a result and does not nudge the loop is a window that is responsive
 * except for the interactions that happened to arrive that way — a bug this stack has already paid for twice,
 * the second time by meeting the obligation accidentally through an unrelated line of reporting.
 */
final class PlacementTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    private static final Topic<String> WORK = Topic.of("test.component.work", String.class);

    private static Shell shell() {
        return new Shell(Launch.parse(new String[0], "demo", Set.of()), DEMO);
    }

    @Test
    void aComponentDrainsOnItsOwnThreadRatherThanThePublishersOrAPools() throws Exception {
        Shell shell = shell();
        try {
            AtomicReference<String> ranOn = new AtomicReference<>();
            CountDownLatch delivered = new CountDownLatch(1);
            Placement component = shell.place("compose")
                    .subscribe(WORK, msg -> {
                        ranOn.set(Thread.currentThread().getName());
                        delivered.countDown();
                    }, 1, Backpressure.COALESCE_LATEST);

            shell.startComponents();
            shell.bus().publish(WORK, "an edit");

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "the component never drained its mailbox");
            assertNotEquals(Thread.currentThread().getName(), ranOn.get(),
                    "the component ran on the publisher's thread, which is the confinement the model needs");
            assertTrue(ranOn.get().contains("compose"),
                    "a component's thread is named for the component; this was " + ranOn.get());
            assertEquals("compose", component.name());
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void nothingRunsUntilTheFrameworkStartsIt() throws Exception {
        Shell shell = shell();
        try {
            AtomicInteger delivered = new AtomicInteger();
            shell.place("early").subscribe(WORK, msg -> delivered.incrementAndGet(), 8, Backpressure.BLOCK);

            // Published before start. It queues — the subscription exists — but nothing drains it, which is
            // exactly the state the ordering rule protects: a mailbox must not pump before its publishers
            // exist, and here the publisher is a wiring that has not finished running.
            shell.bus().publish(WORK, "too early");
            Thread.sleep(100);
            assertEquals(0, delivered.get(), "a component was pumping before the framework started it");

            shell.startComponents();
            // Starting is what drains what was waiting, rather than discarding it.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (delivered.get() == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(1, delivered.get(), "the message queued before start was never delivered");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aMailboxCannotBeAddedOnceTheComponentIsRunning() {
        Shell shell = shell();
        try {
            Placement component = shell.place("late");
            shell.startComponents();
            // Not a race to be won: a mailbox added to a running component begins delivering on a thread
            // already inside a drain, and the whole point of the two-phase start is that this is structural.
            assertThrows(IllegalStateException.class,
                    () -> component.subscribe(WORK, msg -> { }, 1, Backpressure.FAIL));
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void stoppingDrainsWhatWasQueuedBeforeItCloses() throws Exception {
        Shell shell = shell();
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch inHandler = new CountDownLatch(1);
        try {
            shell.place("drainer").subscribe(WORK, msg -> {
                delivered.incrementAndGet();
                if ("hold".equals(msg)) {
                    inHandler.countDown();
                    try {
                        blocking.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, 8, Backpressure.BLOCK);
            shell.startComponents();

            // Hold the component inside a delivery, queue two more behind it, then stop. Without the last
            // drain those two are an edge lost at shutdown — and an edge is what nothing downstream can
            // reconstruct, which is why this one is not allowed to coalesce its way out of the problem.
            shell.bus().publish(WORK, "hold");
            assertTrue(inHandler.await(5, TimeUnit.SECONDS), "the component never entered its handler");
            shell.bus().publish(WORK, "second");
            shell.bus().publish(WORK, "third");
            blocking.countDown();
        } finally {
            shell.disposer().close();
        }
        assertEquals(3, delivered.get(), "a queued message was dropped by the stop rather than drained first");
    }

    @Test
    void aPlacementIsTheWakeSourceItOwes() throws Exception {
        Shell shell = shell();
        try {
            Placement component = shell.place("publisher");
            AtomicInteger wakes = new AtomicInteger();

            // Before anything connects one, published() is a no-op rather than a null — a headless tree run
            // has no loop to nudge, and a component should not have to know which kind of run it is in.
            component.published();

            component.onWake(wakes::incrementAndGet);
            component.published();
            component.published();
            assertEquals(2, wakes.get(), "a component's publish did not reach the wake it was given");

            // The shape is WakeSource's, and that is the point rather than a coincidence: it is the seam the
            // loop already takes, so the framework registers a placement without a second mechanism.
            assertTrue(component instanceof dev.vexelray.framework.core.WakeSource);
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void shutdownStopsTheComponentThread() throws Exception {
        Shell shell = shell();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Thread> componentThread = new AtomicReference<>();
        shell.place("stoppable").subscribe(WORK, msg -> {
            componentThread.set(Thread.currentThread());
            delivered.countDown();
        }, 1, Backpressure.COALESCE_LATEST);
        shell.startComponents();
        shell.bus().publish(WORK, "hello");
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "the component never ran");

        shell.disposer().close();

        // Joined rather than left behind. A daemon thread would not hold the process open, but a component
        // still draining while the tree it publishes into is being closed is how a shutdown finds a
        // half-disposed application.
        assertFalse(componentThread.get().isAlive(),
                "the component's thread outlived the shutdown that was supposed to stop it");
    }
}

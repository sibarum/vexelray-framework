package dev.vexelray.framework.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the application's threads come from, and what is true of every one of them.
 *
 * <p>Three claims, each of which the model above this depends on rather than merely prefers. <b>The lanes are
 * named apart</b>, because one name for two lanes with different rules is a thread dump that misleads whoever
 * reaches for it when one of them is the problem. <b>The job lane is bounded</b>, because a pool that answers a full queue by growing has
 * chosen the one policy the rest of the model refuses. <b>Every thread is a platform thread</b>, because an
 * application that takes Kronometer's single-carrier flag has no second carrier to give a virtual one, which
 * makes it the documented deadlock rather than merely the slower choice.
 *
 * <p>No GPU and no window: this is the container's own substrate, which is the whole reason {@code -core} is
 * JDK-only.
 */
final class LanesTest {

    /** T1.2: each lane has one name, used everywhere. The logic lane's and a declared lane's are minted here too. */
    @Test
    void theLanesAreNamedApart() throws Exception {
        AtomicReference<String> job = new AtomicReference<>();
        AtomicReference<String> logic = new AtomicReference<>();
        AtomicReference<String> declared = new AtomicReference<>();
        CountDownLatch all = new CountDownLatch(3);
        try (Lanes lanes = new Lanes()) {
            lanes.jobs().execute(() -> {
                job.set(Thread.currentThread().getName());
                all.countDown();
            });
            lanes.logic(() -> {
                logic.set(Thread.currentThread().getName());
                all.countDown();
            });
            lanes.thread("physics", () -> {
                declared.set(Thread.currentThread().getName());
                all.countDown();
            });
            assertTrue(all.await(5, TimeUnit.SECONDS), "a lane never ran its work");
        }
        assertTrue(job.get().startsWith("vexel-job-"), job.get());
        assertEquals("vexel-logic", logic.get());
        assertEquals("vexel-component-physics", declared.get());
    }

    @Test
    void theJobLaneIsBoundedInThreadsRatherThanGrowing() {
        try (Lanes lanes = new Lanes()) {
            // Bounded in threads, not in queue: work waits behind the work already on the lane, which is
            // backpressure. A bounded queue would make it fail instead, and a job's sender has nowhere to
            // put a rejection — choosing loss is a decision taken per channel, by whoever knows what that
            // channel carries.
            ThreadPoolExecutor pool = (ThreadPoolExecutor) lanes.jobs();
            assertTrue(pool.getMaximumPoolSize() < Integer.MAX_VALUE,
                    "the job lane grows without bound, so a wedged mount answers backpressure by spawning");
            assertTrue(pool.getQueue().remainingCapacity() > 1_000,
                    "the job lane's queue is bounded, which turns a slow consumer into a rejection the "
                            + "caller cannot answer");
        }
    }

    @Test
    void everyThreadIsAPlatformDaemon() throws Exception {
        AtomicReference<Thread> onLane = new AtomicReference<>();
        AtomicReference<Thread> onComponent = new AtomicReference<>();
        CountDownLatch both = new CountDownLatch(2);
        try (Lanes lanes = new Lanes()) {
            lanes.jobs().execute(() -> {
                onLane.set(Thread.currentThread());
                both.countDown();
            });
            lanes.thread("probe", () -> {
                onComponent.set(Thread.currentThread());
                both.countDown();
                // Held open so close() has something real to stop.
                while (!lanes.closed()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            assertTrue(both.await(5, TimeUnit.SECONDS), "a thread never ran");
        }
        // isVirtual() rather than a property read: the flag that would make this wrong is the application's to
        // set, and correctness here may not depend on what it happens to be.
        assertFalse(onLane.get().isVirtual(), "the job lane is virtual");
        assertFalse(onComponent.get().isVirtual(), "a component thread is virtual");
        assertTrue(onLane.get().isDaemon(), "a lane thread can hold the process open past the loop ending");
        assertTrue(onComponent.get().isDaemon(), "a component thread can hold the process open");
    }

    @Test
    void closeStopsComponentThreadsAndRefusesToPlaceMore() throws Exception {
        Lanes lanes = new Lanes();
        CountDownLatch started = new CountDownLatch(1);
        Thread placed = lanes.thread("worker", () -> {
            started.countDown();
            while (!lanes.closed()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "the component thread never started");

        lanes.close();

        assertTrue(lanes.closed());
        assertFalse(placed.isAlive(), "close returned with a component thread still running");
        assertTrue(lanes.jobs().isShutdown(), "the job lane outlived close");

        // Placing after shutdown is a mistake with a name rather than a thread that starts and is never
        // stopped — which is what it used to be, since nothing owned the threads to know.
        assertThrows(IllegalStateException.class, () -> lanes.thread("late", () -> { }));
    }

    @Test
    void interruptingALaneReachesThreadsItsCodeCreatedAndNoOthers() throws Exception {
        CountDownLatch parked = new CountDownLatch(3);
        CountDownLatch swept = new CountDownLatch(2);
        CountDownLatch bystanderInterrupted = new CountDownLatch(1);
        try (Lanes lanes = new Lanes()) {
            lanes.thread("wedged", () -> {
                Thread child = new Thread(() -> parkUntilInterrupted(parked, swept));
                child.setDaemon(true);
                child.start();
                parkUntilInterrupted(parked, swept);
            });
            lanes.thread("bystander", () -> parkUntilInterrupted(parked, bystanderInterrupted));
            assertTrue(parked.await(5, TimeUnit.SECONDS), "a thread never parked");

            assertTrue(lanes.interruptLane("wedged"));
            assertTrue(swept.await(5, TimeUnit.SECONDS),
                    "the sweep missed the lane's thread or the thread its code created");
            assertFalse(bystanderInterrupted.await(200, TimeUnit.MILLISECONDS),
                    "the sweep reached a lane it was not asked about");
            assertFalse(lanes.interruptLane("nothing-placed-here"));
        }
    }

    private static void parkUntilInterrupted(CountDownLatch parked, CountDownLatch interrupted) {
        parked.countDown();
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            interrupted.countDown();
        }
    }

    @Test
    void closeIsIdempotent() {
        Lanes lanes = new Lanes();
        lanes.close();
        // The Disposer closes in reverse order and an application may close the shell twice on an error path;
        // a second close that threw would turn a failed startup into a failed shutdown on top of it.
        lanes.close();
        assertTrue(lanes.closed());
    }
}

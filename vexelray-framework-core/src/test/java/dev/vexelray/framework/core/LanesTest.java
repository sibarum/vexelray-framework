package dev.vexelray.framework.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the application's threads come from, and what is true of every one of them.
 *
 * <p>Three claims, each of which the model above this depends on rather than merely prefers. <b>The lanes are
 * distinct and named apart</b>, because "worker thread" meaning both is one name for two lanes with different
 * rules in the documentation applications read — and in the thread dump somebody reaches for when one of them
 * is the problem. <b>The offload lane is bounded</b>, because a pool that answers a full queue by growing has
 * chosen the one policy the rest of the model refuses. <b>Every thread is a platform thread</b>, because an
 * application that takes Kronometer's single-carrier flag has no second carrier to give a virtual one, which
 * makes it the documented deadlock rather than merely the slower choice.
 *
 * <p>No GPU and no window: this is the container's own substrate, which is the whole reason {@code -core} is
 * JDK-only.
 */
final class LanesTest {

    @Test
    void theTwoLanesAreDistinctAndNamedApart() throws Exception {
        AtomicReference<String> handler = new AtomicReference<>();
        AtomicReference<String> offloaded = new AtomicReference<>();
        CountDownLatch both = new CountDownLatch(2);
        try (Lanes lanes = new Lanes()) {
            lanes.handlers().execute(() -> {
                handler.set(Thread.currentThread().getName());
                both.countDown();
            });
            lanes.offload().execute(() -> {
                offloaded.set(Thread.currentThread().getName());
                both.countDown();
            });
            assertTrue(both.await(5, TimeUnit.SECONDS), "a lane never ran its work");
        }
        assertNotEquals(handler.get(), offloaded.get(), "both lanes are the same pool");
        assertTrue(handler.get().startsWith("vexel-handler"), handler.get());
        assertTrue(offloaded.get().startsWith("vexel-offload"), offloaded.get());
    }

    @Test
    void theOffloadLaneIsBoundedInThreadsRatherThanGrowing() {
        try (Lanes lanes = new Lanes()) {
            // Bounded in threads, not in queue: work waits behind the work already on the lane, which is
            // backpressure. A bounded queue would make it fail instead, and a caller of offload has nowhere to
            // put a rejection — choosing loss is a decision taken per channel, by whoever knows what that
            // channel carries.
            ThreadPoolExecutor pool = (ThreadPoolExecutor) lanes.offload();
            assertTrue(pool.getMaximumPoolSize() < Integer.MAX_VALUE,
                    "the offload lane grows without bound, so a wedged mount answers backpressure by spawning");
            assertTrue(pool.getQueue().remainingCapacity() > 1_000,
                    "the offload lane's queue is bounded, which turns a slow consumer into a rejection the "
                            + "caller cannot answer");
        }
    }

    @Test
    void everyThreadIsAPlatformDaemon() throws Exception {
        AtomicReference<Thread> onLane = new AtomicReference<>();
        AtomicReference<Thread> onComponent = new AtomicReference<>();
        CountDownLatch both = new CountDownLatch(2);
        try (Lanes lanes = new Lanes()) {
            lanes.offload().execute(() -> {
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
        assertFalse(onLane.get().isVirtual(), "the offload lane is virtual");
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
        assertTrue(lanes.handlers().isShutdown(), "the handler lane outlived close");
        assertTrue(lanes.offload().isShutdown(), "the offload lane outlived close");

        // Placing after shutdown is a mistake with a name rather than a thread that starts and is never
        // stopped — which is what it used to be, since nothing owned the threads to know.
        assertThrows(IllegalStateException.class, () -> lanes.thread("late", () -> { }));
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

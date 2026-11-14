package dev.vexelray.framework.core;

import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Subscriber;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watchdog, with no GPU: it names a lane that stopped draining, says so once per stall, and ends the process
 * when an orderly stop overruns — the last of which is asserted against a halt that is not allowed to happen.
 */
final class WatchdogTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    /** A watchdog on {@code lanes}, started, whose reports {@code heard} hears directly on the bus, as the policy does. */
    private static Watchdog watching(Lanes lanes, long thresholdNanos, Subscriber<Stalled> heard) {
        Atchung bus = Atchung.create();
        bus.subscribe(Stalled.TOPIC, heard);
        return new Watchdog(lanes, bus, status -> { }).start(thresholdNanos);
    }

    @Test
    void aLaneInsideOneDeliveryTooLongIsReportedOnceByName() throws Exception {
        CopyOnWriteArrayList<Stalled> seen = new CopyOnWriteArrayList<>();
        CountDownLatch reported = new CountDownLatch(1);
        try (Lanes lanes = new Lanes(); Watchdog dog = watching(lanes, 50 * MS, stall -> {
            seen.add(stall);
            reported.countDown();
        })) {
            Lanes.Busy busy = lanes.busy("compose");
            busy.enter();
            assertTrue(reported.await(5, TimeUnit.SECONDS), "a wedged delivery was never reported");
            Thread.sleep(300);
            busy.exit();

            assertEquals(1, seen.size(), "one stalled delivery is one report, not one per check");
            assertEquals("compose", seen.get(0).lane());
            assertTrue(seen.get(0).stalledFor().toNanos() >= 50 * MS);
        }
    }

    @Test
    void aPolicyThatThrowsDoesNotStopTheWatching() throws Exception {
        CountDownLatch other = new CountDownLatch(1);
        try (Lanes lanes = new Lanes(); Watchdog dog = watching(lanes, 30 * MS, stall -> {
            if (stall.lane().equals("first")) {
                throw new IllegalStateException("policy bug");
            }
            other.countDown();
        })) {
            lanes.busy("first").enter();
            Thread.sleep(100);
            lanes.busy("second").enter();
            assertTrue(other.await(5, TimeUnit.SECONDS), "the watchdog died with the policy");
        }
    }

    @Test
    void theProcessIsHaltedWhenAnOrderlyStopOverruns() throws Exception {
        CountDownLatch halted = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger(-1);
        try (Watchdog dog = new Watchdog(new Lanes(), Atchung.create(), s -> {
            status.set(s);
            halted.countDown();
        }, text -> { })) {
            dog.start(TimeUnit.SECONDS.toNanos(10));
            dog.haltAfter(50 * MS);
            assertTrue(halted.await(5, TimeUnit.SECONDS), "the backstop never fired");
            assertEquals(Watchdog.HALT_STATUS, status.get());
        }
    }

    @Test
    void closingBeforeTheDeadlineDisarmsTheHalt() throws Exception {
        AtomicInteger halts = new AtomicInteger();
        Watchdog dog = new Watchdog(new Lanes(), Atchung.create(), s -> halts.incrementAndGet());
        dog.start(TimeUnit.SECONDS.toNanos(10));
        dog.haltAfter(100 * MS);
        dog.close();
        Thread.sleep(300);
        assertEquals(0, halts.get(), "a process that ended on its own was halted anyway");
    }

    @Test
    void aLaterRequestCannotRelaxTheDeadline() throws Exception {
        CountDownLatch halted = new CountDownLatch(1);
        try (Watchdog dog = new Watchdog(new Lanes(), Atchung.create(), s -> halted.countDown(), text -> { })) {
            dog.start(TimeUnit.SECONDS.toNanos(10));
            dog.haltAfter(50 * MS);
            dog.haltAfter(TimeUnit.HOURS.toNanos(1));
            assertTrue(halted.await(5, TimeUnit.SECONDS), "a patient second caller postponed the backstop");
        }
    }
}

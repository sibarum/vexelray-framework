package dev.vexelray.framework.core;

import org.junit.jupiter.api.Test;

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

    @Test
    void aLaneInsideOneDeliveryTooLongIsReportedOnceByName() throws Exception {
        try (Lanes lanes = new Lanes(); Watchdog dog = new Watchdog(lanes, status -> { })) {
            CopyOnWriteArrayList<Lanes.Stall> seen = new CopyOnWriteArrayList<>();
            CountDownLatch reported = new CountDownLatch(1);
            dog.start(50 * MS, stall -> {
                seen.add(stall);
                reported.countDown();
            });

            Lanes.Busy busy = lanes.busy("compose");
            busy.enter();
            assertTrue(reported.await(5, TimeUnit.SECONDS), "a wedged delivery was never reported");
            Thread.sleep(300);
            busy.exit();

            assertEquals(1, seen.size(), "one stalled delivery is one report, not one per check");
            assertEquals("compose", seen.get(0).lane());
            assertTrue(seen.get(0).nanos() >= 50 * MS);
        }
    }

    @Test
    void aLaneThatIsParkedOrQuickIsNeverReported() throws Exception {
        try (Lanes lanes = new Lanes(); Watchdog dog = new Watchdog(lanes, status -> { })) {
            AtomicInteger reports = new AtomicInteger();
            dog.start(200 * MS, stall -> reports.incrementAndGet());

            lanes.busy("parked");
            Lanes.Busy quick = lanes.busy("quick");
            for (int i = 0; i < 20; i++) {
                quick.enter();
                quick.exit();
                Thread.sleep(20);
            }
            assertEquals(0, reports.get());
        }
    }

    @Test
    void aSecondStallOnTheSameLaneIsAnotherReport() throws Exception {
        try (Lanes lanes = new Lanes(); Watchdog dog = new Watchdog(lanes, status -> { })) {
            CountDownLatch two = new CountDownLatch(2);
            dog.start(30 * MS, stall -> two.countDown());

            Lanes.Busy busy = lanes.busy("compose");
            for (int i = 0; i < 2; i++) {
                busy.enter();
                Thread.sleep(150);
                busy.exit();
            }
            assertTrue(two.await(5, TimeUnit.SECONDS), "the second stall on a recovered lane was not reported");
        }
    }

    @Test
    void aPolicyThatThrowsDoesNotStopTheWatching() throws Exception {
        try (Lanes lanes = new Lanes(); Watchdog dog = new Watchdog(lanes, status -> { })) {
            CountDownLatch other = new CountDownLatch(1);
            dog.start(30 * MS, stall -> {
                if (stall.lane().equals("first")) {
                    throw new IllegalStateException("policy bug");
                }
                other.countDown();
            });
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
        try (Watchdog dog = new Watchdog(new Lanes(), s -> {
            status.set(s);
            halted.countDown();
        })) {
            dog.start(TimeUnit.SECONDS.toNanos(10), stall -> { });
            dog.haltAfter(50 * MS);
            assertTrue(halted.await(5, TimeUnit.SECONDS), "the backstop never fired");
            assertEquals(Watchdog.HALT_STATUS, status.get());
        }
    }

    @Test
    void closingBeforeTheDeadlineDisarmsTheHalt() throws Exception {
        AtomicInteger halts = new AtomicInteger();
        Watchdog dog = new Watchdog(new Lanes(), s -> halts.incrementAndGet());
        dog.start(TimeUnit.SECONDS.toNanos(10), stall -> { });
        dog.haltAfter(100 * MS);
        dog.close();
        Thread.sleep(300);
        assertEquals(0, halts.get(), "a process that ended on its own was halted anyway");
    }

    @Test
    void aLaterRequestCannotRelaxTheDeadline() throws Exception {
        CountDownLatch halted = new CountDownLatch(1);
        try (Watchdog dog = new Watchdog(new Lanes(), s -> halted.countDown())) {
            dog.start(TimeUnit.SECONDS.toNanos(10), stall -> { });
            dog.haltAfter(50 * MS);
            dog.haltAfter(TimeUnit.HOURS.toNanos(1));
            assertTrue(halted.await(5, TimeUnit.SECONDS), "a patient second caller postponed the backstop");
        }
    }
}

package dev.vexelray.framework.core;

import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A timer publishes its message on its topic, once or every period, and nothing after it is cancelled or closed. */
class TimerTest {

    record Beat(String what) {
    }

    private static final Topic<Beat> BEATS = Topic.edge("test.timer.beat", Beat.class);

    @Test
    void afterPublishesOnce() throws Exception {
        Atchung bus = Atchung.create();
        CountDownLatch heard = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        bus.subscribe(BEATS, b -> {
            assertEquals(new Beat("once"), b);
            count.incrementAndGet();
            heard.countDown();
        });
        try (Timer timer = new Timer(bus)) {
            timer.after(Duration.ofMillis(5), BEATS, new Beat("once"));
            assertTrue(heard.await(5, TimeUnit.SECONDS), "published");
            Thread.sleep(50);
            assertEquals(1, count.get(), "once, not again");
        }
    }

    @Test
    void closedSendsNothing() throws Exception {
        Atchung bus = Atchung.create();
        AtomicInteger count = new AtomicInteger();
        bus.subscribe(BEATS, b -> count.incrementAndGet());
        Timer timer = new Timer(bus);
        timer.every(Duration.ofMillis(20), BEATS, new Beat("tick"));
        timer.close();
        Thread.sleep(60);
        assertEquals(0, count.get());
    }

    /**
     * Shutdown closes the timer while a component may still be in a delivery that asks it for its next beat, as a
     * simulation's step asks for the next step: that is accepted and dropped, as a silent timer drops it, and not
     * thrown back into the lane, where it stopped the application as a fault after it had stopped.
     */
    @Test
    void aClosedTimerDropsWhatItIsAsked() throws Exception {
        Atchung bus = Atchung.create();
        AtomicInteger count = new AtomicInteger();
        bus.subscribe(BEATS, b -> count.incrementAndGet());
        Timer timer = new Timer(bus);
        timer.close();
        Timer.Scheduled once = timer.after(Duration.ofNanos(1), BEATS, new Beat("late"));
        Timer.Scheduled every = timer.every(Duration.ofMillis(5), BEATS, new Beat("late"));
        once.cancel();
        every.cancel();
        Thread.sleep(30);
        assertEquals(0, count.get());
    }

    @Test
    void aTimerWaitsSomeTime() {
        try (Timer timer = new Timer(Atchung.create())) {
            assertThrows(IllegalArgumentException.class, () -> timer.every(Duration.ZERO, BEATS, new Beat("x")));
        }
    }
}

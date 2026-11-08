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
    void everyPublishesUntilCancelled() throws Exception {
        Atchung bus = Atchung.create();
        CountDownLatch three = new CountDownLatch(3);
        AtomicInteger count = new AtomicInteger();
        bus.subscribe(BEATS, b -> {
            count.incrementAndGet();
            three.countDown();
        });
        try (Timer timer = new Timer(bus)) {
            Timer.Scheduled beat = timer.every(Duration.ofMillis(5), BEATS, new Beat("tick"));
            assertTrue(three.await(5, TimeUnit.SECONDS), "every period");
            beat.cancel();
            int at = count.get();
            Thread.sleep(60);
            assertTrue(count.get() <= at + 1, "at most the publish under way after a cancel: " + at + " then " + count.get());
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

    @Test
    void aTimerWaitsSomeTime() {
        try (Timer timer = new Timer(Atchung.create())) {
            assertThrows(IllegalArgumentException.class, () -> timer.every(Duration.ZERO, BEATS, new Beat("x")));
        }
    }
}

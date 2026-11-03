package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Time as a message source: a message published on a topic after a delay, or every period, and nothing run but the
 * publish. Any part may take one, a view or a component, because it hands no code to anyone: whoever subscribes to the
 * topic hears the message on its own lane, as it hears every other.
 *
 * {@snippet :
 * watch = timer.every(WATCH, Messages.GIT_TICK_TOPIC, new GitTick());   // while Git mode is up
 * watch.cancel();                                                       // when it goes
 * }
 *
 * <p><b>Why not a clock's {@code every}</b> (vexelray-framework's {@code docs/threading.md}, T7.2): Kronometer's runs the
 * caller's code on the GUI thread, which is a callback, and a named loop on the timeline keeps it from going quiet, so a
 * window polling something every two seconds never idles, and automation never sees it settle. Here the frame loop
 * parks between beats, and wakes only when a subscriber has something to say about one.
 *
 * <p><b>A sample, never a burst.</b> A period is measured from the end of one publish to the next, so a process that
 * was suspended, or a subscriber that held the thread, is followed by one message and not by the ones it missed. A
 * subscriber that must know how long it has been reads the time itself.
 *
 * <p><b>One thread, and not a lane.</b> A lane is where application code runs (T1.1); this thread runs only a publish,
 * as the watchdog's runs only a check, so it is the framework's like the watchdog's. A subscriber registered directly
 * on the bus, rather than through a component's mailbox, is delivered on the publishing thread, so a slow one holds
 * every beat behind it; a component's mailbox never does. One per application, built by the container and closed when
 * shutdown begins, so nothing is sent into a component that is closing.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Timer implements AutoCloseable {

    private final Atchung bus;
    private final ScheduledThreadPoolExecutor thread;

    public Timer(Atchung bus) {
        this(bus, false);
    }

    /**
     * A timer that sends nothing and starts no thread: a replay's, which is told what the timer sent, and in which
     * tick, by the recording it runs again (ROADMAP <i>Done means</i> 5). What is asked of it is accepted and dropped.
     */
    public static Timer silent(Atchung bus) {
        return new Timer(bus, true);
    }

    private Timer(Atchung bus, boolean silent) {
        this.bus = Objects.requireNonNull(bus, "bus");
        if (silent) {
            this.thread = null;
            return;
        }
        this.thread = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "vexel-timer");
            t.setDaemon(true);
            return t;
        });
        thread.setRemoveOnCancelPolicy(true);
        thread.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /** Publish {@code message} on {@code topic} once, {@code delay} from now. */
    public <T> Scheduled after(Duration delay, Topic<T> topic, T message) {
        check(delay, topic, message);
        if (thread == null) {
            return Scheduled.NONE;
        }
        return new Scheduled(thread.schedule(() -> bus.publish(topic, message), delay.toNanos(), TimeUnit.NANOSECONDS));
    }

    /** Publish {@code message} on {@code topic} every {@code period}, the first one period from now, until cancelled. */
    public <T> Scheduled every(Duration period, Topic<T> topic, T message) {
        check(period, topic, message);
        if (thread == null) {
            return Scheduled.NONE;
        }
        long nanos = period.toNanos();
        return new Scheduled(thread.scheduleWithFixedDelay(() -> bus.publish(topic, message), nanos, nanos,
                TimeUnit.NANOSECONDS));
    }

    private static void check(Duration time, Topic<?> topic, Object message) {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(message, "message");
        if (time.isZero() || time.isNegative()) {
            throw new IllegalArgumentException("a timer waits some time: " + time + " on " + topic);
        }
    }

    /** Send nothing more. Idempotent. */
    @Override
    public void close() {
        if (thread != null) {
            thread.shutdownNow();
        }
    }

    /** A message waiting to be sent, or sent every period. */
    public static final class Scheduled {

        /** What a silent timer answers: nothing to cancel. */
        static final Scheduled NONE = new Scheduled(null);

        private final ScheduledFuture<?> future;

        private Scheduled(ScheduledFuture<?> future) {
            this.future = future;
        }

        /** Send it no more. A publish already under way finishes. Idempotent. */
        public void cancel() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}

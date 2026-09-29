package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A thread that belongs to no lane, and so cannot be wedged by one: it notices a lane that has stopped draining,
 * and it ends the process when an orderly stop does not.
 *
 * <p><b>Two jobs, and the second is the reason it exists.</b> Noticing is best-effort — a report, and whatever
 * the application's policy does with it. Ending the process is not: {@link #haltAfter} calls
 * {@code Runtime.halt}, which the operating system finishes whatever any thread is doing. It is {@code halt} and
 * not {@code System.exit} because {@code exit} runs shutdown hooks, and a wedged thread can be holding what a
 * hook needs. This is the layer that depends on nothing cooperating, which is why the layers above it may be
 * best-effort.
 *
 * <p><b>It is a plain thread, in the system group.</b> Not in a lane's {@code ThreadGroup}, so an
 * {@link Lanes#interruptLane} sweep cannot reach it; not on an executor a lane could exhaust; a daemon, so it
 * never holds the process open.
 *
 * <p><b>What it does not watch is the main thread.</b> A wedged window is the case the watchdog is for, but the
 * main thread legitimately blocks for as long as a person takes in a modal native dialog, and a halt fired at
 * somebody choosing a filename is the failure this class exists to prevent, not one it may cause. That needs a
 * heartbeat that knows a dialog is up, which is the frame loop's to say.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Watchdog implements AutoCloseable {

    /** The exit status of a halt: 1, which the launcher reserves for <i>it went wrong</i>. */
    public static final int HALT_STATUS = 1;

    private static final long MAX_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long MIN_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    private final Lanes lanes;
    private final IntConsumer halter;
    private final Object lock = new Object();
    private final Map<String, Long> reported = new HashMap<>();

    private Thread thread;
    private boolean closed;
    private long haltAt;
    private boolean armed;

    public Watchdog(Lanes lanes) {
        this(lanes, status -> Runtime.getRuntime().halt(status));
    }

    /** With the halt supplied — the one thing a test cannot let happen. */
    Watchdog(Lanes lanes, IntConsumer halter) {
        this.lanes = lanes;
        this.halter = halter;
    }

    /**
     * Start watching: any lane inside one delivery for {@code thresholdNanos} is handed to {@code onStall}, once
     * per delivery. The lane recovering and stalling again is a second report; still stalled is not.
     *
     * <p>{@code onStall} runs on the watchdog's thread. It may block, but a blocked one delays the next check
     * and the halt deadline with it, so a policy that waits should say so by arming {@link #haltAfter} first.
     */
    public synchronized Watchdog start(long thresholdNanos, Consumer<Lanes.Stall> onStall) {
        if (thread != null) {
            return this;
        }
        long period = Math.max(MIN_PERIOD_NANOS, Math.min(thresholdNanos / 4, MAX_PERIOD_NANOS));
        thread = new Thread(() -> watch(thresholdNanos, period, onStall), "vexel-watchdog");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    /**
     * End the process with {@code Runtime.halt} if it is still running {@code nanos} from now.
     *
     * <p>Idempotent, and it keeps the earliest deadline: a second request cannot extend the first, because a
     * backstop that a later, more patient caller could relax is not one.
     */
    public void haltAfter(long nanos) {
        synchronized (lock) {
            long at = System.nanoTime() + nanos;
            if (!armed || at - haltAt < 0) {
                haltAt = at;
                armed = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * Stop watching and disarm any halt. Called when the process is ending on its own, which is the case the halt
     * is the backstop for.
     */
    @Override
    public void close() {
        Thread t;
        synchronized (lock) {
            closed = true;
            armed = false;
            lock.notifyAll();
        }
        synchronized (this) {
            t = thread;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void watch(long thresholdNanos, long periodNanos, Consumer<Lanes.Stall> onStall) {
        long nextCheck = System.nanoTime() + periodNanos;
        while (true) {
            synchronized (lock) {
                while (!closed) {
                    long now = System.nanoTime();
                    if (armed && now - haltAt >= 0) {
                        break;
                    }
                    long wait = nextCheck - now;
                    if (armed) {
                        wait = Math.min(wait, haltAt - now);
                    }
                    if (wait <= 0) {
                        break;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lock, wait);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                if (armed && System.nanoTime() - haltAt >= 0) {
                    halter.accept(HALT_STATUS);
                    return;
                }
            }
            if (System.nanoTime() - nextCheck >= 0) {
                check(thresholdNanos, onStall);
                nextCheck = System.nanoTime() + periodNanos;
            }
        }
    }

    private void check(long thresholdNanos, Consumer<Lanes.Stall> onStall) {
        for (Lanes.Stall stall : lanes.stalled(thresholdNanos)) {
            Long seen = reported.get(stall.lane());
            if (seen != null && seen == stall.delivery()) {
                continue;
            }
            reported.put(stall.lane(), stall.delivery());
            try {
                onStall.accept(stall);
            } catch (RuntimeException e) {
                // A policy that throws must not take the watchdog with it: it is the one thread that cannot
                // be allowed to stop, and the halt deadline is on it.
                e.printStackTrace();
            }
        }
    }
}

package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Atchung;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * A thread that belongs to no lane, and so cannot be wedged by one: it notices a lane that has stopped draining,
 * and it ends the process when an orderly stop does not.
 *
 * <p><b>Two jobs, and the second is the reason it exists.</b> Noticing is best-effort — a report, published as a
 * {@link Stalled} message, and whatever the application's policy does with it. Ending the process is not: {@link #haltAfter} calls
 * {@code Runtime.halt}, which the operating system finishes whatever any thread is doing. It is {@code halt} and
 * not {@code System.exit} because {@code exit} runs shutdown hooks, and a wedged thread can be holding what a
 * hook needs. This is the layer that depends on nothing cooperating, which is why the layers above it may be
 * best-effort.
 *
 * <p><b>It is a plain thread, in the system group.</b> Not in a lane's {@code ThreadGroup}, so an
 * {@link Lanes#interruptLane} sweep cannot reach it; not on an executor a lane could exhaust; a daemon, so it
 * never holds the process open.
 *
 * <p><b>A halt says why, and what was stuck.</b> Before ending the process it writes a line saying the deadline
 * passed, and every thread's stack as it stood, to standard error. Without that, a halt is an exit status of 1 and
 * nothing else — a launcher reports the status, not the cause, and the wedged call is gone with the process. The
 * writing is bounded like everything else here: it runs on a thread of its own, and the halt goes ahead after
 * {@link #REPORT_BOUND_NANOS} whether it finished or not, since a wedged thread can be holding the stream.
 *
 * <p><b>The main thread is watched through its beat</b> ({@link #watchMain}), never by a timer alone. A wedged window
 * is the case the watchdog is for, but the main thread legitimately parks for as long as nothing is due, and blocks
 * for as long as a person takes in a modal native dialog, and a halt fired at somebody choosing a filename is the
 * failure this class exists to prevent, not one it may cause. So the frame loop says which state it is in
 * ({@link MainThreadBeat}), and a main thread that has made no progress for the threshold, is not parked and is not in
 * a dialog the framework opened has its stack read before it is called a wedge: a thread inside the operating system's
 * own modal loop (a window being dragged, its system menu) is serving the person and is left alone. The report is a
 * {@link Stalled} naming {@link Stalled#MAIN}.
 *
 * <p><b>A last act before a halt.</b> {@link #beforeHalt} names something to do on the way out (the framework's
 * restart starts the new process there), bounded like the report, so it cannot hold the halt up.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Watchdog implements AutoCloseable {

    /** The exit status of a halt: 1, which the launcher reserves for <i>it went wrong</i>. */
    public static final int HALT_STATUS = 1;

    /** How long a halt waits for its report to be written before halting anyway. */
    static final long REPORT_BOUND_NANOS = TimeUnit.SECONDS.toNanos(2);

    private static final long MAX_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long MIN_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    /**
     * How many check periods may pass between two checks before the gap is taken for the process having been
     * suspended (the machine slept) rather than for the main thread having made no progress.
     */
    private static final long SUSPENDED_PERIODS = 4;

    private final Lanes lanes;
    private final Atchung bus;
    private final IntConsumer halter;
    private final Consumer<String> report;
    private final Object lock = new Object();
    private final Map<String, Long> reported = new HashMap<>();

    private Thread thread;
    private boolean closed;
    private long haltAt;
    /** How long the deadline that is armed was set for, for the report. */
    private long haltBound;
    private boolean armed;
    /** Run on the way out, before the halt; null for nothing. See {@link #beforeHalt}. */
    private volatile Runnable last;

    // The main thread's watch: set before start, and after it read and written only on the watchdog's thread.
    private MainThreadBeat beat;
    private long mainThreshold;
    private Predicate<StackTraceElement[]> servingOs = stack -> false;
    private long seenBeats = -1L;
    private long seenIteration = -1L;
    private long progressAt;
    private long reportedBeats = -1L;
    private long reportedIteration = -1L;
    private long lastCheck;

    /** Watching {@code lanes}, and reporting on {@code bus}. */
    public Watchdog(Lanes lanes, Atchung bus) {
        this(lanes, bus, status -> Runtime.getRuntime().halt(status));
    }

    /**
     * With the halt supplied — the one thing a test cannot let happen. Public for the framework's tests in other
     * modules, as {@link Lanes#Lanes(java.util.concurrent.ExecutorService)} is; an application has no reason to
     * replace the halt, and one that does has given up the guarantee.
     */
    public Watchdog(Lanes lanes, Atchung bus, IntConsumer halter) {
        this(lanes, bus, halter, text -> {
            System.err.print(text);
            System.err.flush();
        });
    }

    /** With the halt and where its report goes supplied. */
    Watchdog(Lanes lanes, Atchung bus, IntConsumer halter, Consumer<String> report) {
        this.lanes = lanes;
        this.bus = bus;
        this.halter = halter;
        this.report = report;
    }

    /**
     * Start watching: any lane inside one delivery for {@code thresholdNanos} is published as a {@link Stalled},
     * once per delivery. The lane recovering and stalling again is a second report; still stalled is not.
     *
     * <p>The publish is on the watchdog's thread, so a subscriber registered directly on the bus (the shell's
     * policy) runs there, and a mailbox is staged there and heard on its own lane. A direct subscriber may block, but
     * a blocked one delays the next check and the halt deadline with it, so a policy that waits should say so by
     * arming {@link #haltAfter} first.
     */
    public synchronized Watchdog start(long thresholdNanos) {
        if (thread != null) {
            return this;
        }
        long shortest = beat == null ? thresholdNanos : Math.min(thresholdNanos, mainThreshold);
        long period = Math.max(MIN_PERIOD_NANOS, Math.min(shortest / 4, MAX_PERIOD_NANOS));
        thread = new Thread(() -> watch(thresholdNanos, period), "vexel-watchdog");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    /**
     * Watch the main thread as well, through {@code beat}: one that has begun no frame and no loop iteration for
     * {@code thresholdNanos}, while not parked and not in a native dialog, is published as a {@link Stalled} naming
     * {@link Stalled#MAIN}, once per stall — unless {@code servingOs} says its stack is inside the operating system's
     * own modal loop, which a person is holding and which is not a wedge. Before {@link #start}. Nothing is looked for
     * until the loop has begun its first frame, so a slow start-up is never a stall, nor after it has ended, since
     * teardown has the shutdown bound.
     *
     * <p>{@code servingOs} is asked only about a main thread already suspected, so its stack costs nothing while the
     * loop is healthy. It must never say no to a thread inside a native modal loop: what follows a report by default is
     * a halt, and a check that refuses safe code is worse than none.
     */
    public synchronized Watchdog watchMain(MainThreadBeat beat, long thresholdNanos,
                                           Predicate<StackTraceElement[]> servingOs) {
        if (thread != null) {
            throw new IllegalStateException("the main thread is watched from the start or not at all");
        }
        this.beat = beat;
        this.mainThreshold = thresholdNanos;
        this.servingOs = servingOs;
        return this;
    }

    /**
     * Run {@code act} on the way out, just before a halt: on a thread of its own, after the halt's report, and bounded by
     * {@link #REPORT_BOUND_NANOS} as the report is, since whatever it needs may be what is wedged. A second call
     * replaces the first, and the act runs at most once.
     */
    public void beforeHalt(Runnable act) {
        last = act;
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
                haltBound = nanos;
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

    private void watch(long thresholdNanos, long periodNanos) {
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
                    reportHalt(haltBound);
                    lastAct();
                    halter.accept(HALT_STATUS);
                    return;
                }
            }
            if (System.nanoTime() - nextCheck >= 0) {
                check(thresholdNanos);
                checkMain(periodNanos);
                nextCheck = System.nanoTime() + periodNanos;
            }
        }
    }

    /**
     * Say that the deadline passed and what every thread was doing, on a thread of its own, waiting for it at most
     * {@link #REPORT_BOUND_NANOS}. The stacks are taken here, on the watchdog's thread, so they are the ones at the
     * deadline even if the writing is slow.
     */
    private void reportHalt(long boundNanos) {
        String text = haltReport(boundNanos, Thread.getAllStackTraces());
        Thread writer = new Thread(() -> report.accept(text), "vexel-watchdog-report");
        writer.setDaemon(true);
        writer.start();
        try {
            TimeUnit.NANOSECONDS.timedJoin(writer, REPORT_BOUND_NANOS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // halting regardless: that is the point of the backstop
        }
    }

    /** The {@link #beforeHalt} act, once, on a thread of its own, waited for at most {@link #REPORT_BOUND_NANOS}. */
    private void lastAct() {
        Runnable act = last;
        last = null;
        if (act == null) {
            return;
        }
        Thread runner = new Thread(() -> {
            try {
                act.run();
            } catch (RuntimeException e) {
                e.printStackTrace();   // halting regardless: the act was the best effort on the way out
            }
        }, "vexel-watchdog-last");
        runner.setDaemon(true);
        runner.start();
        try {
            TimeUnit.NANOSECONDS.timedJoin(runner, REPORT_BOUND_NANOS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One look at the main thread. Progress is a frame or a loop iteration begun since the last look; parked, in a
     * native dialog, or inside the operating system's modal loop is not a stall, however long it lasts.
     */
    private void checkMain(long periodNanos) {
        MainThreadBeat b = beat;
        if (b == null) {
            return;
        }
        long now = System.nanoTime();
        boolean suspended = lastCheck != 0L && now - lastCheck > SUSPENDED_PERIODS * periodNanos + MAX_PERIOD_NANOS;
        lastCheck = now;
        long beats = b.beats();
        long iteration = b.iteration();
        if (!b.watching() || suspended || beats != seenBeats || iteration != seenIteration
                || b.parked() || b.inNative()) {
            seenBeats = beats;
            seenIteration = iteration;
            progressAt = now;
            return;
        }
        if (now - progressAt < mainThreshold || (beats == reportedBeats && iteration == reportedIteration)) {
            return;
        }
        Thread owner = b.owner();
        if (owner != null && servingOs.test(owner.getStackTrace())) {
            // A person is holding it in the platform's own modal loop: not progress, and not a stall. Looked at again
            // from here, since leaving that loop for a wedge is a stall from then.
            progressAt = now;
            return;
        }
        reportedBeats = beats;
        reportedIteration = iteration;
        try {
            bus.publish(Stalled.TOPIC, new Stalled(Stalled.MAIN, Duration.ofNanos(now - progressAt)));
        } catch (RuntimeException e) {
            e.printStackTrace();   // as for a lane: the watchdog is the one thread that may not stop
        }
    }

    /** The report a halt writes: why, then every thread's stack, the main thread first, each whole. */
    static String haltReport(long boundNanos, Map<Thread, StackTraceElement[]> stacks) {
        StringBuilder out = new StringBuilder();
        out.append("vexelray: the process did not end within ")
                .append(TimeUnit.NANOSECONDS.toMillis(boundNanos))
                .append(" ms of being asked to, so the watchdog is halting it (exit status ").append(HALT_STATUS)
                .append("). Whatever is still running below is what it was waiting for.\n");
        List<Thread> threads = new ArrayList<>(stacks.keySet());
        threads.sort(Comparator.comparing((Thread t) -> !"main".equals(t.getName())).thenComparing(Thread::getName));
        for (Thread t : threads) {
            out.append('\n').append('"').append(t.getName()).append('"')
                    .append(t.isDaemon() ? " daemon" : "")
                    .append(" ").append(t.getState()).append('\n');
            for (StackTraceElement frame : stacks.get(t)) {
                out.append("    at ").append(frame).append('\n');
            }
        }
        return out.toString();
    }

    private void check(long thresholdNanos) {
        for (Lanes.Stall stall : lanes.stalled(thresholdNanos)) {
            Long seen = reported.get(stall.lane());
            if (seen != null && seen == stall.delivery()) {
                continue;
            }
            reported.put(stall.lane(), stall.delivery());
            try {
                bus.publish(Stalled.TOPIC, new Stalled(stall.lane(), Duration.ofNanos(stall.nanos())));
            } catch (RuntimeException e) {
                // A policy that throws, or a full mailbox, must not take the watchdog with it: it is the one thread
                // that cannot be allowed to stop, and the halt deadline is on it.
                e.printStackTrace();
            }
        }
    }
}

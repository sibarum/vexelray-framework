package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;

import java.util.function.LongSupplier;

/**
 * The frame loop's pulse, for the {@link Watchdog}: what lets it tell a main thread that is wedged from one that is
 * parked with nothing to do, and from one a person is holding in a native dialog.
 *
 * <p><b>Why the main thread needs this and a lane does not.</b> A lane is idle when it is parked on its mailbox and
 * busy inside a delivery, and {@link Lanes.Busy} says which. The main thread has three states, not two: it runs a
 * frame, it parks when nothing is due (render on demand, so a quiet window can park for as long as nobody touches it),
 * and it sits inside a native modal loop for as long as a person takes, choosing a filename. Only the first can be a
 * wedge, and the third looks exactly like one from outside. A halt fired at somebody choosing a filename is the failure
 * the watchdog exists to prevent, so the loop says which state it is in, and the watchdog reads it.
 *
 * <p><b>Written by the frame loop, on the hot path.</b> {@link #beat} runs at the top of every frame and
 * {@link #parking} just before every park, so each is a few volatile stores and nothing else: no allocation, no
 * logging, no lock. {@link #enterNative} and {@link #exitNative} bracket a native dialog run on the main thread,
 * which is rare and is not on the hot path.
 *
 * <p><b>What it cannot see by itself</b>, and so what the watchdog asks of the thread's stack before calling it a
 * wedge: an operating system's own modal loop entered from inside the message pump (a window being dragged, its
 * system menu, a drag and drop). Those are the platform's, not a dialog this framework opened, so nobody brackets them.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class MainThreadBeat {

    private static final LongSupplier NO_ITERATIONS = () -> 0L;

    /** Frames begun. One writer, the main thread. */
    private volatile long beats;
    /** {@link #beats} when the loop last parked; -1 before it ever has. */
    private volatile long parkedAfter = -1L;
    /** The loop's iteration count when it last parked, so a wake from that park is seen before the next beat. */
    private volatile long parkIteration;
    /** Native dialogs up on the main thread now. One writer, the main thread. */
    private volatile int inNative;
    /** Set when the loop has ended: teardown is the shutdown bound's to watch, not this. */
    private volatile boolean stopped;
    private volatile Thread owner;
    private volatile LongSupplier iterations = NO_ITERATIONS;

    /**
     * The loop's own count of iterations begun, readable from any thread ({@code LoopCounters.frame()} in
     * {@code vexelray-gui}). An iteration starts before the posted tasks and the message pump, which is before the
     * {@link #beat} of its frame, so with it a wake from a park is seen as soon as it happens. Without it, a park
     * that ends in a wedged posted task is taken for a park until the next beat, which never comes.
     */
    public MainThreadBeat iterations(LongSupplier iterations) {
        this.iterations = iterations == null ? NO_ITERATIONS : iterations;
        return this;
    }

    /** The thread this is the pulse of: the one whose stack the watchdog reads before it calls it a wedge. */
    public MainThreadBeat owner(Thread owner) {
        this.owner = owner;
        return this;
    }

    /** The top of a frame. Main thread, every frame: one volatile store. */
    public void beat() {
        beats = beats + 1L;
    }

    /** The loop is about to park. Main thread, before every park: two volatile stores and a counter read. */
    public void parking() {
        // The iteration first, so a reader that sees this park sees the iteration it parked in.
        parkIteration = iterations.getAsLong();
        parkedAfter = beats;
    }

    /** A native dialog is about to run on the main thread and hold it until a person answers. */
    public void enterNative() {
        inNative = inNative + 1;
    }

    /** The native dialog {@link #enterNative} announced has returned, however it ended. */
    public void exitNative() {
        inNative = inNative - 1;
    }

    /** The loop has ended. What runs on the main thread afterwards is teardown, which has a bound of its own. */
    public void stop() {
        stopped = true;
    }

    // ---- read by the watchdog -------------------------------------------------------------------------------

    /** Whether there is a loop to watch: it has begun its first frame and not ended. */
    boolean watching() {
        return beats > 0L && !stopped;
    }

    /** Frames begun so far. */
    long beats() {
        return beats;
    }

    /** The loop's iterations begun so far, or 0 with no counter. */
    long iteration() {
        return iterations.getAsLong();
    }

    /** Whether the loop is parked: it parked after its latest frame began, and no iteration has begun since. */
    boolean parked() {
        long after = parkedAfter;
        long iteration = parkIteration;
        return after == beats && iteration == iterations.getAsLong();
    }

    /** Whether a native dialog is up on the main thread. */
    boolean inNative() {
        return inNative > 0;
    }

    /** The thread being watched, or null when none was named. */
    Thread owner() {
        return owner;
    }
}

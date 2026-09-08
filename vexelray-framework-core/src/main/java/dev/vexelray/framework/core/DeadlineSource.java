package dev.vexelray.framework.core;

/**
 * Something that knows when it will next need a frame.
 *
 * <p>Implemented by anything holding a deadline: a clock with a running animation, window memory with a
 * debounced write pending, a component waiting out a cue. The framework asks every source each time the loop is
 * about to park, and parks until the earliest answer.
 *
 * <p>The seam exists because of a specific failure the hand-written edge documents. Pacing is currently one
 * expression naming every deadline holder the application happens to have:
 *
 * <pre>{@code
 * app.pacing(() -> Math.min(krono.kron().sleepTimeout().nanos(), memory.nanosUntilSettle()));
 * }</pre>
 *
 * <p>with the reason beside it: <i>"Every deadline this application holds goes in one supplier — the clock
 * knows about animations, but it does not know that the window placement is 700ms from being written, and a
 * parked loop has no next frame on which to find out."</i>
 *
 * <p>That expression is correct and it is also the wrong shape, because it has to be edited by whoever adds the
 * next deadline holder — and the failure when they forget is that the loop parks past a deadline. Nothing
 * throws; the window simply does not get written, or the animation resumes late, on a machine that was idle at
 * the time. It is the hardest class of bug on a render-on-demand loop to attribute after the fact. Being a
 * source rather than a term in somebody's {@code min} means a component brings its own deadline with it.
 */
public interface DeadlineSource {

    /**
     * Nanoseconds until this source next needs a frame.
     *
     * <p>{@code 0} or less means now. {@link Long#MAX_VALUE} means never — the correct answer for a source with
     * nothing pending, and the reason a source that is idle costs the composition nothing rather than having to
     * be removed from it.
     *
     * <p>Called on the main thread, immediately before the loop parks, every time it parks. Must be cheap and
     * must not block: this runs at the point where the frame has been presented and the application is about to
     * become idle, so anything expensive here is latency the user sees on the next interaction.
     */
    long nanosUntilNextFrame();
}

package dev.vexelray.framework.api;

/**
 * The order of work inside one frame, before the tree is reconciled and presented.
 *
 * <p>This enum is the framework's answer to the one piece of the hand-written application edge that looks
 * arbitrary and is not. Every application on this stack ends up with the same three lines in the same order:
 *
 * <pre>{@code
 * app.run(gui, maxFrames, () -> {
 *     pump(bridge);       // INPUT
 *     krono.tick();       // CLOCK
 *     memory.poll();      // SETTLE
 * });
 * }</pre>
 *
 * <p>and the order carries a reason that was written down once, in a comment in
 * {@code mainframe-template}, and then copied by hand into every application: <i>"Input first, then the clock:
 * the tick returns with its batch complete, so anything an animation posts this frame is on the bus before
 * Gui.frame reconciles it — the frame that presents a value is the frame that computed it."</i>
 *
 * <p><b>Named stages rather than {@code @Order(int)} integers.</b> An integer priority makes every hook's
 * position a negotiation with every other hook's, decided by numbers whose meaning lives in no one place; the
 * bug it produces is a frame of latency, which is invisible in a screenshot and hard to attribute. A closed
 * enum of four stages says what the positions <em>are</em>, and there is exactly one an application should be
 * putting work in.
 *
 * <p>Ordering within a single stage is declaration order, and depending on it is a mistake — if two hooks in
 * one stage must run in a given order, one of them is in the wrong stage or they are one hook.
 */
public enum FrameStage {

    /**
     * Device input onto the bus. First, because everything downstream reads what it delivers, and because a
     * frame that reconciles before its input arrived presents last frame's answer to this frame's click.
     *
     * <p>The framework's own hook here pumps the tactroller bridge. An application has no business adding one:
     * every device event flows through tactroller, and a second input path is a bug in this stack even when it
     * works ({@code vexelray-gui/CLAUDE.md}).
     */
    INPUT,

    /**
     * The frame clock: timelines advance, animations post their values. After {@link #INPUT} so that a value
     * an animation computes from this frame's input is on the bus before reconciliation, and before
     * {@link #APP} so application work sees the time it is running at rather than the previous frame's.
     */
    CLOCK,

    /**
     * The application's own per-frame work — the one stage an application should be writing hooks in.
     *
     * <p>Whatever runs here is inside the frame budget, on the main thread, every frame the loop wakes for.
     * That is the right place for reading a queue drained by a worker, and the wrong place for the work the
     * worker was doing.
     */
    APP,

    /**
     * Deferred bookkeeping that must not hold up the frame it belongs to: debounced window placement, settings
     * that have been dirtied, anything whose deadline is measured in hundreds of milliseconds.
     *
     * <p>Last on purpose. These are the hooks whose cost is allowed to be skipped rather than paid late, and
     * putting them after the application's work means a frame that overruns overruns here, where nothing the
     * eye is waiting for is queued behind them.
     */
    SETTLE
}

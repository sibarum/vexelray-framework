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
 *
 * <h2>This is the main thread's frame, and nothing else's</h2>
 *
 * <p><b>A recipe, not an ordering vocabulary.</b> Look at what actually registers here: {@code input::pump},
 * {@code krono::tick} and {@code memory::poll} are all framework-owned, and {@link #APP} is an empty slot held
 * for the application. Four stages is not a scale somebody could need a fifth point on; it is the list of
 * things one thread does between waking and presenting. The main thread is special because Vulkan makes it
 * special — <i>"Vulkan, the window and present stay on the main thread"</i> — and this enum describes that
 * thread's frame. No component enters this pipeline.
 *
 * <p><b>{@code Rate} is the other thing entirely, and they are not rivals.</b> Kronometer's {@code Rate} is
 * <i>"an independent sampling grid over the timeline"</i>, and its {@code priority} is the tie-break <i>"for
 * shreds of different domains waking at the same moment"</i>. That is where a component says when its work
 * runs. The order here is <b>causal</b> rather than ranked: {@link #CLOCK} follows {@link #INPUT} because the
 * tick reads what the pump delivered, not because it outranks it.
 *
 * <p>So the argument against integer priorities above wants a scope rather than an answer. It holds for an
 * open hook list, where unrelated parties pick numbers with no shared meaning; it does not hold for
 * {@code Rate.priority}, where one author declares a few grids whose relationship is real — physics before
 * render, because render displays what physics computed. Two collapses to refuse, so they are not
 * re-proposed: these four stages as four {@code Rate}s at priorities 0–3 loses the causality and makes "equal
 * priority" both expressible and meaningless, while everything as a {@code FrameStage} cannot express
 * independent rates at all, which is the whole reason {@code Rate} exists — <i>"an animation framework with
 * one frame rate is a toy."</i>
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
    INPUT("a hook in INPUT is pumping input, so the backend itself is blocking"),

    /**
     * The frame clock: timelines advance, animations post their values. After {@link #INPUT} so that a value
     * an animation computes from this frame's input is on the bus before reconciliation, and before
     * {@link #APP} so application work sees the time it is running at rather than the previous frame's.
     */
    CLOCK("the timeline ticks in CLOCK on one thread, so a cue or effect is doing work there that"
            + " belongs off the baton"),

    /**
     * <b>The main thread draining what a worker left for it</b> — the one stage an application should be
     * writing hooks in.
     *
     * <p>Whatever runs here is inside the frame budget, on the main thread, every frame the loop wakes for.
     * That is the right place for reading a queue a worker filled, and the wrong place for the work the worker
     * was doing. The narrower name is the useful one, because "the application's own per-frame work" invites
     * the second half in.
     *
     * <p>{@code vexelray-gui} names this stage from the other side of the seam, describing what an input
     * handler does when its effect is neither a tree mutation nor a clock operation: <i>"it drops a request on
     * one of the application's own queues — a history to restore, a file to open, a preview to render — each
     * drained once per frame from the host's beforeFrame hook."</i> This is that hook. It is also
     * {@code Pump}'s contract exactly — <i>"call {@code drain()} on one owner thread (e.g. a render/UI thread,
     * once per frame)"</i> — for an application whose queue is an Atchung mailbox rather than a flag.
     *
     * <p>The designer is the worked example: its viewport marches on the GPU, <i>"a {@code VkQueue} is not
     * thread-safe, so a drag handler running on a worker must only move the camera and raise a flag —
     * {@code pump()} is called from the frame loop and is the only place the GPU is touched"</i>, and that
     * {@code pump()} has exactly one home, one hook here.
     *
     * <p><b>The framework registers nothing in this stage, and that is not an omission.</b> The Gui's own
     * mutation and navigation mailbox is drained inside {@code Gui.frame}, not here, so a framework hook on
     * that bus would be a second drain point landing at a different moment than the tree edits it has to agree
     * with. What gets drained here is the application's queues, and only the application knows what they are.
     */
    APP("APP is for draining a queue a worker filled, and the wrong place for the work the worker was"
            + " doing"),

    /**
     * Deferred bookkeeping that must not hold up the frame it belongs to: debounced window placement, settings
     * that have been dirtied, anything whose deadline is measured in hundreds of milliseconds.
     *
     * <p>Last on purpose. These are the hooks whose cost is allowed to be skipped rather than paid late, and
     * putting them after the application's work means a frame that overruns overruns here, where nothing the
     * eye is waiting for is queued behind them.
     */
    SETTLE("SETTLE is deferred bookkeeping, so something registered there is doing real work");

    private final String whenSlow;

    FrameStage(String whenSlow) {
        this.whenSlow = whenSlow;
    }

    /**
     * Where to look when this stage has held the main thread long enough for the window to stop.
     *
     * <p>On the stage rather than in whatever prints it, because it is the stage's own knowledge — the same
     * rule that keeps this repo free of switches over closed types. Each of these is the short form of the
     * paragraph above it, written for the moment somebody is reading a warning instead of reading this file.
     * A reader told that {@code CLOCK} overran, and reminded that the timeline is single-threaded by
     * construction, has somewhere to go; one told that "a frame took 300ms" does not.
     *
     * <p>The framework has no way to name the hook that did it — a hook is a {@code Runnable}, and the lambda
     * implementing one carries no name worth printing — so the stage is the whole of the address, which is
     * why it has to be a good one.
     */
    public String whenSlow() {
        return whenSlow;
    }
}

package dev.vexelray.framework.core;

import dev.vexelray.framework.api.FrameStage;

import java.util.ArrayList;
import java.util.List;

/**
 * Every per-frame hook the application has, flattened into one array in stage order, walked once a frame.
 *
 * <p><b>The shape is the point.</b> This is the only framework code that runs inside the frame budget, so it is
 * the only place where a framework's usual machinery is not merely inelegant but disqualifying. Sixty times a
 * second, an event bus with listener registration, an iterator, a lambda capture per dispatch or a reflective
 * call would each cost more than the work being dispatched — and worse, would cost it in allocations, on the
 * thread that must not pause. A realtime frame loop is where "the framework is only 2% of the request" stops
 * being an acceptable answer.
 *
 * <p>So the stage ordering is resolved at build time, once, into a flat {@code Runnable[]}: the walk is a
 * counted loop over an array field with no allocation, no comparison and no branch per element. Stages exist in
 * the source and in {@link #stages}; at runtime they have already been spent.
 *
 * <p>Not thread-safe and not meant to be. Hooks are added while the application is being built and the array is
 * frozen by {@link #seal()} before the first frame — after which this object is read-only, on one thread, which
 * is what lets the walk be as plain as it is.
 */
public final class FrameHooks {

    private final List<Entry> pending = new ArrayList<>();

    private Runnable[] hooks = new Runnable[0];
    private FrameStage[] stages = new FrameStage[0];
    private boolean sealed;

    private record Entry(FrameStage stage, Runnable hook) {
    }

    /**
     * Register {@code hook} to run in {@code stage}.
     *
     * <p>Order within one stage is registration order, and depending on it is a mistake — see
     * {@link FrameStage}. Only legal before {@link #seal()}.
     */
    public FrameHooks add(FrameStage stage, Runnable hook) {
        if (sealed) {
            throw new IllegalStateException("frame hooks are sealed; add before the first frame");
        }
        pending.add(new Entry(java.util.Objects.requireNonNull(stage, "stage"),
                java.util.Objects.requireNonNull(hook, "hook")));
        return this;
    }

    /**
     * Freeze the registrations into the flat array {@link #run()} walks.
     *
     * <p>Sorting happens here and nowhere else. A stable sort by stage ordinal, so registration order survives
     * within a stage; done once, at startup, on a list that is then dropped.
     */
    public FrameHooks seal() {
        if (sealed) {
            return this;
        }
        pending.sort(java.util.Comparator.comparingInt(e -> e.stage().ordinal()));
        hooks = new Runnable[pending.size()];
        stages = new FrameStage[pending.size()];
        for (int i = 0; i < pending.size(); i++) {
            hooks[i] = pending.get(i).hook();
            stages[i] = pending.get(i).stage();
        }
        pending.clear();
        sealed = true;
        return this;
    }

    /**
     * Run every hook, in order. Called once per frame, before the tree is reconciled.
     *
     * <p>No try/catch. A hook that throws takes the loop down, and that is the honest outcome: the alternative
     * is a frame loop that keeps presenting while some part of the application has stopped updating, which
     * shows up as a display that is subtly wrong rather than a program that has stopped. Hooks are documented
     * as not throwing ({@code @BeforeFrame}), and the places on this stack where a per-frame operation can fail
     * transiently already swallow it at the source, where there is enough context to know that dropping one
     * frame's input is the right answer.
     */
    public void run() {
        Runnable[] local = hooks;
        for (int i = 0; i < local.length; i++) {
            local[i].run();
        }
    }

    /** How many hooks are registered — for diagnostics, and for the tests. */
    public int size() {
        return sealed ? hooks.length : pending.size();
    }

    /** The stage of each hook, in walk order. Diagnostics: this is what a startup dump prints. */
    public FrameStage[] stages() {
        return stages.clone();
    }
}

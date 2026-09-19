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
 *
 * <p><b>This is the barrier's degenerate case, and saying so is what keeps it able to grow.</b> A flat
 * {@code Runnable[]} walked on one thread is the N=1 answer to a question the concurrency model asks in
 * general: release at tick, drain, await quiescence, reconcile. With one thread the first and last are
 * nothing, quiescence is the return of the call, and what remains is the walk. The no-allocation rigour above
 * is right and should stay — but it is a property of the frame budget, not an argument that the shape is
 * final, and without this paragraph it will be defended into one. See <i>the concurrency model</i> in
 * {@code docs/architecture.md}; a placed component's work happens on {@link Lanes}, and what reaches a frame
 * from it is drained at {@code FrameStage.APP} rather than run here.
 */
public final class FrameHooks {

    private final List<Entry> pending = new ArrayList<>();

    private Runnable[] hooks = new Runnable[0];
    private FrameStage[] stages = new FrameStage[0];
    private boolean sealed;

    /** One entry per run of consecutive hooks sharing a stage: which stage, and where the run ends. */
    private FrameStage[] runStage = new FrameStage[0];
    private int[] runEnd = new int[0];

    private long overrunNanos;
    private StageOverrun overrun;

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
        sealStageRuns();
        sealed = true;
        return this;
    }

    /**
     * Precompute where each stage's run of hooks begins and ends, so {@link #run()} can time a stage without
     * asking which stage it is in per hook.
     *
     * <p>This is the same trade the sort above makes and for the same reason: the stage structure is known at
     * startup and spent there, so the walk stays a counted loop over an array with nothing to decide. Timing
     * per stage without this would put a comparison on every element, which is the one thing that file's
     * shape exists to avoid.
     */
    private void sealStageRuns() {
        int runs = 0;
        for (int i = 0; i < stages.length; i++) {
            if (i == 0 || stages[i] != stages[i - 1]) {
                runs++;
            }
        }
        runStage = new FrameStage[runs];
        runEnd = new int[runs];
        int r = -1;
        for (int i = 0; i < stages.length; i++) {
            if (i == 0 || stages[i] != stages[i - 1]) {
                r++;
                runStage[r] = stages[i];
            }
            runEnd[r] = i + 1;
        }
    }

    /**
     * Be told when one stage held the main thread for longer than {@code thresholdNanos}.
     *
     * <p><b>Measured here, reported elsewhere</b>, and the split is a layering rule rather than a preference:
     * this module is JDK-only, so it has no channel to warn on and no opinion about what a warning should say.
     * It knows the one thing nobody else can see — which stage the time went into — and hands that to whoever
     * does. {@code VexelApplication} connects it to the stack's diagnostics channel.
     *
     * <p>The listener runs <b>on the main thread, inside the frame that overran</b>. It is called only on a
     * breach, so it may allocate and format; a frame that has already lost a third of a second is not one to
     * be careful about a string in. It must not block, for the obvious reason.
     *
     * <p>{@code thresholdNanos} of zero or less turns the measurement off entirely.
     */
    public FrameHooks onOverrun(long thresholdNanos, StageOverrun listener) {
        this.overrunNanos = listener == null ? 0L : thresholdNanos;
        this.overrun = listener;
        return this;
    }

    /**
     * Told that {@code stage} took {@code nanos} — longer than anyone watching a window would forgive.
     *
     * <p>Primitive {@code long} rather than a {@code Duration} or a boxed type, because this is declared in the
     * one file that runs inside the frame budget and a functional interface that boxes would put an allocation
     * on the breach path of every stall.
     */
    @FunctionalInterface
    public interface StageOverrun {
        void overran(FrameStage stage, long nanos);
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
        long threshold = overrunNanos;
        if (threshold <= 0) {
            for (int i = 0; i < local.length; i++) {
                local[i].run();
            }
            return;
        }
        // One clock read per stage boundary rather than per hook, which is what sealStageRuns bought. Four
        // stages is eight nanoTime calls a frame, on the order of 200ns against a 16ms budget -- and the
        // reason it is worth paying always rather than behind a flag is that the failure it catches is
        // invisible by construction. A blocking call on this thread does not throw and does not log; the
        // window simply stops, which reads as "the application is slow" and sends nobody to the right file.
        // A diagnostic a consumer has to switch on is one the consumer who needed it never saw.
        int from = 0;
        for (int r = 0; r < runEnd.length; r++) {
            long started = System.nanoTime();
            int end = runEnd[r];
            for (int i = from; i < end; i++) {
                local[i].run();
            }
            long took = System.nanoTime() - started;
            if (took > threshold) {
                overrun.overran(runStage[r], took);
            }
            from = end;
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

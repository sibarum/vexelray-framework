package dev.vexelray.framework.core;

import dev.vexelray.framework.api.FrameStage;

import dev.vexelray.framework.api.Stability;
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
@Stability(Stability.Level.EXPERIMENTAL)
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
    private HookFailure failure;

    /** What a hook that threw is replaced with: it has stopped, and the rest of the frame has not. */
    private static final Runnable QUARANTINED = () -> { };

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
     * Be told when a hook throws, and keep the frame loop running without it.
     *
     * <p>With a listener, a hook that throws a {@link RuntimeException} is <b>quarantined</b>: replaced for good
     * by a no-op, so it fails once rather than once a frame, and reported. Without one, it propagates and takes
     * the loop down, which is what a bare container under test should see. An {@link Error} always propagates;
     * that is the VM in trouble, not a hook.
     *
     * <p>Measured here and reported elsewhere, as {@link #onOverrun} is, for the same layering reason.
     */
    public FrameHooks onFailure(HookFailure listener) {
        this.failure = listener;
        return this;
    }

    /**
     * Told that the hook at {@code index} in walk order, in {@code stage}, threw {@code thrown} and will not run
     * again. On the main thread, inside the frame; called once per hook, so it may allocate and log.
     */
    @FunctionalInterface
    public interface HookFailure {
        void failed(FrameStage stage, int index, RuntimeException thrown);
    }

    /**
     * Run every hook, in order. Called once per frame, before the tree is reconciled.
     *
     * <p><b>A hook that throws is contained, not fatal</b>, once {@link #onFailure} is set, which the shell
     * always does. This reverses an earlier ruling, which let a throwing hook take the loop down on the ground
     * that a loop presenting while part of the application has stopped is a display subtly wrong rather than a
     * program visibly stopped. What that ruling assumed was that hooks are the application's own code, documented
     * not to throw. The hook that disproved it was the framework's: {@code WindowMemory.poll} read the bounds of a
     * settings window the user had just closed, and the calculator went down with the user's tape in it. A frame
     * loop is the one place where one part failing takes every other part with it, so the failure is reported
     * loudly, once, with its stack, and the part that failed stops. The display is not subtly wrong; it is
     * missing one behaviour, and the log says which.
     *
     * <p>The {@code try} costs nothing on the path that does not throw: no allocation, no extra branch per hook.
     */
    public void run() {
        Runnable[] local = hooks;
        long threshold = overrunNanos;
        if (threshold <= 0) {
            for (int i = 0; i < local.length; i++) {
                try {
                    local[i].run();
                } catch (RuntimeException e) {
                    contain(i, e);
                }
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
                try {
                    local[i].run();
                } catch (RuntimeException e) {
                    contain(i, e);
                }
            }
            long took = System.nanoTime() - started;
            if (took > threshold) {
                overrun.overran(runStage[r], took);
            }
            from = end;
        }
    }

    /** Quarantine the hook at {@code index} and report it, or rethrow if nobody is listening. Off the hot path. */
    private void contain(int index, RuntimeException thrown) {
        HookFailure listener = failure;
        if (listener == null) {
            throw thrown;
        }
        hooks[index] = QUARANTINED;
        listener.failed(stages[index], index, thrown);
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

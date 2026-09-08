package dev.vexelray.framework.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The composition of every {@link DeadlineSource} in the application: park until the earliest of them.
 *
 * <p>One line of arithmetic, and the reason it is a class is that the set it reduces over is decided by the
 * dependency graph rather than by whoever wrote the expression. See {@link DeadlineSource} for what goes wrong
 * when it is an expression.
 */
public final class Pacing implements DeadlineSource {

    private final List<DeadlineSource> pending = new ArrayList<>();

    private DeadlineSource[] sources = new DeadlineSource[0];
    private boolean sealed;

    /** Add a source. Only legal before {@link #seal()}. */
    public Pacing add(DeadlineSource source) {
        if (sealed) {
            throw new IllegalStateException("pacing is sealed; add before the first frame");
        }
        pending.add(java.util.Objects.requireNonNull(source, "source"));
        return this;
    }

    /** Freeze the sources into the array {@link #nanosUntilNextFrame()} walks. */
    public Pacing seal() {
        if (!sealed) {
            sources = pending.toArray(new DeadlineSource[0]);
            pending.clear();
            sealed = true;
        }
        return this;
    }

    /** How many sources are registered. */
    public int size() {
        return sealed ? sources.length : pending.size();
    }

    /**
     * The earliest deadline any source holds, or {@link Long#MAX_VALUE} when none of them holds one — which is
     * the answer that lets the loop park indefinitely and wait to be woken by input.
     *
     * <p>Negative answers are clamped to zero rather than propagated. A source that is already overdue reports
     * a negative interval, and a negative timeout means "no timeout" to some of the platform waits underneath
     * this, so passing one through would turn "a frame was due 2ms ago" into "park until something happens" —
     * a missed deadline becoming a hang, which is the one way this composition could fail worse than the
     * expression it replaces.
     */
    @Override
    public long nanosUntilNextFrame() {
        DeadlineSource[] local = sources;
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < local.length; i++) {
            long next = local[i].nanosUntilNextFrame();
            if (next < earliest) {
                earliest = next;
            }
        }
        return Math.max(earliest, 0L);
    }
}

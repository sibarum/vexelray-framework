package dev.vexelray.framework.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * <b>Every provider of a type</b>, as a {@link Provides} method's parameter: zero or more, built in this run's mode, in
 * an order that is stable and means nothing.
 *
 * <p>This is how a part takes pieces it does not name. A window's owner declares what it holds as a type of its own (a
 * {@code SidePiece}), takes {@code All<SidePiece>}, and lays them out; anything else provides a {@code SidePiece}, and
 * leaving it out of the build leaves one fewer. Nothing adds itself to anything, so the order things are built in
 * cannot show, and the owner names none of what fills it ({@code docs/plans/regions.md}).
 *
 * <p>{@code Optional<T>} is the other shape: zero or one provider of {@code T}, and two is the conflict it always was.
 *
 * <p><b>Checked by the processor.</b> The providers of a type taken as {@code All<T>} are exempt from <i>exactly one
 * provider wins</i>, which is what they are for, and so a type taken as {@code All<T>} anywhere cannot also be taken
 * as a plain {@code T} or an {@code Optional<T>}: it is a set, not a part. A {@link Default} provider of it is refused,
 * since there is nothing for it to stand back from. Only a recipe takes one.
 *
 * <p><b>The order</b> is the providers' qualified names, configuration class and then method, so a build is the same
 * every time; it carries no meaning, and a part whose pieces have an order of their own says so in the piece's type
 * and sorts by it (ruled 2026-11-03, regions.md).
 *
 * @param <T> the type every piece is provided as
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class All<T> implements Iterable<T> {

    private final List<T> pieces;

    private All(List<T> pieces) {
        this.pieces = pieces;
    }

    /**
     * The pieces given, leaving out a null: what the generated wiring calls, with a provider that this mode does not
     * build as null.
     */
    @SafeVarargs
    public static <T> All<T> of(T... pieces) {
        List<T> out = new ArrayList<>(pieces.length);
        for (T piece : pieces) {
            if (piece != null) {
                out.add(piece);
            }
        }
        return new All<>(Collections.unmodifiableList(out));
    }

    /** The pieces, in their order; unmodifiable. */
    public List<T> list() {
        return pieces;
    }

    public int size() {
        return pieces.size();
    }

    public boolean isEmpty() {
        return pieces.isEmpty();
    }

    @Override
    public Iterator<T> iterator() {
        return pieces.iterator();
    }

    @Override
    public String toString() {
        return "All" + pieces;
    }
}

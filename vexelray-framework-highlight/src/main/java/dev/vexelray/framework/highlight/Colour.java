package dev.vexelray.framework.highlight;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.api.Stability;
import dev.vexelray.gui.core.style.Paint;

import java.util.Objects;

/**
 * One rule of a colour table: a token in {@link #scope()}, or in a scope under it, is drawn in {@link #paint()}.
 *
 * <p>"Under" is TextMate's own rule: a rule's scope matches a token's scope that equals it or continues it at a dot, so
 * {@code keyword} colours {@code keyword.control.java} and not {@code keywords}. Which rule wins is {@link Colours}'.
 *
 * <p>A {@link dev.vexelray.gui.core.style.Role} is a paint, and the usual one: the spans hold it, so a document is
 * repainted in a new look with nothing re-marked. A fixed {@link Color} is the same in every theme. Hold each rule once,
 * as a table does, so that two marks of the same text compare equal: a paint made by a lambda at every call is a new
 * paint every time.
 *
 * <p>A final class with a factory rather than a record, so a field it gains (a weight or a slope, which a span can
 * already carry) is not a change to every application that builds one.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Colour {

    private final String scope;
    private final Paint paint;

    private Colour(String scope, Paint paint) {
        this.scope = scope;
        this.paint = paint;
    }

    /**
     * {@code scope} and everything under it in {@code paint}.
     *
     * @throws IllegalArgumentException for a blank scope, or one with a space in it: a rule names one scope, and a
     *                                  TextMate selector of several is not something this table matches
     * @throws NullPointerException     for a null paint
     */
    public static Colour of(String scope, Paint paint) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(paint, "paint");
        if (scope.isBlank() || scope.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("a colour's scope is one TextMate scope, like \"keyword\" or"
                    + " \"entity.name.type\", and \"" + scope + "\" is not");
        }
        return new Colour(scope, paint);
    }

    /** As {@link #of(String, Paint)}, in a fixed colour: the same in every theme. */
    public static Colour of(String scope, Color color) {
        return of(scope, Paint.of(Objects.requireNonNull(color, "color")));
    }

    /** The scope this rule names, a prefix of the scopes it colours at a dot. */
    public String scope() {
        return scope;
    }

    /** What a token in that scope is drawn in. */
    public Paint paint() {
        return paint;
    }

    /** Whether this rule colours a token in {@code tokenScope}: it is the rule's scope, or under it. */
    boolean matches(String tokenScope) {
        return tokenScope.startsWith(scope)
                && (tokenScope.length() == scope.length() || tokenScope.charAt(scope.length()) == '.');
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Colour c && c.scope.equals(scope) && c.paint.equals(paint);
    }

    @Override
    public int hashCode() {
        return scope.hashCode() * 31 + paint.hashCode();
    }

    @Override
    public String toString() {
        return "Colour[" + scope + " " + paint + "]";
    }
}

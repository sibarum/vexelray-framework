package dev.vexelray.framework.highlight;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;

import java.util.List;
import java.util.Objects;

/**
 * A colour table: what each kind of token is drawn in, as {@link Colour} rules in order.
 *
 * <p><b>The most specific scope wins, then the first rule.</b> A token has a stack of scopes, outermost first
 * ({@code source.java}, {@code meta.class.java}, {@code storage.modifier.java}); the innermost one any rule matches
 * decides, and among the rules that match it the first in the table does. So a table lists a narrow rule before a broad
 * one it refines ({@code constant.numeric} before {@code constant}) only when both would match the same scope, and need
 * not order anything else. A token no rule matches is not coloured: it is drawn in the field's own ink.
 *
 * <p>This is the application's look, and the one thing about highlighting that is: the grammars, the tokenizer and the
 * matching are {@link Highlighting}'s. An application provides its own table with a {@code @Provides Colours}, which
 * backs off {@link HighlightStarter}'s {@link #standard()}.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Colours {

    private final List<Colour> rules;

    private Colours(List<Colour> rules) {
        this.rules = rules;
    }

    /** A table of {@code rules}, in order. */
    public static Colours of(Colour... rules) {
        return of(List.of(rules));
    }

    /** A table of {@code rules}, in order; copied, so changing the list afterwards changes nothing here. */
    public static Colours of(List<Colour> rules) {
        return new Colours(List.copyOf(Objects.requireNonNull(rules, "rules")));
    }

    /**
     * The stack's own table, what {@link HighlightStarter} colours with when the application provides none: the
     * families every grammar shares, each a {@link Role} derived from the palette in use, so it follows a change of
     * look and reads on any page.
     *
     * <p>Each colour sits at the same place between the page and the ink in every palette (most of the way to the
     * ink, which holds it at 4.5:1 or better on the page in the stack's dark and light looks), at a fixed hue: keywords
     * in the accent's, strings green, numbers and constants amber, types cyan, functions gold, tags red. Comments and
     * punctuation are the palette's dim ink, quieter than the code they sit in. Plain names are left in the field's ink.
     */
    public static Colours standard() {
        return STANDARD;
    }

    /** The rules, in order; unmodifiable. */
    public List<Colour> rules() {
        return rules;
    }

    /** The paint of a token whose innermost scope is {@code scope}, or null when no rule colours it. */
    public Paint paintOf(String scope) {
        if (scope == null) {
            return null;
        }
        for (Colour rule : rules) {
            if (rule.matches(scope)) {
                return rule.paint();
            }
        }
        return null;
    }

    /** The paint of a token in {@code scopes}, outermost first: the innermost any rule colours decides. */
    Paint paintOf(List<String> scopes) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            Paint paint = paintOf(scopes.get(i));
            if (paint != null) {
                return paint;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Colours c && c.rules.equals(rules);
    }

    @Override
    public int hashCode() {
        return rules.hashCode();
    }

    @Override
    public String toString() {
        return "Colours" + rules;
    }

    // ---------------------------------------------------------------- the standard table

    /** How far from the page towards the ink a code colour sits: most of the way, so it reads as text. */
    private static final double TOWARD_INK = 0.72;

    /** A colour at {@code hueDegrees} (Oklab), at the code lightness of whichever palette is drawing. */
    private static Role hue(double hueDegrees) {
        return p -> Oklab.polar(lightness(p), chroma(p), hueDegrees).toColor();
    }

    private static double lightness(Palette p) {
        return p.page().l() + TOWARD_INK * (p.ink().l() - p.page().l());
    }

    /** A little more colour on a light page, where the same chroma reads paler. */
    private static double chroma(Palette p) {
        return p.page().l() > p.ink().l() ? 0.12 : 0.10;
    }

    // Each held once: a span compares its paint, so two marks of one text are equal only if the rules are the same.
    private static final Role KEYWORD = p -> Oklab.polar(lightness(p), chroma(p), p.accent().hueDegrees()).toColor();
    private static final Role STRING = hue(140);
    private static final Role CONSTANT = hue(60);
    private static final Role FUNCTION = hue(95);
    private static final Role TYPE = hue(200);
    private static final Role PARAMETER = hue(75);
    private static final Role TAG = hue(15);
    private static final Role ATTRIBUTE = hue(235);
    private static final Role LINK = hue(175);
    private static final Role DELETED = hue(20);

    private static final Colours STANDARD = of(
            Colour.of("comment", Role.DIM),
            Colour.of("string", STRING),
            Colour.of("constant", CONSTANT),
            Colour.of("support.constant", CONSTANT),
            Colour.of("keyword", KEYWORD),
            Colour.of("storage", KEYWORD),
            Colour.of("entity.name.function", FUNCTION),
            Colour.of("support.function", FUNCTION),
            Colour.of("entity.name.type", TYPE),
            Colour.of("entity.other.inherited-class", TYPE),
            Colour.of("support.type", TYPE),
            Colour.of("support.class", TYPE),
            Colour.of("variable.parameter", PARAMETER),
            Colour.of("entity.name.section", KEYWORD),
            Colour.of("entity.name.tag", TAG),
            Colour.of("entity.other.attribute-name", ATTRIBUTE),
            Colour.of("markup.heading", KEYWORD),
            Colour.of("markup.bold", FUNCTION),
            Colour.of("markup.italic", KEYWORD),
            Colour.of("markup.inline.raw", TYPE),
            Colour.of("markup.fenced_code", TYPE),
            Colour.of("markup.underline.link", LINK),
            Colour.of("markup.quote", Role.DIM),
            Colour.of("markup.inserted", STRING),
            Colour.of("markup.deleted", DELETED),
            Colour.of("markup.changed", FUNCTION),
            Colour.of("meta.diff", KEYWORD),
            Colour.of("invalid", DELETED),
            Colour.of("punctuation", Role.DIM));
}

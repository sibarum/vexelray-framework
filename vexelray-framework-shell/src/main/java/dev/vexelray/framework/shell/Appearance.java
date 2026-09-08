package dev.vexelray.framework.shell;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

/**
 * The look, and the smallest window it stays coherent in — the two things that must be settled before the first
 * widget exists.
 *
 * <p>Values only, so this belongs to {@code Phase.CONFIG}: a theme and two lengths need no {@code Gui}, which is
 * exactly why they can be decided before there is one. The framework applies them to the {@code Gui} the moment
 * it creates it, and the ordering constraint that has been a comment in four applications —
 *
 * <blockquote>The look is a preference, so it is applied before anything is built: a role resolves at the moment
 * a widget writes a prop, so a theme set afterwards reaches the renderer's own chrome and nothing else.</blockquote>
 *
 * <p>— stops being something an author has to know. There is no way to hand the framework an appearance late,
 * because the phase it is asked for in is earlier than the phase a widget can exist in.
 *
 * @param theme     the look. {@link Theme#DARK} is the framework's own default, stated rather than implied
 * @param minWidth  the narrowest the layout stays coherent at — a floor, not the design size. {@code null} for
 *                  no floor, which is what an application that has not thought about it should get rather than
 *                  a number the framework invented
 * @param minHeight the shortest, on the same terms
 */
public record Appearance(Theme theme, Length minWidth, Length minHeight) {

    /** Dark, with no minimum size. What an application that says nothing gets. */
    public static final Appearance DEFAULT = new Appearance(Theme.DARK, null, null);

    public Appearance {
        if (theme == null) {
            theme = Theme.DARK;
        }
    }

    /** This look, with no size floor. */
    public static Appearance of(Theme theme) {
        return new Appearance(theme, null, null);
    }

    /** This look, and the smallest window it stays coherent in. */
    public static Appearance of(Theme theme, Length minWidth, Length minHeight) {
        return new Appearance(theme, minWidth, minHeight);
    }

    /** True when a size floor was given, and so when {@code Gui.minSize} should be called at all. */
    public boolean hasMinSize() {
        return minWidth != null && minHeight != null;
    }

    /**
     * The colour behind the tree.
     *
     * <p>Derived from the theme rather than configured separately, for the reason the scaffold gives about the
     * clear colour it passes to a capture: <i>"the same role the root paints, so the frame is never a second
     * opinion"</i>. Two applications currently pass this as three float literals, and one of them has drifted
     * from its own theme.
     */
    public Color page() {
        return theme.color(Role.PAGE);
    }
}

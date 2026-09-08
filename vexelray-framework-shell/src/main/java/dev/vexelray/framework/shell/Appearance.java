package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.os.Decorations;

import java.util.List;

/**
 * How this application looks, and where its chrome lives — declared once, as values, before there is a
 * {@code Gui} to apply any of it to.
 *
 * <p><b>The look belongs to the application, completely.</b> The framework owns <em>where</em> the title bar
 * sits and what framework tools appear in it; it has no opinion about how any of it is drawn. {@link #theme} is
 * whatever the application says it is — including a {@link Theme} of its own construction, with its own
 * nine-anchor {@code Palette}, its own {@code Shading} and its own {@code Relief} — and the chrome the
 * framework builds reads that same theme rather than one of its own. An application that wants to draw things
 * differently is not fighting a default here; it is supplying the only value there is.
 *
 * <p>That direction matters and is easy to get backwards. Chrome <em>placement</em> is the framework's, because
 * a screenshot button is only free everywhere if the strip it lives in means the same thing in every window
 * ({@code vexelray-gui/docs/automation.md} §7). Chrome <em>appearance</em> is the application's, because it is
 * appearance.
 *
 * <p>Being values is what makes {@code Phase.CONFIG} the right place for all of it: a theme and two lengths
 * need no {@code Gui}, which is exactly why they can be settled before there is one. The framework applies them
 * at the moment it creates one, and the constraint that used to be a comment in four applications —
 *
 * <blockquote>The look is a preference, so it is applied before anything is built: a role resolves at the
 * moment a widget writes a prop, so a theme set afterwards reaches the renderer's own chrome and nothing
 * else.</blockquote>
 *
 * <p>— stops being something an author has to know. There is no way to hand the framework an appearance late,
 * because the phase it is asked for in is earlier than the phase a widget can exist in.
 *
 * @param theme       the look, in full. {@link Theme#DARK} when the application says nothing
 * @param minWidth    the narrowest the layout stays coherent at — a floor, not the design size. {@code null}
 *                    for no floor, which is what an application that has not thought about it should get
 *                    rather than a number the framework invented
 * @param minHeight   the shortest, on the same terms
 * @param decorations whether the application draws its own frame ({@link Decorations#CLIENT}, and then it gets
 *                    a framework title bar) or the OS draws it ({@link Decorations#SYSTEM})
 * @param instruments the framework tools in the title bar. {@link WindowInstrument#standard()} by default,
 *                    and reducible to fewer or none — the doc's rule is that the framework supplies a default
 *                    set and a window may take less, so the utility is free to <em>enable</em> rather than
 *                    present unconditionally
 */
public record Appearance(Theme theme, Length minWidth, Length minHeight, Decorations decorations,
                         List<WindowInstrument> instruments) {

    /** Dark, no size floor, application-drawn frame, the standard instruments. */
    public static final Appearance DEFAULT = new Appearance(Theme.DARK, null, null, null, null);

    public Appearance {
        if (theme == null) {
            theme = Theme.DARK;
        }
        // CLIENT rather than SYSTEM as the default: three of the four applications on this stack draw their own
        // frame, and it is what makes a framework title bar -- and so the screenshot instrument -- reachable
        // at all. An application that wants the OS frame says so and gets no bar.
        if (decorations == null) {
            decorations = Decorations.CLIENT;
        }
        instruments = instruments == null ? WindowInstrument.standard() : List.copyOf(instruments);
    }

    /** This look, with everything else defaulted. */
    public static Appearance of(Theme theme) {
        return new Appearance(theme, null, null, null, null);
    }

    /** This look, and the smallest window it stays coherent in. */
    public static Appearance of(Theme theme, Length minWidth, Length minHeight) {
        return new Appearance(theme, minWidth, minHeight, null, null);
    }

    /** The same, with a size floor. */
    public Appearance minSize(Length width, Length height) {
        return new Appearance(theme, width, height, decorations, instruments);
    }

    /** The same, with the frame drawn by whoever this says. */
    public Appearance decorations(Decorations decorations) {
        return new Appearance(theme, minWidth, minHeight, decorations, instruments);
    }

    /**
     * The same, with these framework tools in the title bar.
     *
     * <p>{@code List.of()} for none, which is the answer for a shipped application that does not want a
     * screenshot button in its caption.
     */
    public Appearance instruments(List<WindowInstrument> instruments) {
        return new Appearance(theme, minWidth, minHeight, decorations, instruments);
    }

    /** True when a size floor was given, and so when {@code Gui.minSize} should be called at all. */
    public boolean hasMinSize() {
        return minWidth != null && minHeight != null;
    }

    /** True when the application draws its own frame, and so when there is a framework title bar. */
    public boolean drawsOwnFrame() {
        return decorations == Decorations.CLIENT;
    }
}

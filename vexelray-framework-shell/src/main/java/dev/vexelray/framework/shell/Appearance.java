package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
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
 * <p>Being values is what makes {@code Phase.CONFIG} the right place for all of it: a theme, two lengths and
 * three numbers need no {@code Gui}, which is exactly why they can be settled before there is one. The framework applies them
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
 * @param zoom        how far the UI zoom goes and what one press moves it by. {@link ZoomRange#DEFAULT} when
 *                    the application says nothing, which is the range five places on this stack had already
 *                    agreed on. Never null
 */
public record Appearance(Theme theme, Length minWidth, Length minHeight, Decorations decorations,
                         List<WindowInstrument> instruments, ZoomRange zoom) {

    /** Dark, no size floor, application-drawn frame, the standard instruments, the agreed zoom range. */
    public static final Appearance DEFAULT = new Appearance(Theme.DARK, null, null, null, null, null);

    /**
     * How far the UI zoom goes, and what one press of a zoom chord moves it by.
     *
     * <p><b>The range is the framework's; the chord is the application's.</b> That split is the whole of this
     * type, and it is easy to read as the wrong shape of duplication.
     * {@code gui.zoomRange(0.5f, 3f, 1.25f)} appears in five places on this stack — {@code CalculatorWiring},
     * {@code TextEditorApp}, {@code Console}, {@code Desktop} and {@code mainframe-template}'s scaffold — with
     * identical numbers and no reason given at any of them, which is what a default looks like before anybody
     * has taken it. What chord zooms, or whether zooming exists at all, stays where {@code CalculatorWiring}
     * put it: <i>"which chord zooms, or whether zooming exists at all, is not something a framework should be
     * choosing."</i> How far the zoom goes is a different question, and one every application answered the
     * same way.
     *
     * <p>The framework already owned the other half — the zoom is <em>remembered</em>, because the main window
     * is watched with its tree, so Ctrl+= survives a quit the way dragging the window bigger does. Owning both
     * halves is what makes them agree: the range is set in {@code GUI} and the remembered factor is restored in
     * {@code ATTACH} through {@code Gui.zoom}, which clamps — so an application that narrows its range in a
     * later release does not reopen at a zoom its own chords can no longer reach.
     *
     * <p><b>The step is a factor, not an increment</b>, which is {@code Gui.zoomRange}'s own rule and the
     * reason it is validated here: <i>"zoom is perceived as a ratio, so a fixed increment is a huge jump at the
     * bottom of the range and an imperceptible one at the top."</i> A step of 1 is a chord that appears to be
     * broken, and {@code Gui} answers that by clamping to 1.0001 — a chord that moves the UI by a hundredth of
     * a percent, which looks the same. Refusing the value in {@code CONFIG} names it instead, and a startup
     * error beats a runtime one.
     *
     * @param min  the smallest factor a zoom chord can reach
     * @param max  the largest
     * @param step the factor one press multiplies or divides by; must be greater than 1
     */
    public record ZoomRange(float min, float max, float step) {

        /**
         * 0.5 to 3, a step of 1.25 — the numbers all five hand-written call sites chose.
         *
         * <p>Narrower than {@code Gui}'s own 0.25 to 4, deliberately and not by accident of copying: this is
         * the range the applications settled on with a live UI in front of them, and it is the one being taken
         * as the framework's answer. An application that wants {@code Gui}'s wider bounds says so, the same way
         * one that wants the OS frame says so.
         */
        public static final ZoomRange DEFAULT = new ZoomRange(0.5f, 3f, 1.25f);

        public ZoomRange {
            if (!(min > 0f)) {
                throw new IllegalArgumentException("zoom min must be greater than 0: " + min);
            }
            if (!(max >= min)) {
                throw new IllegalArgumentException("zoom max " + max + " is below zoom min " + min);
            }
            if (!(step > 1f)) {
                throw new IllegalArgumentException(
                        "zoom step is a factor, not an increment, so it must be greater than 1: " + step);
            }
        }

        /**
         * Apply this range to {@code gui}, and nothing else.
         *
         * <p><b>The narrow half of {@link Appearance#applyTo}, and it exists because the wide one is wrong for
         * some windows.</b> A second window may legitimately have a look of its own — the text editor's file
         * drawer is deliberately a different hue from its editor, on the grounds that <i>"they are different
         * machines... hue is the cheapest thing a glance resolves"</i>, and the console it opens brings its
         * own palette because <i>"a window that had to be themed by whoever embedded it would look different
         * in every application that used it."</i> A second window that disagreed about <em>how far the zoom
         * goes</em> is not making a point; it is just inconsistent.
         *
         * <p>So this is what every window on a desk should share whatever else it does not, and it is also
         * what a library window reaches for: a class built to run under a host that is not this framework has
         * no {@code Shell} to ask, and {@link #DEFAULT} is still the one place the three numbers are written
         * down.
         */
        public void applyTo(Gui gui) {
            gui.zoomRange(min, max, step);
        }
    }

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
        if (zoom == null) {
            zoom = ZoomRange.DEFAULT;
        }
    }

    /** This look, with everything else defaulted. */
    public static Appearance of(Theme theme) {
        return new Appearance(theme, null, null, null, null, null);
    }

    /** This look, and the smallest window it stays coherent in. */
    public static Appearance of(Theme theme, Length minWidth, Length minHeight) {
        return new Appearance(theme, minWidth, minHeight, null, null, null);
    }

    /** The same, with a size floor. */
    public Appearance minSize(Length width, Length height) {
        return new Appearance(theme, width, height, decorations, instruments, zoom);
    }

    /** The same, with the frame drawn by whoever this says. */
    public Appearance decorations(Decorations decorations) {
        return new Appearance(theme, minWidth, minHeight, decorations, instruments, zoom);
    }

    /**
     * The same, with these framework tools in the title bar.
     *
     * <p>{@code List.of()} for none, which is the answer for a shipped application that does not want a
     * screenshot button in its caption.
     */
    public Appearance instruments(List<WindowInstrument> instruments) {
        return new Appearance(theme, minWidth, minHeight, decorations, instruments, zoom);
    }

    /** The same, with the UI zoom held between these bounds. See {@link ZoomRange} for what is whose. */
    public Appearance zoom(float min, float max, float step) {
        return zoom(new ZoomRange(min, max, step));
    }

    /** The same, with this zoom range. {@code null} for the framework's. */
    public Appearance zoom(ZoomRange zoom) {
        return new Appearance(theme, minWidth, minHeight, decorations, instruments, zoom);
    }

    /**
     * Apply the parts of this appearance that belong to <em>every</em> window to a {@code Gui} the framework
     * did not build.
     *
     * <p><b>Why this is reachable at all</b>, and it is the clipboard's argument again. The framework builds
     * one {@code Gui} and applies this to it; an application with more windows owns the rest, and each of them
     * owns a {@code Gui} from construction — long before any native window exists. So the framework applies
     * what it built and hands the value over for the others:
     *
     * {@snippet :
     * for (Gui window : files.windows()) {
     *     shell.appearance().applyTo(window);
     * }
     * }
     *
     * <p>Without it the second window is a window the application's own look never reached, which is not a
     * hypothetical: it is exactly the defect {@code Modals} had — a dialog that builds its own {@code Gui},
     * gets {@code Theme.DARK} by default, and drew dark whatever the application said. Invisible in an
     * application whose theme is dark, and glaring in one whose theme is not. That one is fixed, and this is
     * the method it was fixed with: {@code Modals.install(app, appearance::applyTo)}.
     *
     * <p><b>The theme and the zoom range; not the minimum size.</b> That line is where it is because
     * {@code Gui.minSize} is <i>"not an OS window minimum"</i> — it is the smallest canvas <em>this tree</em>
     * can be laid out on, so it is a fact about one layout and the main window's floor is the wrong answer for
     * a tool window beside it.
     *
     * <p><b>Only for a window that is meant to look the same</b>, which is the correction the text editor
     * forced on this method after it was written. Its file drawer is deliberately a different hue from its
     * editor — <i>"they are different machines... hue is the cheapest thing a glance resolves"</i> — and the
     * console it opens brings its own palette on purpose. Calling this on either would overwrite a decision
     * with a default. So the two facts are not the same kind after all: how far the zoom goes should be the
     * same in every window on the desk, and the theme is the application's answer for windows that have not
     * got one of their own. A window with its own look calls {@link ZoomRange#applyTo} and keeps its theme.
     */
    public void applyTo(Gui gui) {
        gui.theme(theme);
        zoom.applyTo(gui);
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

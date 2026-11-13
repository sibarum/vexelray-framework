package ${packageName};

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * The two looks, dark and light, as anchors: the application's own, and the person chooses between them in the app
 * menu (Theme, then Light or Dark). The framework remembers the choice and re-themes every window with it.
 *
 * <h2>A palette is a construction, not a list of colours</h2>
 *
 * <p>{@link Palette} takes a handful of anchors and derives the rest: surfaces are an even ladder from the page
 * at a fixed lightness step, and text is the ink blended towards the page at a fixed rate. That is the point --
 * every surface in the application stays in step with every other one for free, and a design change is a
 * number here rather than fifteen hex codes spread across the tree.
 *
 * <p>So when a design has fifteen authored colours, the first job is to <b>measure them in Oklab</b> and find
 * out how many decisions they actually are. Usually far fewer, and the ones that do not fit the construction
 * are the interesting ones -- declare those as roles of your own, at their measured values, and note the gap
 * rather than absorbing it. A theme that silently rounds a designer's colour is a theme nobody can check.
 *
 * <h2>Two looks, each authored</h2>
 *
 * <p>The light look is not the dark one reflected. A mirror of the dark page (L 0.214) lands at 0.79, a grey, where
 * a light page wants 0.975; the accent has to come down to stay readable on white; and a shadow that is depth on a
 * dark page is a smudge on a light one, so its alpha drops by two thirds. Every number below is a decision, and a
 * design replaces both sets. The framework derives neither, on purpose.
 *
 * <h2>Write the role, never the colour</h2>
 *
 * <p>The person can change the look while the application runs. A node that holds a {@link Role} is repainted in the
 * new look, and fades to it; a node that was handed {@code gui.theme().color(role)} keeps the colour it was given,
 * forever. So everything in this application writes roles, and a colour the framework does not name is a role of
 * its own here ({@link #EMPHASIS}), with an answer in each look.
 */
final class Look {

    // ---------------------------------------------------------------- the dark look

    /** The page: the colour behind everything, and what the frame clears to. */
    private static final Oklab DARK_PAGE = Oklab.polar(0.2141, 0.0278, -82.48);

    /** Primary text. Neutral, so it does not tint every label in the application. */
    private static final Oklab DARK_INK = Oklab.polar(0.9352, 0.0054, -73.70);

    /** The one chromatic decision: a hover wash, a selected row, a focus ring. */
    private static final Oklab DARK_ACCENT = Oklab.polar(0.6600, 0.1245, -70.45);

    /** The fill of a filled control, and the border of anything the accent has claimed. */
    private static final Oklab DARK_ACTION = Oklab.polar(0.4801, 0.1041, -70.46);

    /** The accent's chroma taken round to red, so a confirmation dialog belongs to this palette. */
    private static final Oklab DARK_DANGER = Oklab.polar(0.5894, 0.1448, 18.40);

    /** Shadows: near-black, at the page's hue rather than a neutral grey. */
    private static final Oklab DARK_DEPTH = Oklab.polar(0.1236, 0.0130, -86.65);

    /** The dark palette: two steps from the page is a panel, and text(1) is DIM, text(2) is FAINT. */
    static final Palette DARK_PALETTE = new Palette(DARK_PAGE, 0.0271, DARK_INK, 0.202, DARK_ACCENT, DARK_ACTION,
            DARK_DANGER, DARK_DEPTH, 0.60);

    /**
     * Lit surfaces on, letterpress off.
     *
     * <p>The edge light is what gives a dark panel its glint. Letterpress is the opposite call: it buys
     * contrast for white-on-fill labels by spending crispness, which small text can least afford. Both are
     * decisions to make against a real design rather than defaults to leave alone.
     */
    static final Theme DARK = Theme.of(DARK_PALETTE, Shading.ON_DARK, Relief.STANDARD, true, false);

    // ---------------------------------------------------------------- the light look

    /** A near-white page at the dark page's hue, so the two read as one family. */
    private static final Oklab LIGHT_PAGE = Oklab.polar(0.9750, 0.0060, -82.48);

    /** Primary text: a deep slate rather than black, at the ink's own hue. */
    private static final Oklab LIGHT_INK = Oklab.polar(0.2500, 0.0180, -73.70);

    /** The accent, brought down from 0.66 so a link and a focus ring still read on white. */
    private static final Oklab LIGHT_ACCENT = Oklab.polar(0.5400, 0.1450, -70.45);

    /** A filled control's fill: a little deeper than the accent, so its white label has room. */
    private static final Oklab LIGHT_ACTION = Oklab.polar(0.4900, 0.1300, -70.46);

    /** Danger, darker and stronger than the dark look's, for the same reason as the accent. */
    private static final Oklab LIGHT_DANGER = Oklab.polar(0.5200, 0.1700, 18.40);

    /** Shadows, at the page's hue. Lighter than the dark look's: here depth is a hint, not a void. */
    private static final Oklab LIGHT_DEPTH = Oklab.polar(0.2000, 0.0100, -86.65);

    /** The light palette: each surface a step down from the page, and the ink fading as fast. */
    static final Palette LIGHT_PALETTE = new Palette(LIGHT_PAGE, 0.0300, LIGHT_INK, 0.320, LIGHT_ACCENT,
            LIGHT_ACTION, LIGHT_DANGER, LIGHT_DEPTH, 0.18);

    /** No edge light, which on a white panel reads as a smear, and no letterpress. */
    static final Theme LIGHT = Theme.of(LIGHT_PALETTE, Shading.ON_LIGHT, Relief.STANDARD, false, false);

    // ---------------------------------------------------------------- roles of our own

    /** Emphasis on the dark page: a warm gold at the accent's lightness and above. */
    private static final Color DARK_EMPHASIS = Oklab.polar(0.8200, 0.1100, 85.00).toColor();

    /** Emphasis on the light page: the same gold, darkened until it reads on white as text does. */
    private static final Color LIGHT_EMPHASIS = Oklab.polar(0.5300, 0.1100, 75.00).toColor();

    /**
     * Emphasis in Markdown, which the framework does not name: a warm gold, so it reads as a second voice beside the
     * accent's headings and links rather than as another shade of them. The atlas has one weight, so emphasis is a
     * colour rather than a bold face. A role, with an answer in each look, so it follows the person's choice.
     */
    static final Role EMPHASIS = p -> p.equals(LIGHT_PALETTE) ? LIGHT_EMPHASIS : DARK_EMPHASIS;

    private Look() {
    }
}

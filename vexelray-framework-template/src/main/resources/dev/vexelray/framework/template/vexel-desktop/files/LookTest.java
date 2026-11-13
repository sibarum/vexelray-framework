package ${packageName};

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both looks, each held to what a look has to be true of, whichever way its page faces.
 *
 * <p>Worth having from the first day rather than the day a colour goes wrong, because a palette is derived:
 * one anchor moved by a tenth changes every surface and every shade of text at once, and nothing on screen
 * says which change did it. These are the checks that would have caught it.
 *
 * <p>Every check is written as a <b>distance from the page</b>, not as "lighter" or "darker", so one test holds the
 * dark look and the light one alike. Contrast is WCAG's ratio: 4.5 for body text, 3 for what only has to be seen.
 *
 * <p>When this application gets a real design, the useful version of this test is the one that <b>pins the gap
 * between the design and the construction</b> -- assert each authored colour against what the palette derives,
 * with the difference written down. A gap that is measured is a known quantity; a gap that is absorbed is a
 * surprise waiting for a review.
 */
class LookTest {

    static Stream<Theme> looks() {
        return Stream.of(Look.DARK, Look.LIGHT);
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theInkReadsAgainstThePage(Theme look) {
        assertReads(look, Role.INK, 7.0, "primary text");
        assertReads(look, Role.DIM, 4.5, "dim text");
    }

    /** Markdown's colours, the application's own role among them, read as text on the page in both looks. */
    @ParameterizedTest
    @MethodSource("looks")
    void everyTextColourOfOurOwnReadsAgainstThePage(Theme look) {
        assertReads(look, Look.EMPHASIS, 4.5, "Markdown emphasis");
        assertReads(look, Role.ACCENT, 4.5, "the accent, as a heading and a link");
        assertReads(look, Role.FAINT, 3.0, "faint text, a Markdown marker and the empty-state hint");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void surfacesStepAwayFromThePage(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float panel = Math.abs(luminance(look.color(Role.PANEL)) - page);
        float raised = Math.abs(luminance(look.color(Role.RAISED)) - page);
        assertTrue(panel > 0, "a panel should stand off the page");
        assertTrue(raised > panel, "a raised surface should stand further off the page than a panel");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void textFadesInSteps(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float ink = Math.abs(luminance(look.color(Role.INK)) - page);
        float dim = Math.abs(luminance(look.color(Role.DIM)) - page);
        float faint = Math.abs(luminance(look.color(Role.FAINT)) - page);
        assertTrue(ink > dim, "dim text should be quieter than primary text");
        assertTrue(dim > faint, "faint text should be quieter than dim text");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theAccentIsNotTheInk(Theme look) {
        assertNotEquals(look.color(Role.INK), look.color(Role.ACCENT),
                "the accent should be a colour, not the text shade");
    }

    /** The two looks are two: an application role that answered the same in both would not follow a choice. */
    @ParameterizedTest
    @MethodSource("looks")
    void theLooksFaceOppositeWays(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float ink = luminance(look.color(Role.INK));
        assertTrue(look == Look.DARK ? ink > page : ink < page, "the dark look's ink is above its page, the light one's below");
        assertNotEquals(Look.DARK.color(Look.EMPHASIS), Look.LIGHT.color(Look.EMPHASIS),
                "emphasis has an answer in each look");
    }

    private static void assertReads(Theme look, Role role, double least, String what) {
        double ratio = contrast(look.color(role), look.color(Role.PAGE));
        assertTrue(ratio >= least, what + " is " + String.format("%.2f", ratio) + ":1 against the page in the "
                + (look == Look.DARK ? "dark" : "light") + " look; it needs " + least + ":1");
    }

    /** WCAG's contrast ratio between two colours. */
    private static double contrast(Color a, Color b) {
        double la = relative(a);
        double lb = relative(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG's relative luminance, from sRGB. */
    private static double relative(Color c) {
        return 0.2126 * linear(c.r()) + 0.7152 * linear(c.g()) + 0.0722 * linear(c.b());
    }

    private static double linear(float channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /** Rough perceived brightness -- enough to order two shades, which is all the ordering checks need. */
    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }
}

package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.os.Decorations;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The look, as a value — what an application declares in {@code CONFIG} and what applying it means.
 *
 * <p>A {@code Gui} here and no {@code GuiApp}: a tree with no device is what {@link Appearance#applyTo} acts
 * on, and it is the same object an application's second window owns before any native window exists. Which is
 * the whole reason that method is reachable. Nothing in this file needs a GPU.
 */
final class AppearanceTest {

    private static Gui gui() {
        return new Gui(Atchung.create());
    }

    /** No font here, and none needed: nothing in this file lays out text. */
    private static float noText(dev.vexelray.gui.core.model.RetainedNode node,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    /** A shipped application must be able to say "no buttons in my caption". */
    @Test
    void instrumentsAreReducibleToNone() {
        assertTrue(Appearance.of(Theme.DARK).instruments(List.of()).instruments().isEmpty());
    }

    @Test
    void anAppearanceWithNoFloorReportsNone() {
        assertFalse(Appearance.of(Theme.DARK).hasMinSize());
        assertTrue(Appearance.of(Theme.DARK, Length.em(46), Length.em(30)).hasMinSize());
    }

    // ---- the zoom range ---------------------------------------------------------------------------------

    /**
     * Unlike the size floor, there is always a zoom range: five hand-written call sites had already agreed on
     * one, so an application that says nothing gets that rather than {@code Gui}'s wider bounds. Never null,
     * because {@code VexelApplication} applies it unconditionally.
     */
    @Test
    void anApplicationThatSaysNothingAboutZoomGetsTheRangeEverybodyAgreedOn() {
        assertSame(Appearance.ZoomRange.DEFAULT, Appearance.of(Theme.DARK).zoom());
        assertSame(Appearance.ZoomRange.DEFAULT, Appearance.DEFAULT.zoom());
        assertSame(Appearance.ZoomRange.DEFAULT,
                Appearance.of(Theme.DARK).zoom((Appearance.ZoomRange) null).zoom());
        assertEquals(0.5f, Appearance.ZoomRange.DEFAULT.min());
        assertEquals(3f, Appearance.ZoomRange.DEFAULT.max());
        assertEquals(1.25f, Appearance.ZoomRange.DEFAULT.step());
    }

    /** Wider bounds are available to whoever wants them; what is gone is having to know the numbers. */
    @Test
    void anApplicationCanStillAskForTheWiderRange() {
        Appearance wide = Appearance.of(Theme.DARK).zoom(0.25f, 4f, 1.1f);

        assertEquals(0.25f, wide.zoom().min());
        assertEquals(4f, wide.zoom().max());
        assertEquals(1.1f, wide.zoom().step());
    }

    /** The zoom range is one part of an appearance, so setting it keeps the rest. */
    @Test
    void takingTheZoomRangeKeepsEverythingElse() {
        Appearance base = Appearance.of(Theme.LIGHT, Length.em(46), Length.em(30))
                .decorations(Decorations.SYSTEM)
                .instruments(List.of());
        Appearance zoomed = base.zoom(1f, 2f, 1.5f);

        assertSame(base.theme(), zoomed.theme());
        assertSame(base.minWidth(), zoomed.minWidth());
        assertSame(base.minHeight(), zoomed.minHeight());
        assertEquals(base.decorations(), zoomed.decorations());
        assertEquals(base.instruments(), zoomed.instruments());
        assertSame(Appearance.ZoomRange.DEFAULT, base.zoom(), "zoom returns a new record rather than mutating");
    }

    /**
     * A step is a factor, not an increment. {@code Gui.zoomRange} clamps 1 up to 1.0001 — a chord that moves
     * the UI by a hundredth of a percent, which is indistinguishable from a chord that does nothing. Refusing
     * it in {@code CONFIG} names it instead, which is this repo's order: a startup error beats a runtime one.
     */
    @Test
    void aZoomStepOfOneIsRefusedRatherThanClampedIntoAChordThatLooksBroken() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Appearance.ZoomRange(0.5f, 3f, 1f));
        assertTrue(e.getMessage().contains("factor"), e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new Appearance.ZoomRange(0.5f, 3f, 0.8f));
    }

    /** A range whose maximum is below its minimum is a typo, and {@code Gui} would silently widen it back. */
    @Test
    void anInvertedOrZeroedZoomRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Appearance.ZoomRange(3f, 0.5f, 1.25f));
        assertThrows(IllegalArgumentException.class, () -> new Appearance.ZoomRange(0f, 3f, 1.25f));
        assertThrows(IllegalArgumentException.class, () -> new Appearance.ZoomRange(-1f, 3f, 1.25f));
    }

    /** A range that cannot move is legal: it is how an application pins the zoom, and it is not a typo. */
    @Test
    void aPinnedZoomRangeIsAllowed() {
        assertEquals(1f, new Appearance.ZoomRange(1f, 1f, 1.25f).max());
    }

    // ---- applying it to a window the framework did not build --------------------------------------------

    /**
     * The clipboard's argument, for the look: the framework dresses the one {@code Gui} it built, and an
     * application with three windows has two the framework never saw. A window the application's own theme
     * never reached is the defect {@code Modals} has upstream, and this is the call that stops it being every
     * multi-window application's as well.
     */
    @Test
    void applyingAnAppearanceDressesAWindowTheFrameworkDidNotBuild() {
        try (Gui second = gui()) {
            Appearance.of(Theme.LIGHT).zoom(0.5f, 1.5f, 1.25f).applyTo(second);

            assertSame(Theme.LIGHT, second.theme());
            for (int i = 0; i < 20; i++) {
                second.zoomIn();
            }
            assertEquals(1.5f, second.zoom().value(), 0.001f, "the second window got the same bounds");
        }
    }

    /**
     * The narrow half, for a window that has a look of its own.
     *
     * <p>The text editor's file drawer is deliberately a different hue from its editor, so dressing it with
     * the whole appearance would overwrite a decision with a default. What it should still share is how far
     * the zoom goes — a second window that disagreed about that is not making a point.
     */
    @Test
    void aWindowWithItsOwnLookTakesTheRangeAndKeepsItsTheme() {
        try (Gui drawer = gui()) {
            Theme own = Theme.LIGHT;
            drawer.theme(own);
            Appearance.of(Theme.DARK).zoom(0.5f, 1.5f, 1.25f).zoom().applyTo(drawer);

            assertSame(own, drawer.theme(), "the window's own theme survives");
            for (int i = 0; i < 20; i++) {
                drawer.zoomIn();
            }
            assertEquals(1.5f, drawer.zoom().value(), 0.001f, "and it still agrees about the bounds");
        }
    }

    /**
     * The minimum size is deliberately not part of it. {@code Gui.minSize} is <i>"not an OS window
     * minimum"</i> — it is the smallest canvas one tree can be laid out on — so the main window's floor is the
     * wrong answer for a tool window beside it, and applying it would be a silent layout crop.
     */
    @Test
    void applyingAnAppearanceLeavesTheSecondWindowsOwnLayoutFloorAlone() {
        try (Gui second = gui()) {
            Appearance.of(Theme.LIGHT, Length.em(46), Length.em(30)).applyTo(second);

            second.frame(300f, 150f, AppearanceTest::noText);
            assertEquals(300f, second.root().layout().rect().w(), 0.5f,
                    "a floor of 46em would have laid this out on 736pt and cropped it");
            assertEquals(150f, second.root().layout().rect().h(), 0.5f);
        }
    }
}

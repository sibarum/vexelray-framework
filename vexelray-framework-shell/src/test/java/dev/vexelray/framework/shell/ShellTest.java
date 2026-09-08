package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phase gate — the backstop for wiring the processor has not checked.
 *
 * <p>No GPU needed: nothing here constructs a {@code Gui} or a {@code GuiApp}, only asks what happens when
 * somebody reaches for one too early.
 */
final class ShellTest {

    private static Shell shell() {
        return new Shell(Launch.parse(new String[0], "demo", Set.of()),
                new AppInfo("demo", "Demo", 800, 600));
    }

    @Test
    void aShellStartsInConfig() {
        assertEquals(Phase.CONFIG, shell().phase());
    }

    @Test
    void theWindowDoesNotExistBeforeItsPhaseAndSaysSo() {
        Shell shell = shell();
        shell.phase(Phase.MODEL);

        IllegalStateException e = assertThrows(IllegalStateException.class, shell::app);
        assertTrue(e.getMessage().contains("WINDOW"), e.getMessage());
        assertTrue(e.getMessage().contains("MODEL"), e.getMessage());
    }

    @Test
    void theGuiDoesNotExistBeforeItsPhase() {
        assertThrows(IllegalStateException.class, shell()::gui);
    }

    @Test
    void settingsAreNotThereUntilConfigHasRun() {
        assertThrows(IllegalStateException.class, shell()::settings);
    }

    /**
     * The ordering constraint that was a comment in four applications: the look is applied before the first
     * widget, so there is no way to hand it over late.
     */
    @Test
    void appearanceCannotBeRegisteredAfterConfig() {
        Shell shell = shell();
        shell.phase(Phase.GUI);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> shell.appearance(Appearance.of(Theme.LIGHT)));
        assertTrue(e.getMessage().contains("CONFIG"), e.getMessage());
    }

    @Test
    void appearanceIsTheFrameworkDefaultUntilSomethingSaysOtherwise() {
        assertSame(Appearance.DEFAULT, shell().appearance());
        assertSame(Theme.DARK, shell().appearance().theme());
    }

    @Test
    void appearanceRegisteredInConfigSticks() {
        Shell shell = shell();
        Appearance light = Appearance.of(Theme.LIGHT);
        shell.appearance(light);
        assertSame(light, shell.appearance());
    }

    @Test
    void aNullAppearanceFallsBackRatherThanNullingTheLook() {
        Shell shell = shell();
        shell.appearance(null);
        assertSame(Appearance.DEFAULT, shell.appearance());
    }

    /** A wake registered before there is a loop would be silently never connected, so it is refused. */
    @Test
    void wakesAreRefusedBeforeAttach() {
        Shell shell = shell();
        shell.phase(Phase.WINDOW);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> shell.wake(wake -> {
        }));
        assertTrue(e.getMessage().contains("ATTACH"), e.getMessage());
    }

    @Test
    void deadlinesAndHooksAreAvailableThroughout() {
        Shell shell = shell();
        shell.deadline(() -> 42L);
        shell.hooks().add(dev.vexelray.framework.api.FrameStage.APP, () -> {
        });

        assertEquals(1, shell.hooks().size());
        assertEquals(42L, shell.pacing().seal().nanosUntilNextFrame());
    }

    @Test
    void theLaunchAndTheAppInfoAreAlwaysThere() {
        Shell shell = shell();
        assertEquals("demo", shell.info().name());
        assertEquals(dev.vexelray.framework.api.RunMode.WINDOWED, shell.launch().mode());
    }

    /**
     * The framework builds the bar; the application supplies the look. So an appearance says nothing about how
     * the chrome draws — it says the theme, and the chrome reads the same one.
     */
    @Test
    void anApplicationDrawingItsOwnFrameGetsTheStandardInstruments() {
        Appearance appearance = Appearance.of(Theme.LIGHT);
        assertTrue(appearance.drawsOwnFrame());
        assertEquals(dev.vexelray.gui.core.WindowInstrument.standard().size(),
                appearance.instruments().size());
    }

    /** A shipped application must be able to say "no buttons in my caption". */
    @Test
    void instrumentsAreReducibleToNone() {
        assertTrue(Appearance.of(Theme.DARK).instruments(java.util.List.of()).instruments().isEmpty());
    }

    @Test
    void askingForSystemDecorationsMeansThereIsNoFrameworkBar() {
        Appearance os = Appearance.of(Theme.DARK).decorations(dev.vexelray.os.Decorations.SYSTEM);
        assertFalse(os.drawsOwnFrame());

        Shell shell = shell();
        shell.appearance(os);
        shell.phase(dev.vexelray.framework.core.Phase.GUI);

        IllegalStateException e = assertThrows(IllegalStateException.class, shell::titleBar);
        assertTrue(e.getMessage().contains("SYSTEM decorations"), e.getMessage());
    }

    @Test
    void theBarDoesNotExistBeforeTheGuiPhase() {
        assertThrows(IllegalStateException.class, shell()::titleBar);
    }

    /** The application's theme is the only theme. Nothing here substitutes a framework one. */
    @Test
    void theThemeIsWhateverTheApplicationSaid() {
        Theme mine = Theme.of(Theme.LIGHT.palette(), Theme.LIGHT.shading(), Theme.LIGHT.relief(), true, false);
        Shell shell = shell();
        shell.appearance(Appearance.of(mine));
        assertSame(mine, shell.appearance().theme());
    }

    @Test
    void anAppearanceWithNoFloorReportsNone() {
        assertFalse(Appearance.of(Theme.DARK).hasMinSize());
        assertTrue(Appearance.of(Theme.DARK,
                dev.vexelray.gui.core.layout.Length.em(46),
                dev.vexelray.gui.core.layout.Length.em(30)).hasMinSize());
    }

}

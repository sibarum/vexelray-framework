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

    @Test
    void anAppearanceWithNoFloorReportsNone() {
        assertFalse(Appearance.of(Theme.DARK).hasMinSize());
        assertTrue(Appearance.of(Theme.DARK,
                dev.vexelray.gui.core.layout.Length.em(46),
                dev.vexelray.gui.core.layout.Length.em(30)).hasMinSize());
    }

    /** The clear colour comes off the theme, so a capture cannot disagree with the page it photographs. */
    @Test
    void thePageColourIsDerivedFromTheTheme() {
        assertEquals(Theme.LIGHT.color(dev.vexelray.gui.core.style.Role.PAGE),
                Appearance.of(Theme.LIGHT).page());
        assertEquals(Theme.DARK.color(dev.vexelray.gui.core.style.Role.PAGE),
                Appearance.of(Theme.DARK).page());
    }
}

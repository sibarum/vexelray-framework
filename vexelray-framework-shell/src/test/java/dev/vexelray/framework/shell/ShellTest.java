package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    /**
     * The three things the port of the text editor found missing, all of them ATTACH-phase: without them an
     * application with a second window has no way to reach the clipboard, an application with unsaved work has
     * no way to be asked before it quits, and neither had anywhere to say so.
     */
    @Test
    void theClipboardIsNotThereBeforeAttach() {
        Shell shell = shell();
        shell.phase(Phase.WINDOW);

        IllegalStateException e = assertThrows(IllegalStateException.class, shell::clipboard);
        assertTrue(e.getMessage().contains("ATTACH"), e.getMessage());
        assertTrue(e.getMessage().contains("WINDOW"), e.getMessage());
    }

    // --- the defaults an application replaces by handing one back ------------------------------------------

    /** An input backend that records what the framework does with it, and opens nothing. */
    private static final class RecordedInput implements InputBackend {
        boolean closed;

        public boolean present() {
            return true;
        }

        public void attach(long windowHandle) {
        }

        public void bridge(dev.vexelray.gui.core.Gui gui, PointerLock lock) {
        }

        public void pump() {
        }

        public void close() {
            closed = true;
        }
    }

    /** A clipboard confined to the application: installs nothing, so the GUI's in-memory default stays. */
    private static final class ConfinedClipboard implements ClipboardBackend {
        boolean closed;

        public boolean present() {
            return true;
        }

        public ClipboardBackend installOn(dev.vexelray.gui.core.Gui gui) {
            return this;
        }

        public void close() {
            closed = true;
        }
    }

    @Test
    void anInputBackendHandedBackIsTheOneTheFrameworkUsesAndItOpensNoneOfItsOwn() {
        dev.vexelray.diag.Diagnostics.reset();
        Shell shell = shell();
        RecordedInput mine = new RecordedInput();
        shell.input(mine);
        shell.phase(Phase.WINDOW);

        assertSame(mine, shell.openInput());
        // The framework's own would have reported its absence here, since no platform module is on this
        // classpath -- so an empty record is what "it never opened one" looks like from outside.
        assertEquals(List.of(), dev.vexelray.diag.Diagnostics.recorded());
        shell.disposer().close();
        assertTrue(mine.closed, "taken over: the shell closes what it was handed");
    }

    @Test
    void withNothingHandedBackTheFrameworkOpensItsOwn() {
        dev.vexelray.diag.Diagnostics.reset();
        Shell shell = shell();
        shell.input(null);
        shell.phase(Phase.WINDOW);

        assertFalse(shell.openInput().present(), "the framework's, on a classpath with no platform module");
        List<String> recorded = dev.vexelray.diag.Diagnostics.recorded();
        assertEquals(1, recorded.size(), recorded::toString);
        dev.vexelray.diag.Diagnostics.reset();
    }

    @Test
    void anInputBackendIsRefusedOnceTheFrameworkHasReachedForItsOwn() {
        Shell shell = shell();
        shell.phase(Phase.WINDOW);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> shell.input(new RecordedInput()));
        assertTrue(e.getMessage().contains("by TREE"), e.getMessage());
        assertTrue(e.getMessage().contains("this is WINDOW"), e.getMessage());
    }

    @Test
    void aSecondInputBackendIsRefusedRatherThanLeftOpen() {
        Shell shell = shell();
        shell.input(new RecordedInput());
        assertThrows(IllegalStateException.class, () -> shell.input(new RecordedInput()));
    }

    @Test
    void aClipboardHandedBackIsTheOneInstalledAndIsNotReadableBeforeAttach() {
        Shell shell = shell();
        ConfinedClipboard mine = new ConfinedClipboard();
        shell.phase(Phase.WINDOW);
        shell.clipboard(mine);

        assertThrows(IllegalStateException.class, shell::clipboard,
                "handed back is not installed: the accessor is ATTACH's whether or not the value exists yet");
        shell.phase(Phase.ATTACH);
        assertSame(mine, shell.openClipboard());
        assertSame(mine, shell.clipboard());
        assertThrows(IllegalStateException.class, () -> shell.clipboard(new ConfinedClipboard()),
                "ATTACH is too late: the framework has installed one");
        shell.disposer().close();
        assertTrue(mine.closed);
    }

    @Test
    void theDialogsAreNotThereBeforeAttach() {
        assertThrows(IllegalStateException.class, shell()::dialogs);
    }

    /**
     * A gate registered before there is a window would be registered against nothing, on the same terms as a
     * wake registered before there is a loop — so it is refused rather than silently dropped.
     */
    @Test
    void aCloseGateIsRefusedBeforeAttach() {
        Shell shell = shell();
        shell.phase(Phase.WINDOW);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> shell.onClose(request -> {
                }));
        assertTrue(e.getMessage().contains("ATTACH"), e.getMessage());
    }

}

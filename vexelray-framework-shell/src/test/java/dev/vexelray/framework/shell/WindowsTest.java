package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.os.Decorations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A second window, declared by name: its tree is the framework's to build, and its window the framework's to claim.
 *
 * <p><b>No GPU.</b> Everything asserted here is true by the end of {@code TREE}, which is the point of declaring a
 * window with the trees: what the window will show is built, themed and readable before there is a device, and the
 * claim at {@code ATTACH} adds only what needs one. The claim itself runs in the acceptance witness, which opens the
 * windows for real.
 */
final class WindowsTest {

    private static final AppInfo DEMO = new AppInfo("windowstest", "Windows", 800, 600);

    private Shell shell;

    @BeforeEach
    void ownHome(@TempDir Path home) {
        System.setProperty(DEMO.name() + ".home", home.toString());
    }

    @AfterEach
    void close() {
        if (shell != null) {
            shell.disposer().close();
        }
    }

    /** A wiring that hands back {@code look} in CONFIG and runs {@code tree} in TREE. */
    private static Wiring wiring(Appearance look, Consumer<Shell> tree) {
        return new Wiring() {
            @Override
            public AppInfo info() {
                return DEMO;
            }

            @Override
            public void config(Shell shell) {
                shell.appearance(look);
            }

            @Override
            public void tree(Shell shell) {
                tree.accept(shell);
            }
        };
    }

    private Window declare(Appearance look, NamedWindow window) {
        Window[] out = new Window[1];
        shell = VexelApplication.tree(wiring(look, s -> out[0] = s.windows().named(window)), new String[0]);
        return out[0];
    }

    @Test
    void aNamedWindowsTreeIsOnABusOfItsOwnAndTheApplicationsLanes() {
        Window w = declare(Appearance.DEFAULT, NamedWindow.of("tool", "Tool", 300, 200));

        // Two trees on one bus each receive the other's mutations, and the main window drew the calculator's
        // settings for it (FN-2). The lanes are shared, so a window is not a set of threads.
        assertNotSame(shell.bus(), w.gui().bus(), "a second window's tree is on the application's bus");
        // Gui wraps the handler lane it is given, so the offload lane is the one that can be compared.
        assertSame(shell.lanes().offload(), w.gui().offload(), "a second window has an offload lane of its own");
    }

    @Test
    void itWearsTheApplicationsLookUnlessItWasDeclaredWithItsOwn() {
        Appearance light = Appearance.of(Theme.LIGHT).zoom(0.75f, 2f, 1.1f);
        Window plain = declare(light, NamedWindow.of("plain", "Plain", 300, 200));
        assertSame(Theme.LIGHT, plain.gui().theme(), "a second window did not get the application's look");
        close();

        Window own = declare(light, NamedWindow.of("own", "Own", 300, 200).theme(Theme.DARK));
        assertSame(Theme.DARK, own.gui().theme(), "a window declared with a look of its own did not keep it");
    }

    @Test
    void itHasAFrameworkTitleBarUnlessTheApplicationAskedForTheOsFrame() {
        Window drawn = declare(Appearance.DEFAULT, NamedWindow.of("drawn", "Drawn", 300, 200));
        assertNotSame(shell.titleBar(), drawn.titleBar(), "a second window was handed the main window's bar");
        close();

        Window system = declare(Appearance.DEFAULT.decorations(Decorations.SYSTEM),
                NamedWindow.of("system", "System", 300, 200));
        IllegalStateException e = assertThrows(IllegalStateException.class, system::titleBar);
        assertTrue(e.getMessage().contains("SYSTEM"), e.getMessage());
    }

    @Test
    void commandsBeforeTheWindowCanExistAreHeldNotLost() {
        Window w = declare(Appearance.DEFAULT, NamedWindow.of("early", "Early", 300, 200));

        // From any thread, and before ATTACH there is nothing to post to; none of these may throw.
        w.show().hide().toggle();
        assertFalse(w.isOpen(), "a window reported open before there was a device to open it on");
    }

    @Test
    void theMainWindowIsAWindowOverTheFrameworksOwnTreeAndBar() {
        shell = VexelApplication.tree(wiring(Appearance.DEFAULT, s -> { }), new String[0]);
        Window main = shell.windows().main();

        assertEquals("main", main.key());
        assertSame(shell.gui(), main.gui(), "the main window's handle is not over the framework's tree");
        assertSame(shell.titleBar(), main.titleBar(), "the main window's handle has a bar of its own");
        // Before there is a device: held, not thrown, and nothing reported open. A close here would quit at once
        // once claimed, which is right for an application that decided to quit while it was being built.
        main.hide().show();
        assertFalse(main.isOpen(), "the main window reported open before there was a device");
        assertTrue(shell.windows().views().isEmpty(), "views before ATTACH should be empty");
    }

    @Test
    void aNameIsDeclaredOnce() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> VexelApplication.tree(
                wiring(Appearance.DEFAULT, s -> {
                    s.windows().named("twice", "One", 300, 200);
                    s.windows().named("twice", "Two", 300, 200);
                }), new String[0]));
        assertTrue(e.getMessage().contains("twice"), e.getMessage());
    }

    @Test
    void theMainWindowsNameIsTaken() {
        assertThrows(IllegalArgumentException.class, () -> VexelApplication.tree(
                wiring(Appearance.DEFAULT, s -> s.windows().named("main", "Main", 300, 200)), new String[0]));
    }

    @Test
    void aWindowIsDeclaredByTreeBecauseItIsClaimedAtAttach() {
        Shell bare = new Shell(Launch.parse(new String[0], DEMO.name(), Set.of()), DEMO);
        Windows windows = new Windows(bare, null);
        bare.phase(Phase.WINDOW);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> windows.named("late", "Late", 300, 200));
        assertTrue(e.getMessage().contains("ATTACH") && e.getMessage().contains("WINDOW"), e.getMessage());
    }

    @Test
    void theWindowsDoNotExistBeforeTheGuiPhase() {
        Shell bare = new Shell(Launch.parse(new String[0], DEMO.name(), Set.of()), DEMO);
        IllegalStateException e = assertThrows(IllegalStateException.class, bare::windows);
        assertTrue(e.getMessage().contains("GUI"), e.getMessage());
    }

    @Test
    void aDeclarationNeedsANameAndASize() {
        assertThrows(IllegalArgumentException.class, () -> NamedWindow.of(" ", "Blank", 300, 200));
        assertThrows(IllegalArgumentException.class, () -> NamedWindow.of("flat", "Flat", 300, 0));
        assertEquals("", NamedWindow.of("untitled", null, 300, 200).title());
    }
}

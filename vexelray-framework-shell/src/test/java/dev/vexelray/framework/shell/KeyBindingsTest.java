package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.WindowOpened;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.gui.widget.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key-bindings window is one of the framework's own windows, declared through {@link Windows}: so it follows a
 * choice of look, wears the app menu (asserted by the acceptance run, which has bars), opens by its key, and its
 * Cancel closes it. No GPU: all of it is true by the end of {@code TREE}.
 */
final class KeyBindingsTest {

    private static final AppInfo DEMO = new AppInfo("keybindingstest", "Keys", 800, 600);
    private static final Theme LIGHT = Theme.of(Palette.LIGHT, Shading.ON_LIGHT, Relief.STANDARD, false, false);
    private static final Theme DARK = Theme.of(Palette.DARK, Shading.ON_DARK, Relief.STANDARD, true, false);

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

    private void run(Appearance look, Consumer<Shell> tree) {
        shell = VexelApplication.tree(new Wiring() {
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
        }, new String[0]);
    }

    /** One action, then the window declared by the framework's own code, kept so the test can reach its editor. */
    private KeyBindings withAnAction(Appearance look) {
        KeyBindings[] out = new KeyBindings[1];
        run(look, s -> {
            s.keymap().action("file.save", "Save", Shortcut.of(Key.S, Modifier.CONTROL));
            // What the framework does after TREE, done here so the test holds it; the framework's own call then finds
            // the window declared and takes it up, as it would an application's.
            out[0] = KeyBindings.declare(s);
        });
        return out[0];
    }

    private Window declared(String key) {
        for (Window w : shell.windows().declared()) {
            if (w.key().equals(key)) {
                return w;
            }
        }
        return null;
    }

    @Test
    void nothingToRebindDeclaresNoWindow() {
        run(Appearance.DEFAULT, s -> { });
        assertEquals(null, declared(KeyBindings.KEY));
    }

    @Test
    void anActionDeclaresTheWindowOnceAsASatellite() {
        KeyBindings keys = withAnAction(Appearance.DEFAULT);
        assertNotNull(keys.editor());
        assertSame(keys.window(), declared(KeyBindings.KEY));
        assertTrue(keys.window().satellite(), "it belongs to the main window, as About does");
        assertEquals(1, shell.windows().declared().stream().filter(w -> w.key().equals(KeyBindings.KEY)).count());
    }

    /** The point of declaring it through Windows: a choice of look made while it is open reaches it. */
    @Test
    void itFollowsAChoiceOfLook() {
        KeyBindings keys = withAnAction(Appearance.DEFAULT.themes(LIGHT, DARK));
        assertSame(DARK, keys.window().gui().theme());

        shell.themes().choose(true);

        assertSame(LIGHT, keys.window().gui().theme());
    }

    @Test
    void cancelPutsTheBindingsBackAndClosesTheWindow() {
        KeyBindings keys = withAnAction(Appearance.DEFAULT);
        Window window = keys.window();
        window.show();
        assertTrue(window.shown());
        var save = shell.keymap().actions().getFirst();
        // The visit starts when the window opens: the checkpoint is heard in the main tree's frame.
        shell.bus().publish(WindowOpened.TOPIC, new WindowOpened(KeyBindings.KEY));
        frame(shell.gui());
        shell.keymap().bind(save, List.of(Shortcut.of(Key.K, Modifier.CONTROL)));

        shell.bus().publish(Button.PRESSED, new Button.Pressed(keys.editor().cancelAddress()));
        frame(window.gui());

        assertEquals(List.of(Shortcut.of(Key.S, Modifier.CONTROL)), save.chords(), "Cancel put Save back");
        assertFalse(window.shown(), "and closed the window");
    }

    private static void frame(dev.vexelray.gui.core.Gui gui) {
        Seams.of(gui).frame(800f, 600f, TextFaces.standard());
    }
}

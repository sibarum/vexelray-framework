package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.Modifiers;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.text.TextFaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The modifiers held in a window, as the application hears them (Vexplore FN-9): said on each change of the set, for
 * the window it was held in and no other, and said released when the window loses focus. Keys go in as ottermate's
 * {@code keydown} sends them, onto the tree's input topic.
 */
final class ModifiersTest {

    private static final AppInfo DEMO = new AppInfo("modifierstest", "Modifiers", 800, 600);

    private Shell shell;
    private Window tool;
    private final BlockingQueue<Modifiers> heard = new LinkedBlockingQueue<>();

    @BeforeEach
    void open(@TempDir Path home) {
        System.setProperty(DEMO.name() + ".home", home.toString());
        Window[] out = new Window[1];
        shell = VexelApplication.tree(new Wiring() {
            @Override
            public AppInfo info() {
                return DEMO;
            }

            @Override
            public void tree(Shell shell) {
                out[0] = shell.windows().named(NamedWindow.of("tool", "Tool", 300, 200));
            }
        }, new String[0]);
        tool = out[0];
        shell.bus().subscribe(Modifiers.TOPIC, heard::add);
    }

    @AfterEach
    void close() {
        shell.disposer().close();
    }

    private static void send(Gui gui, InputEvent event) {
        gui.bus().publish(gui.input(), event);
        Seams.of(gui).frame(300, 200, TextFaces.standard());
    }

    private Modifiers next() throws InterruptedException {
        return heard.poll(5, TimeUnit.SECONDS);
    }

    @Test
    void aKeyHeldInAWindowIsSaidForThatWindowAndLettingGoSaysNone() throws Exception {
        Gui main = shell.windows().main().gui();
        send(main, new InputEvent.KeyPressed(Key.LEFT_SHIFT, 0));
        assertEquals(new Modifiers(CloseRequested.MAIN, true, false, false, false), next());
        send(main, new InputEvent.KeyPressed(Key.LEFT_CONTROL, 0));
        assertEquals(new Modifiers(CloseRequested.MAIN, true, true, false, false), next(), "the set, not the key");
        send(main, new InputEvent.KeyReleased(Key.LEFT_SHIFT, 0));
        send(main, new InputEvent.KeyReleased(Key.LEFT_CONTROL, 0));
        assertEquals(new Modifiers(CloseRequested.MAIN, false, true, false, false), next());
        Modifiers last = next();
        assertTrue(last.none() && last.main(), "all let go: " + last);
        assertNull(heard.poll(100, TimeUnit.MILLISECONDS), "nothing more: said on change only");
    }

    @Test
    void aWindowLosingFocusSaysNothingIsHeld() throws Exception {
        Gui gui = tool.gui();
        send(gui, new InputEvent.FocusChanged(true, 0));
        send(gui, new InputEvent.KeyPressed(Key.LEFT_ALT, 0));
        assertEquals(new Modifiers("tool", false, false, true, false), next(), "said for the window it is held in");
        send(gui, new InputEvent.FocusChanged(false, 0));
        assertEquals(Modifiers.released("tool"), next(), "a key let go over another window is never seen");
    }
}

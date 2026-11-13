package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.WindowOpened;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.keymap.KeymapEditor;
import dev.vexelray.gui.keymap.KeymapWindow;
import dev.vexelray.gui.widget.Button;

/**
 * <b>The key-bindings window</b>, which the app menu's <i>Key bindings…</i> opens: vexelray-gui's {@link KeymapEditor}
 * in a window the framework declares, as it declares {@link About}.
 *
 * <p><b>Why the framework's own window and not {@link KeymapWindow}.</b> That one builds its own tree and bar and
 * registers through {@code GuiApp.window}, so the framework held neither. Its bar had no mark and so no app menu; its
 * tree copied the main one's theme when it opened and missed a light/dark choice made while it was open; and
 * {@code WindowCommand.show("keymap")} found no window by that key (MainFrame's FN-13). Declared through
 * {@link Windows}, it is one of the application's windows like any other: the mark and the same app menu as every bar,
 * the look and every choice of it, a remembered placement, and a key a command can name.
 *
 * <p><b>Only when there is something to rebind</b>, as before: declared at the end of {@code TREE}, by which every
 * action is declared, and only with the framework's title bar, which is where it is opened from. An application that
 * declared a {@value #KEY} window of its own keeps it, and the menu line opens that one.
 */
final class KeyBindings {

    /** The window's name, as {@link KeymapWindow} registered it: in the settings file and on the automation socket. */
    static final String KEY = KeymapWindow.KEY;

    private static final String TITLE = "Key bindings";

    private final Window window;
    /** The editor in it; null when the window is the application's own. */
    private final KeymapEditor editor;

    private KeyBindings(Window window, KeymapEditor editor) {
        this.window = window;
        this.editor = editor;
    }

    /** The window the menu line opens. */
    Window window() {
        return window;
    }

    /** The editor in the window; null when the application declared the window itself. */
    KeymapEditor editor() {
        return editor;
    }

    /**
     * Declare the window and build the editor into it, or take up the application's own {@value #KEY}; null with no
     * framework title bar or nothing to rebind. At the end of {@code TREE}, before {@link About}, which comes last.
     */
    static KeyBindings declare(Shell shell) {
        Keymap keymap = shell.keymap();
        if (!shell.appearance().drawsOwnFrame() || keymap.actions().isEmpty()) {
            return null;
        }
        Windows windows = shell.windows();
        for (Window w : windows.declared()) {
            if (w.key().equals(KEY)) {
                return new KeyBindings(w, null);
            }
        }
        Window window = windows.named(NamedWindow.of(KEY, TITLE, 680, 520).satellite());
        Gui g = window.gui();
        KeymapEditor editor = new KeymapEditor(g, keymap);
        // The bar is a child of the root and the padding is the editor's own, below it: a bar inset from the window
        // edge is a bar with a gap the window manager still treats as caption.
        Node body = g.column().width(Length.FILL).height(Length.FILL).children(editor.node());
        g.root().background(Role.PAGE).children(window.titleBar().node(), body);
        // Cancel puts the bindings back (the editor hears its own button) and closes the window, by its close route.
        String cancel = editor.cancelAddress();
        g.mailbox(Button.PRESSED, pressed -> {
            if (cancel.equals(pressed.address())) {
                window.close();
            }
        }, 16);
        // Each opening is a visit: Cancel undoes what was changed since this window opened, not since the first time.
        shell.gui().mailbox(WindowOpened.TOPIC, opened -> {
            if (opened.window().equals(KEY)) {
                editor.checkpoint();
            }
        }, 16);
        return new KeyBindings(window, editor);
    }

    /**
     * The app menu's <i>Key bindings…</i> line, opening the window or raising it: {@code instrument-keymap}, with the
     * icon set's keyboard, as {@link KeymapWindow#instrument} made it, so a script finds it by the same name.
     */
    WindowInstrument instrument() {
        return WindowInstrument.keymap("Key bindings…", controls -> window.show()).icon(Icon.KEYBOARD);
    }
}

package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Icon;
import dev.vexelray.os.WindowConfig;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>One of this application's windows, by name</b> — a settings window, a viewer, a tool window — with its tree
 * built by the framework and its window opened on request. Declared through {@link Windows#named}.
 *
 * <p><b>Every command is safe from any thread</b>, which is why a recipe holding one is not main-thread. Each only
 * asks: {@link #show} posts to the frame loop, and the window is created there, at the top of the next frame. That
 * was already true of the {@code AppWindow} underneath; what was not true is that a recipe could hold one without
 * also taking the {@code GuiApp} to claim it, which made the recipe main-thread for the sake of a handle that is not
 * (calculator FN-1). The claim is the framework's now, so the colour is the handle's own.
 *
 * <p><b>Usable before the window can exist.</b> The handle is handed out with the trees, two phases before there is a
 * device, and the window is claimed at {@code ATTACH}. A {@link #show} before then is remembered and carried out
 * when the window is claimed, so a tree that opens its window on its first frame does not have to know whether that
 * is possible yet.
 *
 * <p><b>The tree outlives the window</b>, as {@code AppWindow}'s does: closing destroys the window and keeps the
 * {@link #gui()}, and showing again re-creates a window around the same tree, so whatever was in it is still there.
 * The framework closes the tree at shutdown.
 *
 * <p><b>What the framework does for it</b>, and what used to be five steps at every second window: its own
 * {@code Gui}, on a bus of its own (two trees on one bus each receive the other's mutations, so the main window drew
 * the calculator's settings) and on the application's lanes; the application's look, or the one it was declared
 * with; a title bar wearing the application's window mark, with the controls and the instruments of its own window;
 * its placement and zoom remembered under its name, and forgotten when it closes; and the application's clipboard.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Window {

    private final NamedWindow declared;
    private final Gui gui;
    private final TitleBar bar;
    private final List<Runnable> opened = new CopyOnWriteArrayList<>();
    private final List<Runnable> closed = new CopyOnWriteArrayList<>();

    /** The claimed window, or null until {@code ATTACH}. Guarded by {@code this}, with {@link #shown}. */
    private AppWindow claimed;
    /** Whether the window should be on screen once it is claimed: the commands given before it could be. */
    private boolean shown;

    Window(NamedWindow declared, Gui gui, TitleBar bar) {
        this.declared = declared;
        this.gui = gui;
        this.bar = bar;
    }

    /** The name this window is known by: in the settings file, and on the automation socket. */
    public String key() {
        return declared.key();
    }

    /** The tree shown in this window, live whether or not the window is open. Build into its {@code root()}. */
    public Gui gui() {
        return gui;
    }

    /**
     * This window's title bar, built by the framework — place its {@link TitleBar#node()} in the tree, as the main
     * window's is placed. Absent when the application asked for {@link dev.vexelray.os.Decorations#SYSTEM}, and
     * asking then is an error rather than a null, as {@link Shell#titleBar()} is.
     */
    public TitleBar titleBar() {
        if (bar == null) {
            throw new IllegalStateException("no framework title bar on window '" + key()
                    + "': this application asked for SYSTEM decorations");
        }
        return bar;
    }

    /**
     * Put this window in front of the user: open it if it is closed, unhide it if it is hidden, raise it and give it
     * the keyboard. Asking for a window that is open raises the one there is rather than opening a second.
     */
    public Window show() {
        AppWindow w;
        synchronized (this) {
            shown = true;
            w = claimed;
        }
        if (w != null) {
            w.show();
        }
        return this;
    }

    /** Take this window off the screen, keeping it and everything in it for the next {@link #show}. */
    public Window hide() {
        AppWindow w;
        synchronized (this) {
            shown = false;
            w = claimed;
        }
        if (w != null) {
            w.hide();
        }
        return this;
    }

    /** {@link #show} if this window is not in front of the user, {@link #hide} if it is. */
    public Window toggle() {
        AppWindow w;
        synchronized (this) {
            w = claimed;
            if (w == null) {
                shown = !shown;
            }
        }
        if (w != null) {
            w.toggle();
        }
        return this;
    }

    /**
     * Close this window by the route its own close button takes. The window goes and the tree stays, for a later
     * {@link #show}. What a button inside the window does when it means "done".
     */
    public Window close() {
        AppWindow w;
        synchronized (this) {
            shown = false;
            w = claimed;
        }
        if (w != null) {
            w.close();
        }
        return this;
    }

    /** Whether the window exists, on screen or hidden. False before {@code ATTACH}. */
    public boolean isOpen() {
        AppWindow w;
        synchronized (this) {
            w = claimed;
        }
        return w != null && w.open();
    }

    /**
     * Run {@code listener} each time the window is created — placed where it was left, its zoom restored — on the
     * main thread. For a part that does work only while its window is up, such as a viewer reading full-size images.
     */
    public Window onOpened(Runnable listener) {
        opened.add(listener);
        return this;
    }

    /** Run {@code listener} each time the window is destroyed, on the main thread. The tree is still there. */
    public Window onClosed(Runnable listener) {
        closed.add(listener);
        return this;
    }

    /**
     * Claim the window, once, at {@code ATTACH}: everything a window needs that only exists once there is a device.
     * Main thread. {@code mark} is the application's window mark, or null for none.
     */
    void claim(GuiApp app, WindowMemory memory, Appearance appearance, Icon mark) {
        String key = key();
        WindowConfig config = memory.config(key, declared.title(), declared.width(), declared.height())
                .decorations(appearance.decorations());
        if (mark != null) {
            config = config.icon(mark);
        }
        WindowSpec spec = WindowSpec.of(config, gui).standing(declared.standing());
        if (bar != null) {
            // Its own window's controls while it is open, and none once it closes; the instruments with them, so a
            // screenshot from this bar photographs this window.
            spec = bar.commands(spec);
            bar.instruments(appearance.instruments()).icon(app, mark);
        }
        // Remembered first, so a listener sees the window where it was left; and forgotten on close, which is what
        // a watch from onCreated never was (calculator FN-4).
        WindowSpec remembered = memory.remember(key, spec, declared.width(), declared.height())
                .onCreated(w -> run(opened))
                .onClosed(() -> run(closed));
        AppWindow w = app.window(key, () -> remembered);
        boolean show;
        synchronized (this) {
            claimed = w;
            show = shown;
        }
        if (show) {
            w.show();
        }
    }

    private static void run(List<Runnable> listeners) {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}

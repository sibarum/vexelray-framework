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
 * <b>One of this application's windows</b> — the main one, or a settings window, a viewer, a tool window by name —
 * with its tree built by the framework and its window commanded from anywhere. The main window is
 * {@link Windows#main()}; the others are declared through {@link Windows#named}.
 *
 * <p><b>Every command is safe from any thread</b>, which is why a recipe holding one is not main-thread. Each only
 * asks: {@link #show} posts to the frame loop, and the window is created there, at the top of the next frame. That
 * was already true of the {@code AppWindow} underneath; what was not true is that a recipe could hold one without
 * also taking the {@code GuiApp} to claim it, which made the recipe main-thread for the sake of a handle that is not
 * (calculator FN-1). The claim is the framework's now, so the colour is the handle's own.
 *
 * <p><b>Usable before the window can exist.</b> The handle is handed out with the trees, two phases before there is a
 * device, and the window is claimed at {@code ATTACH}. A command before then is remembered and carried out when the
 * window is claimed, so a tree that opens its window on its first frame does not have to know whether that is
 * possible yet.
 *
 * <p><b>The tree outlives the window</b>, as {@code AppWindow}'s does: closing destroys the window and keeps the
 * {@link #gui()}, and showing again re-creates a window around the same tree, so whatever was in it is still there.
 * The framework closes the tree at shutdown.
 *
 * <p><b>The main window is the same type with one difference</b>: closing it is quitting. Its {@link #close} takes the
 * route its own close button does, so a close gate ({@link Shell#onClose}) is asked first and may refuse; it cannot be
 * shown again once it has gone. That is what a <i>Quit</i> button wants, and it is why such a button no longer needs
 * the {@code GuiApp}.
 *
 * <p><b>What the framework does for a named window</b>, and what used to be five steps at every second window: its
 * own {@code Gui}, on a bus of its own (two trees on one bus each receive the other's mutations, so the main window
 * drew the calculator's settings) and on the application's lanes; the application's look, or the one it was declared
 * with; a title bar wearing the application's window mark, with the controls and the instruments of its own window;
 * its placement and zoom remembered under its name, and forgotten when it closes; and the application's clipboard.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Window {

    /** What a claimed window is commanded through: a named window's {@code AppWindow}, or the main window's loop. */
    private interface Handle {

        void show();

        void hide();

        void toggle();

        void close();

        boolean open();
    }

    private final NamedWindow declared;
    private final Gui gui;
    private final TitleBar bar;
    private final List<Runnable> opened = new CopyOnWriteArrayList<>();
    private final List<Runnable> closed = new CopyOnWriteArrayList<>();

    /** The claimed window, or null until {@code ATTACH}. Guarded by {@code this}, with the two below. */
    private Handle handle;
    /** Whether the window should be on screen once it is claimed: the commands given before it could be. */
    private boolean shown;
    /** Whether {@link #close} was asked for before the claim. Only the main window acts on it: see {@link #claimMain}. */
    private boolean closeAsked;
    /** The handle, when this is the main window; null for a named one. */
    private MainHandle main;

    /**
     * @param shown whether the window is on screen once claimed unless a command says otherwise: true for the main
     *              window, which opens with the application, and false for a named one, which opens when asked
     */
    Window(NamedWindow declared, Gui gui, TitleBar bar, boolean shown) {
        this.declared = declared;
        this.gui = gui;
        this.bar = bar;
        this.shown = shown;
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
        Handle h;
        synchronized (this) {
            shown = true;
            h = handle;
        }
        if (h != null) {
            h.show();
        }
        return this;
    }

    /** Take this window off the screen, keeping it and everything in it for the next {@link #show}. */
    public Window hide() {
        Handle h;
        synchronized (this) {
            shown = false;
            h = handle;
        }
        if (h != null) {
            h.hide();
        }
        return this;
    }

    /** {@link #show} if this window is not in front of the user, {@link #hide} if it is. */
    public Window toggle() {
        Handle h;
        synchronized (this) {
            h = handle;
            if (h == null) {
                shown = !shown;
            }
        }
        if (h != null) {
            h.toggle();
        }
        return this;
    }

    /**
     * Close this window by the route its own close button takes. A named window goes and its tree stays, for a later
     * {@link #show}: what a button inside the window does when it means "done". The main window's close is quitting,
     * and its close gate is asked first.
     */
    public Window close() {
        Handle h;
        synchronized (this) {
            shown = false;
            closeAsked = true;
            h = handle;
        }
        if (h != null) {
            h.close();
        }
        return this;
    }

    /** Whether the window exists, on screen or hidden. False before {@code ATTACH}. */
    public boolean isOpen() {
        Handle h;
        synchronized (this) {
            h = handle;
        }
        return h != null && h.open();
    }

    /**
     * Run {@code listener} each time the window is created — placed where it was left, its zoom restored — on the
     * main thread. For a part that does work only while its window is up, such as a viewer reading full-size images.
     * The main window is created once, and its listeners run when it is claimed, at {@code ATTACH}.
     */
    public Window onOpened(Runnable listener) {
        opened.add(listener);
        return this;
    }

    /**
     * Run {@code listener} each time the window is destroyed, on the main thread. The tree is still there. The main
     * window's run once, when the frame loop has ended and before anything is closed.
     */
    public Window onClosed(Runnable listener) {
        closed.add(listener);
        return this;
    }

    /**
     * Claim a named window, once, at {@code ATTACH}: everything a window needs that only exists once there is a
     * device. Main thread. {@code mark} is the application's window mark, or null for none.
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
        Handle h = new Handle() {
            @Override
            public void show() {
                w.show();
            }

            @Override
            public void hide() {
                w.hide();
            }

            @Override
            public void toggle() {
                w.toggle();
            }

            @Override
            public void close() {
                w.close();
            }

            @Override
            public boolean open() {
                return w.open();
            }
        };
        boolean show;
        synchronized (this) {
            handle = h;
            show = shown;
        }
        // A close asked for before now has nothing to close: the window was never open.
        if (show) {
            h.show();
        }
    }

    /** The main window's handle: every command posted to the loop, and open until the loop ends. */
    private static final class MainHandle implements Handle {

        private final GuiApp app;
        private volatile boolean running = true;

        MainHandle(GuiApp app) {
            this.app = app;
        }

        @Override
        public void show() {
            app.post(() -> app.window().focus());   // focus() unhides and un-minimizes on the way
        }

        @Override
        public void hide() {
            app.post(() -> app.window().hide());
        }

        @Override
        public void toggle() {
            app.post(() -> {
                if (app.window().isVisible()) {
                    app.window().hide();
                } else {
                    app.window().focus();
                }
            });
        }

        @Override
        public void close() {
            // The route the title bar's X takes, so the application's close gate is asked first.
            app.post(() -> app.controls().close());
        }

        @Override
        public boolean open() {
            return running;
        }
    }

    /**
     * Claim the main window, once, at {@code ATTACH}, after the framework has set it up. Main thread. Its open
     * listeners run here, since it was created with the application. A close asked for before now is carried out:
     * an application that decided to quit while it was being built quits as soon as it can.
     */
    void claimMain(GuiApp app) {
        MainHandle h = new MainHandle(app);
        boolean show;
        boolean close;
        synchronized (this) {
            handle = h;
            main = h;
            show = shown;
            close = closeAsked;
        }
        run(opened);
        if (close) {
            h.close();
        } else if (!show) {
            h.hide();
        }
    }

    /** The main window's loop has ended. Main thread, before anything is closed. */
    void mainEnded() {
        MainHandle h;
        synchronized (this) {
            h = main;
        }
        if (h != null) {
            h.running = false;
        }
        run(closed);
    }

    private static void run(List<Runnable> listeners) {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}

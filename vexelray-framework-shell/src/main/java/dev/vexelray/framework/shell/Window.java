package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.core.WindowClosed;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowFullScreen;
import dev.vexelray.framework.core.WindowOpened;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Icon;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;

/**
 * <b>One of this application's windows</b> — the main one, or a settings window, a viewer, a tool window by name —
 * with its tree built by the framework. The main window is {@link Windows#main()}; the others are declared through
 * {@link Windows#named}.
 *
 * <p><b>What a part holds is the tree, not the window.</b> A recipe builds into {@link #gui()} and places
 * {@link #titleBar()}; it opens, hides or closes the window by sending a {@link WindowCommand} with its {@link #key()},
 * and hears it come and go as {@link WindowOpened} and {@link WindowClosed}. The commands are carried out, and
 * everything this class knows about the window is read and written, on the main thread alone, so there is no lock in
 * it. (In 0.1 the handle's {@code show()} could be called from any thread and its listeners ran on the main thread:
 * nine {@code synchronized} blocks and two copy-on-write lists, ROADMAP §3.)
 *
 * <p><b>Usable before the window can exist.</b> The trees are built two phases before there is a device, and the
 * window is claimed at {@code ATTACH}. A command before then is remembered and carried out when the window is claimed,
 * so a tree that opens its window on its first frame does not have to know whether that is possible yet.
 *
 * <p><b>The tree outlives the window</b>, as {@code AppWindow}'s does: closing destroys the window and keeps the
 * {@link #gui()}, and showing again re-creates a window around the same tree, so whatever was in it is still there.
 * The framework closes the tree at shutdown.
 *
 * <p><b>The main window is the same type with one difference</b>: closing it is quitting, by the route its own close
 * button takes, so the application is asked first ({@code CloseRequested}) and may refuse; it cannot be shown again
 * once it has gone.
 *
 * <p><b>What the framework does for a named window</b>, and what used to be five steps at every second window: its
 * own {@code Gui}, named by its key on the application's bus (so its widgets are {@code "<key>.<name>"}, and a view
 * hears them as it hears the main window's); the application's look, or the one it was declared
 * with; a title bar wearing the application's window mark, with the controls and the instruments of its own window;
 * its placement and zoom remembered under its name, and forgotten when it closes; and the application's clipboard.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Window {

    /** What a claimed window is commanded through: a named window's {@code AppWindow}, or the main window's loop. */
    private interface Handle extends WindowCommand.Sink {
    }

    private final NamedWindow declared;
    private final Gui gui;
    private final TitleBar bar;
    /** Where a close is asked of the application ({@code CloseRequested}), and where opened and closed are said. */
    private final Shell shell;

    // The main thread's, from here down: the startup thread before ATTACH, and the frame after.

    /** The claimed window, or null until {@code ATTACH}. */
    private Handle handle;
    /** Whether the window should be on screen once it is claimed: the commands given before it could be. */
    private boolean shown;
    /** Full screen as the commands before the claim left it: null for none given, so the window opens as remembered. */
    private Boolean fullScreen;
    /** Whether a close was asked for before the claim. Only the main window acts on it: see {@link #claimMain}. */
    private boolean closeAsked;
    /**
     * Told how the next close goes, once: {@code [proceeded, cancelled]}, or null. Set by {@link #close(Runnable,
     * Runnable)}, taken by the close it asked for.
     */
    private Runnable[] outcome;

    /**
     * @param shown whether the window is on screen once claimed unless a command says otherwise: true for the main
     *              window, which opens with the application, and false for a named one, which opens when asked
     */
    Window(NamedWindow declared, Gui gui, TitleBar bar, boolean shown, Shell shell) {
        this.declared = declared;
        this.gui = gui;
        this.bar = bar;
        this.shown = shown;
        this.shell = shell;
        // Undo for the whole window, bound before any widget exists so there is no window without it: Ctrl+Z,
        // Ctrl+Shift+Z and Ctrl+Y everywhere in the tree, and a failure shown rather than swallowed. A drop or a
        // paste is the application's to record, as any edit of its model is: the widget only announces it. A narrower history (a page's, a document's) outranks this one by
        // claim scope, as Gui.history says.
        gui.history(history);
        UndoFeedback.install(gui, shell);
        // Motion for the whole window, on the application's one clock, so a second window moves exactly as the main
        // one does rather than not at all. See WindowMotion. The tree's clock too, which runs every Tween and Timing in
        // it: KronoGui installed it in the main window's tree only, and without one a named window's tween landed at
        // once (the About window's intro never played).
        Seams.of(gui).clock(Seams.of(shell.gui()).clock());
        WindowMotion.install(gui, shell.krono());
    }

    /** This window's undo and redo. See {@link #history()}. */
    private final History history = new History();

    /**
     * <b>This window's undo history</b>, one per window and bound by the framework to Ctrl+Z, Ctrl+Shift+Z and Ctrl+Y
     * and to every drop and paste in its tree (docs/plans/undo.md §5, in {@code vexelray-gui}).
     *
     * <p>An application records its own commands here — {@code history().perform(change)} — so they undo with
     * everything else, and marks it on save for the dirty dot. An Undo button or menu item calls
     * {@code gui().undo(history())}, never {@code history().undo()}: the first takes the user to what will change
     * before changing it, the second changes it wherever it is.
     */
    public History history() {
        return history;
    }

    /** The name this window is known by: in the settings file, on the automation socket, and in a WindowCommand. */
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

    /** Whether the window is to be on screen, as the commands so far have left it. Main thread; for a test. */
    boolean shown() {
        return shown;
    }

    /** Full screen as the commands before the claim left it, or null for none given. Main thread; for a test. */
    Boolean fullScreen() {
        return fullScreen;
    }

    /** Carry out {@code command}. Main thread. */
    void command(WindowCommand command) {
        command.to(commands);
    }

    /** The commands, as the main thread carries them out: on the window once claimed, remembered until then. */
    private final WindowCommand.Sink commands = new WindowCommand.Sink() {
        @Override
        public void show() {
            Window.this.show();
        }

        @Override
        public void hide() {
            shown = false;
            if (handle != null) {
                handle.hide();
            }
        }

        @Override
        public void toggle() {
            if (handle == null) {
                shown = !shown;
            } else {
                handle.toggle();
            }
        }

        @Override
        public void close() {
            Window.this.close();
        }

        @Override
        public void fullScreen(boolean on) {
            if (handle == null) {
                fullScreen = on;
            } else {
                handle.fullScreen(on);
            }
        }

        @Override
        public void toggleFullScreen() {
            if (handle == null) {
                fullScreen = !Boolean.TRUE.equals(fullScreen);
            } else {
                handle.toggleFullScreen();
            }
        }
    };

    /**
     * Put this window in front of the person: open it if it is closed, unhide it if it is hidden, raise it and give it
     * the keyboard. Main thread: the framework's own, for the window mark's About.
     */
    void show() {
        shown = true;
        if (handle != null) {
            handle.show();
        }
    }

    /**
     * Close this window by the route its own close button takes. A named window goes and its tree stays, for a later
     * show. The main window's close is quitting, and the application is asked first. Main thread.
     */
    void close() {
        shown = false;
        closeAsked = true;
        if (handle != null) {
            handle.close();
        }
    }

    /**
     * A close has arrived, by the close button, by {@link #close}, or for the main window by the OS or a liveness exit:
     * ask the application ({@code CloseRequested}) when something hears that, or let it through. What the window's own
     * close route calls, in the frame, on the main thread; package-private so a test can stand in for the route.
     */
    void requested(Runnable proceed, Runnable cancel) {
        Runnable[] told = outcome;
        outcome = null;
        Runnable go = told == null ? proceed : () -> {
            told[0].run();
            proceed.run();
        };
        Runnable stay = told == null ? cancel : () -> {
            told[1].run();
            cancel.run();
        };
        shell.closes().ask(key(), go, stay);
    }

    /**
     * {@link #close()}, told whether it happened: {@code proceeded} once the application (if it is asked) has let it,
     * before the window goes, or {@code cancelled} if it kept the window open. What a restart needs, since it must not
     * start a second process for a window that stayed. Main thread.
     */
    void close(Runnable proceeded, Runnable cancelled) {
        outcome = new Runnable[] {proceeded, cancelled};
        close();
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
        WindowSpec spec = WindowSpec.of(config, gui).standing(declared.standing())
                .onCloseRequest(request -> requested(request::proceed, request::cancel));
        if (bar != null) {
            // Its own window's controls while it is open, and none once it closes; the instruments with them, so a
            // screenshot from this bar photographs this window.
            spec = bar.commands(spec);
            bar.instruments(appearance.instruments()).icon(app, mark);
        }
        // Remembered first, so a part hearing WindowOpened finds the window where it was left; and forgotten on close,
        // which is what a watch from onCreated never was (calculator FN-4).
        WindowSpec remembered = memory.remember(key, spec, declared.width(), declared.height())
                .onCreated(w -> shell.bus().publish(WindowOpened.TOPIC, new WindowOpened(key)))
                .onClosed(() -> shell.bus().publish(WindowClosed.TOPIC, new WindowClosed(key)));
        AppWindow w = app.window(key, () -> remembered);
        handle = new Handle() {
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
            public void fullScreen(boolean on) {
                app.post(() -> screen(w.window(), on));
            }

            @Override
            public void toggleFullScreen() {
                app.post(() -> screen(w.window(), null));
            }
        };
        // A close asked for before now has nothing to close: the window was never open.
        if (shown) {
            handle.show();
            if (fullScreen != null) {
                handle.fullScreen(fullScreen);
            }
        }
    }

    /**
     * Claim the main window, once, at {@code ATTACH}, after the framework has set it up. Main thread. It was created
     * with the application, so it is said to be open here. A close asked for before now is carried out: an application
     * that decided to quit while it was being built quits as soon as it can.
     */
    void claimMain(GuiApp app) {
        // GuiApp holds one handler for the main window, and this is it.
        app.onCloseRequest(request -> requested(request::proceed, request::cancel));
        // Posted to the top of the next frame, as a named window's AppWindow posts its own: a command arrives in the
        // middle of one, and the loop's window is changed between frames.
        handle = new Handle() {
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
                // The route the title bar's X takes, so the application is asked first.
                app.post(() -> app.controls().close());
            }

            @Override
            public void fullScreen(boolean on) {
                app.post(() -> screen(app.window(), on));
            }

            @Override
            public void toggleFullScreen() {
                app.post(() -> screen(app.window(), null));
            }
        };
        shell.bus().publish(WindowOpened.TOPIC, new WindowOpened(key()));
        if (closeAsked) {
            handle.close();
        } else if (!shown) {
            handle.hide();
        } else if (fullScreen != null) {
            handle.fullScreen(fullScreen);
        }
    }

    /**
     * Put {@code w} full screen ({@code on}), back to its window, or, with null, whichever it is not; and say what it is
     * now. Main thread, between frames. A window not open has nothing to change, and nothing is said.
     */
    private void screen(NativeWindow w, Boolean on) {
        if (w == null) {
            return;
        }
        w.fullscreen(on == null ? !w.isFullscreen() : on);
        shell.bus().publish(WindowFullScreen.TOPIC, new WindowFullScreen(key(), w.isFullscreen()));
    }

    /** The main window's loop has ended. Main thread, before anything is closed. */
    void mainEnded() {
        shell.bus().publish(WindowClosed.TOPIC, new WindowClosed(key()));
    }
}

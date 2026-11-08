package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowView;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Icon;
import sibarum.probe.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>This application's windows</b>: the main one, and the others, declared by name with the trees and opened by a
 * {@link WindowCommand}. A recipe takes it like any other framework value:
 *
 * {@snippet :
 * @Provides
 * SettingsWindow settings(Windows windows, Model model) {
 *     Window window = windows.named(NamedWindow.of("settings", "Settings", 500, 640).satellite());
 *     return new SettingsWindow(window, model);     // and a view's chord sends WindowCommand.show("settings")
 * }
 * }
 *
 * <p><b>Not main-thread</b>, and that is the point of it. A second window used to be claimed by the application
 * through {@code GuiApp.window}, and a recipe taking the {@code GuiApp} is main-thread (T2.2) whatever it returns,
 * so a handle every command of which only posts had to be coloured as if it touched Vulkan (calculator FN-1,
 * Vexplore's late-bound viewer). Here the framework claims the window, at {@code ATTACH}, and the handle a recipe
 * holds is a {@link Window}, whose colour is its own.
 *
 * <p><b>From {@code GUI}, and declared by the end of {@code TREE}.</b> A window's tree needs nothing a device makes,
 * so it is built when the main one is, and a headless {@link VexelApplication#tree} run builds it too. A declaration
 * after {@code TREE} is refused rather than quietly never claimed: the framework claims every window at the start of
 * {@code ATTACH}, and a recipe that took the {@code GuiApp} and then declared a window would be declaring one the
 * claim had already passed.
 *
 * <p><b>The main window too</b>, as {@link #main()}, under the key {@code "main"}: a <i>Quit</i> button sends it a
 * close, and something that wants the application in front sends it a show, without taking the {@code GuiApp} for
 * either. What is left behind the {@code GuiApp} is the device, which is the main thread's by rule, and not the
 * windows.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Windows {

    /** The main window's name, which no other window may take. */
    static final String MAIN = dev.vexelray.framework.core.CloseRequested.MAIN;

    private static final Log LOG = Log.of("framework.shell");

    private final Shell shell;
    private final Window main;
    private final List<Window> declared = new ArrayList<>();
    /** The application, once the windows are claimed: what {@link #views} reads. */
    private volatile GuiApp app;

    /** @param main the main window, built by the framework with its tree; null only in a test of the declarations */
    Windows(Shell shell, Window main) {
        this.shell = shell;
        this.main = main;
        // Every window's commands are carried out in the main window's frame, on the main thread, where everything a
        // Window knows is kept.
        if (main != null) {
            main.gui().mailbox(WindowCommand.TOPIC, this::command, CAPACITY);
        }
    }

    /** How many commands may wait for one frame: far more than anyone sends. */
    private static final int CAPACITY = 64;

    /** Carry out {@code c} on the window it names. Main thread, in the frame. */
    private void command(WindowCommand c) {
        Window w = byKey(c.window());
        if (w == null) {
            LOG.warn("window command " + c.command() + " for '" + c.window() + "', and no window has that key: "
                    + "ignored");
        } else {
            w.command(c);
        }
    }

    private Window byKey(String key) {
        if (main != null && main.key().equals(key)) {
            return main;
        }
        for (Window w : declared) {
            if (w.key().equals(key)) {
                return w;
            }
        }
        return null;
    }

    /**
     * The main window. Its {@link Window#close} is quitting, by the route its own close button takes, so the
     * application is asked first, when a part hears {@link dev.vexelray.framework.core.CloseRequested}.
     */
    public Window main() {
        return main;
    }

    /**
     * Every window open right now, the main one first: each one's name, tree and controls. A snapshot, safe from any
     * thread, and empty before {@code ATTACH}.
     *
     * <p><b>For an instrument</b> — the automation socket selects windows from this — and the reason it is here rather
     * than the {@code GuiApp} being handed out for it: an instrument that needs the windows needs nothing else
     * behind that boundary.
     */
    public List<WindowView> views() {
        GuiApp a = app;
        return a == null ? List.of() : a.windows();
    }

    /** {@link #named(NamedWindow)}, for a window beside the main one, in the application's look. */
    public Window named(String key, String title, int width, int height) {
        return named(NamedWindow.of(key, title, width, height));
    }

    /**
     * Declare the window {@code window} describes, and build its tree now: a {@code Gui} of its own, named by the key
     * on the application's bus, in the application's look (or the one declared), and a title bar unless the
     * application asked for the OS frame. The window itself is claimed at {@code ATTACH}.
     *
     * <p>Once per name. A second declaration under one name is refused rather than answered with the first, because
     * it would be two recipes each building a tree into one window and each believing the window was theirs.
     */
    public Window named(NamedWindow window) {
        if (shell.phase().compareTo(Phase.TREE) > 0) {
            throw new IllegalStateException("window '" + window.key() + "' must be declared by TREE, because the "
                    + "framework claims every window at the start of ATTACH; this is " + shell.phase());
        }
        if (window.key().equals(MAIN)) {
            throw new IllegalArgumentException("'" + MAIN + "' is the main window's name; a second window needs "
                    + "one of its own");
        }
        for (Window w : declared) {
            if (w.key().equals(window.key())) {
                throw new IllegalStateException("a window called '" + window.key() + "' is already declared; two "
                        + "trees cannot share one window");
            }
        }
        Appearance appearance = shell.appearance();
        // On the application's bus, named by the window's key, as the main tree is: so a view hears this window's widgets
        // and the application's messages alike, and a widget here answers to "<key>.<name>". Two trees on one bus used
        // to receive each other's mutations (calculator FN-2); a tree's own topics are named by it now (T4.6), and the
        // widgets' shared topics are scoped by tree, so a closed window, which runs no frames, is offered nothing of
        // another's to hold. A Gui owns no threads, so a second window is not a second set of them.
        Gui gui = shell.disposer().register(new Gui(shell.bus(), window.key()));
        if (window.theme() == null) {
            appearance.applyTo(gui);
        } else {
            // A look of its own still shares how far the zoom goes: see Appearance.applyTo.
            gui.theme(window.theme());
            appearance.zoom().applyTo(gui);
        }
        TitleBar bar = appearance.drawsOwnFrame() ? new TitleBar(gui, WindowControls.NONE, window.title()) : null;
        Window w = new Window(window, gui, bar, false, shell);
        declared.add(w);
        return w;
    }

    /** Every window declared so far, in declaration order. */
    List<Window> declared() {
        return List.copyOf(declared);
    }

    /**
     * Claim every declared window, and bind each to the application's clipboard; then the main window. Once, at the
     * start of {@code ATTACH}, on the main thread, after the main window is set up.
     *
     * @param appearance the look the main bar was given, its instruments included, so every bar carries the same tools
     */
    void claim(GuiApp app, WindowMemory memory, ClipboardBackend clipboard, Icon mark, Appearance appearance) {
        this.app = app;
        for (Window w : declared()) {
            // A window that is forgotten is one where Ctrl+C does nothing and reports nothing: see Shell.clipboard.
            clipboard.installOn(w.gui());
            w.claim(app, memory, appearance, mark);
        }
        // Last, so a main window closed while the trees were built is closed with the others already claimed.
        main.claimMain(app);
    }

    /** The frame loop has ended. Main thread, before anything is closed. */
    void ended() {
        main.mainEnded();
    }
}

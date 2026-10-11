package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Icon;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>This application's windows besides the main one</b>, declared by name with the trees and opened on request.
 * A recipe takes it like any other framework value:
 *
 * {@snippet :
 * @Provides
 * SettingsWindow settings(Windows windows, Model model, Ui ui) {
 *     Window window = windows.named(NamedWindow.of("settings", "Settings", 500, 640).satellite());
 *     ui.onSettings(window::show);
 *     return new SettingsWindow(window, model);
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
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Windows {

    /** The main window's name, which no other window may take. */
    static final String MAIN = "main";

    private final Shell shell;
    private final List<Window> declared = new ArrayList<>();

    Windows(Shell shell) {
        this.shell = shell;
    }

    /** {@link #named(NamedWindow)}, for a window beside the main one, in the application's look. */
    public Window named(String key, String title, int width, int height) {
        return named(NamedWindow.of(key, title, width, height));
    }

    /**
     * Declare the window {@code window} describes, and build its tree now: a {@code Gui} of its own on a bus of its
     * own and the application's lanes, in the application's look (or the one declared), and a title bar unless the
     * application asked for the OS frame. The window itself is claimed at {@code ATTACH}.
     *
     * <p>Once per name. A second declaration under one name is refused rather than answered with the first, because
     * it would be two recipes each building a tree into one window and each believing the window was theirs.
     */
    public synchronized Window named(NamedWindow window) {
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
        // A bus of its own, which is Gui's rule rather than a preference: its topics are static, so two trees on
        // one bus each receive the other's mutations (calculator FN-2). The lanes are the application's, so a
        // second window is not a second set of threads.
        Gui gui = shell.disposer().register(
                new Gui(Atchung.create(), shell.lanes().handlers(), shell.lanes().offload()));
        if (window.theme() == null) {
            appearance.applyTo(gui);
        } else {
            // A look of its own still shares how far the zoom goes: see Appearance.applyTo.
            gui.theme(window.theme());
            appearance.zoom().applyTo(gui);
        }
        TitleBar bar = appearance.drawsOwnFrame() ? new TitleBar(gui, WindowControls.NONE, window.title()) : null;
        Window w = new Window(window, gui, bar);
        declared.add(w);
        return w;
    }

    /** Every window declared so far, in declaration order. */
    synchronized List<Window> declared() {
        return List.copyOf(declared);
    }

    /**
     * Claim every declared window, and bind each to the application's clipboard. Once, at the start of
     * {@code ATTACH}, on the main thread.
     */
    void claim(GuiApp app, WindowMemory memory, ClipboardBackend clipboard, Icon mark) {
        Appearance appearance = shell.appearance();
        for (Window w : declared()) {
            // A window that is forgotten is one where Ctrl+C does nothing and reports nothing: see Shell.clipboard.
            clipboard.installOn(w.gui());
            w.claim(app, memory, appearance, mark);
        }
    }
}

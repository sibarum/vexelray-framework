package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.api.RunMode;
import dev.vexelray.framework.core.Disposer;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;

import java.util.function.Function;

/**
 * Runs an application: the phases in order, the loop, and the shutdown.
 *
 * <p>This is the ~350 lines of {@code mainframe-template}'s {@code App.java} with the decisions taken. Read
 * that file beside this one — every block below corresponds to one there, and the comments that justified the
 * ordering have moved onto {@link Phase} and {@link FrameStage}, where they are attached to a type rather than
 * to one copy of a file.
 *
 * <p>Nothing here is reflective and nothing here is discovered. The only thing this class knows about an
 * application is its {@link Wiring} — a normal object, called through six direct method calls, one per phase.
 */
public final class VexelApplication {

    /**
     * The key the main window is remembered under.
     *
     * <p>Named rather than spelled out at each use, for the reason the text editor gives about its own: each is
     * read from more than one place, and a literal that has to agree across call sites is a rename waiting to
     * orphan somebody's window.
     */
    private static final String MAIN = "main";

    /** 5 Hz floor while focused: a missed wake is late, never lost. */
    private static final long IDLE_REFRESH_NANOS = 200_000_000L;

    /** 60 Hz ceiling while animating. */
    private static final long MAX_FRAME_NANOS = 16_666_666L;

    /** Usage error. Distinct from 1 so a script can tell "you typed it wrong" from "it went wrong". */
    private static final int EXIT_USAGE = 2;

    private VexelApplication() {
    }

    /**
     * Run {@code wiring} against {@code args}.
     *
     * <p>Returns when the loop ends and everything is closed. A usage error on the command line exits with
     * {@link #EXIT_USAGE} after printing the message and the usage line — deliberately not a stack trace,
     * which is what one of these applications currently produces for a misspelled flag.
     */
    public static void run(Wiring wiring, String[] args) {
        run(wiring, args, null);
    }

    /**
     * As {@link #run(Wiring, String[])}, with <b>every</b> window this application opens made by
     * {@code windows} — the main window, and every popup, named window and dialog after it. {@code null} is
     * the platform's own, which is what {@link #run(Wiring, String[])} passes.
     *
     * <p><b>This exists for one test that cannot otherwise be written.</b> {@code vexelray-gui-harness} runs a
     * real frame loop against windows that are genuinely created and never shown, in order to ask the one
     * question a hand-driven frame cannot — <i>"after this click, does a frame arrive on its own?"</i> — and it
     * takes over window creation to do it, because a window is on screen from the instant the platform makes
     * it. Without a seam here, the framework's own wake wiring was the part of it no such test could reach:
     * {@link Shell#wake} against {@code gui::onWork} and {@code krono.kron()::onWork}, and the two
     * {@link Shell#deadline} calls, are unremarkable to write and catastrophic to omit, and {@code -core}'s
     * {@code PacingTest} can only prove them as arithmetic. The GUI's own record is that five missing wakes
     * shipped past a green suite.
     *
     * <p>The factory is handed straight to {@code GuiApp}, so its contract is that constructor's: called on the
     * main thread, in creation order, with the fully resolved config, and returning {@code null} is a bug. A
     * factory that adds nothing is an ordinary application, which is why the default is this method with
     * {@code null} rather than a second code path.
     */
    public static void run(Wiring wiring, String[] args, Function<WindowConfig, NativeWindow> windows) {
        AppInfo info = wiring.info();
        Launch launch = parseOrExit(args, info);

        Shell shell = new Shell(launch, info);
        try (Disposer disposer = shell.disposer()) {
            build(wiring, shell, disposer, info, launch, windows);
        }
    }

    /**
     * Build {@code wiring} as far as {@link Phase#TREE} and hand back the {@link Shell} — no window, no input
     * backend, no window memory and no loop.
     *
     * <p><b>What this is for.</b> {@code Phase.TREE} carries the claim that the widget tree is buildable before
     * a window exists, and states the reason: <i>"A tree that cannot be built without a window could not be
     * captured headlessly."</i> Everything needed to act on that claim was already true — the theme is applied,
     * the clock is attached, the title bar is a working bar against {@code WindowControls.NONE} — and there was
     * no way to ask for it. So the one thing the phase exists to make possible was the one thing the framework
     * could not do.
     *
     * <p>Deliberately not a run mode. {@link RunMode} records why the framework's own {@code CAPTURE} mode was
     * removed: {@code GuiApp.capture} is static, builds its own device, and so photographs a marched viewport
     * as the framework's placeholder texture — <i>"correct about the chrome and silently wrong about the
     * content"</i>. That has not stopped being true, which is why what comes back from here is a {@code Shell}
     * and not a PNG. An application that knows its own tree holds nothing device-backed can capture it and know
     * what it is getting; one that does not should reach for {@code WindowInstrument.screenshot()} instead.
     *
     * <p>The caller owns the shutdown, because the caller decides when it has finished with the tree:
     *
     * {@snippet :
     * Shell shell = VexelApplication.tree(new MyWiring(), args);
     * try {
     *     Color page = shell.gui().theme().color(Role.PAGE);
     *     GuiApp.capture(shell.gui(), W, H, page.r(), page.g(), page.b(), out);
     * } finally {
     *     shell.disposer().close();
     * }
     * }
     *
     * <p>No {@code WindowMemory} is built, so nothing reached from here can write a placement — which is what
     * the hand-written capture entry points each had to say for themselves in a comment.
     */
    public static Shell tree(Wiring wiring, String[] args) {
        AppInfo info = wiring.info();
        Shell shell = new Shell(parseOrExit(args, info), info);
        try {
            toTree(wiring, shell, shell.disposer(), info);
        } catch (RuntimeException | Error e) {
            // The Gui and the clock are registered by the time most failures here can happen, and a caller
            // that never received the Shell has no way to close them.
            try {
                shell.disposer().close();
            } catch (RuntimeException | Error nested) {
                e.addSuppressed(nested);
            }
            throw e;
        }
        return shell;
    }

    private static Launch parseOrExit(String[] args, AppInfo info) {
        try {
            return Launch.parse(args, info.name(), info.settingKeys());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(Launch.usage(info.name(), info.settingKeys()));
            System.exit(EXIT_USAGE);
            throw new IllegalStateException("unreachable: System.exit does not return", e);
        }
    }

    /**
     * {@link Phase#CONFIG} through {@link Phase#TREE} — everything that does not need a window.
     *
     * <p>Split out so that {@link #tree} and {@link #run} cannot disagree about it. A capture that built its
     * tree by a second route would be a capture of a different application, and that is not hypothetical: the
     * text editor's hand-written {@code --capture} cleared to a literal {@code 0.06f, 0.07f, 0.09f} while the
     * entry point twenty lines below it read {@code Role.PAGE} off the theme, and only one of those two could
     * still have been right.
     */
    private static void toTree(Wiring wiring, Shell shell, Disposer disposer, AppInfo info) {
        // ---- CONFIG: the settings store, and the look. Both are values; neither needs a Gui. -------------
        shell.phase(Phase.CONFIG);
        shell.settings(Settings.open(info.name()));
        wiring.config(shell);

        // ---- MODEL: what the application knows, before there is anything to draw it with. ----------------
        shell.phase(Phase.MODEL);
        wiring.model(shell);

        // ---- GUI: the look applied before the first widget, and the clock attached before it too. --------
        shell.phase(Phase.GUI);
        Gui gui = disposer.register(new Gui());
        Appearance appearance = shell.appearance();
        // The theme and the zoom range, through the same call an application uses on the windows the framework
        // did not build -- so there is one definition of what an appearance means on a Gui rather than two that
        // have to be kept in agreement. Before the first widget, because a role resolves when a widget writes a
        // prop. The chords that move within the zoom range stay the application's: see Appearance.ZoomRange.
        appearance.applyTo(gui);
        // Not part of that call, because Gui.minSize is a floor for one tree's layout and not for the
        // application: the main window's answer is the wrong one for a tool window beside it.
        if (appearance.hasMinSize()) {
            gui.minSize(appearance.minWidth(), appearance.minHeight());
        }
        shell.gui(gui);
        // Registered after the Gui so it closes before it: the clock outlives the window but not the process.
        KronoGui krono = disposer.register(KronoGui.attach(gui));
        shell.krono(krono);
        // The chrome, built by the framework so that the strip its instruments live in means the same thing in
        // every window (automation.md 7). Against WindowControls.NONE for now -- a native window cannot
        // photograph itself, so only GuiApp can mint working controls, and they are handed down at ATTACH.
        // Everything about how it draws comes from the application's own theme, applied two lines above.
        if (appearance.drawsOwnFrame()) {
            shell.titleBar(new TitleBar(gui, WindowControls.NONE, info.title()));
        }
        wiring.gui(shell);

        // ---- TREE: the widgets, the framework's title bar among them. No window needed. -------------------
        shell.phase(Phase.TREE);
        wiring.tree(shell);
    }

    private static void build(Wiring wiring, Shell shell, Disposer disposer, AppInfo info, Launch launch,
                              Function<WindowConfig, NativeWindow> windows) {
        toTree(wiring, shell, disposer, info);
        Gui gui = shell.gui();
        KronoGui krono = shell.krono();
        Appearance appearance = shell.appearance();

        // ---- WINDOW: the device and the window exist. Main-thread from here on. -------------------------
        shell.phase(Phase.WINDOW);
        WindowMemory memory = new WindowMemory(shell.settings());
        shell.memory(memory);
        InputBackend input = disposer.register(InputBackend.open());
        // The mark goes on the process before the first window exists, so every window this application opens
        // is shown wearing it rather than corrected into it a frame later. See AppInfo.icon for why the same
        // mark is then named on the window's own config as well.
        installMark(info.icon());
        // Placement is read before the window exists, so the window is created where it was left rather than
        // appearing and then moving -- and clamped on the way, because the desk may have changed shape.
        WindowConfig main = mainWindow(memory, info, appearance);
        GuiApp app = disposer.register(windows == null ? new GuiApp(main) : new GuiApp(main, windows));
        shell.app(app);
        wiring.window(shell);

        // ---- ATTACH: everything that needed the handle. -------------------------------------------------
        shell.phase(Phase.ATTACH);
        input.attach(app.windowHandle());
        input.bridge(gui);
        app.input(InputBackend.perWindow());
        // Held rather than discarded once installed: a clipboard belongs to a Gui, so an application with more
        // than one window has to bind the rest itself. See Shell.clipboard.
        ClipboardBackend clipboard = disposer.register(ClipboardBackend.open());
        clipboard.installOn(gui);
        shell.clipboard(clipboard);
        // The dialogs. Installed here rather than on request, because Modals is reached statically from
        // wherever a question arises -- so "the application forgot to install them" surfaces as an exception
        // thrown at the moment somebody needed an answer, which is the worst time to find out.
        shell.dialogs(disposer.register(Modals.install(app)));
        // The window exists at last, so the bar can be given controls that actually work and the instruments
        // that use them. Both in one place, because an instrument without real controls is the exact failure
        // automation.md 7 records: "every other window had a screenshot button that neither worked nor
        // complained".
        if (appearance.drawsOwnFrame()) {
            shell.titleBar().controls(app.controls()).instruments(appearance.instruments());
        }
        if (memory.maximized(MAIN)) {
            app.window().maximize();
        }
        // Watched with its tree, so the UI zoom is remembered too: Ctrl+= is the same kind of decision as
        // dragging the window bigger, and losing it on quit is the same loss.
        memory.watch(MAIN, app.window(), gui);

        // The frame, in stage order. See FrameStage for why input precedes the clock.
        shell.hooks()
                .add(FrameStage.INPUT, input::pump)
                .add(FrameStage.CLOCK, krono::tick)
                .add(FrameStage.SETTLE, memory::poll);

        // The two deadlines the framework itself holds. An application's own are added by its wiring, which is
        // the point of the seam: the clock knows about animations, and it does not know that the window
        // placement is 700ms from being written.
        shell.deadline(() -> krono.kron().sleepTimeout().nanos());
        shell.deadline(memory::nanosUntilSettle);

        // And the wakes, without which the parking below is a hang rather than a saving: a worker's mutation
        // and a timeline's tick are not OS input, so each has to nudge the message queue.
        shell.wake(gui::onWork);
        shell.wake(krono.kron()::onWork);

        wiring.attach(shell);

        // ---- RUN ----------------------------------------------------------------------------------------
        shell.phase(Phase.RUN);
        shell.hooks().seal();
        shell.pacing().seal();

        if (launch.mode() == RunMode.WINDOWED) {
            // Render on demand: park until something says a frame is due.
            app.pacing(shell.pacing()::nanosUntilNextFrame)
                    .idleRefresh(IDLE_REFRESH_NANOS)
                    .maxFrameRate(MAX_FRAME_NANOS);
        }
        // A fixed-frame run deliberately does none of the above: it is a script's mode, and parking to save
        // power in a run that exists to finish as fast as it can would only make it take longer.
        try {
            app.run(gui, launch.frames(), shell.hooks()::run);
        } finally {
            // The debounce has no next frame to fire on once the loop is over, so the last move of the
            // session is written here or not at all.
            memory.save();
        }
    }

    /** The main window's config: where it was left, how it is decorated, and the mark it wears. */
    private static WindowConfig mainWindow(WindowMemory memory, AppInfo info, Appearance appearance) {
        WindowConfig config = memory.config(MAIN, info.title(), info.width(), info.height())
                .decorations(appearance.decorations());
        // Named on the window as well as on the process. Redundant for exactly as long as this application is
        // the process; see AppInfo.icon for the arrangement where it stops being.
        return info.icon() == null ? config : config.icon(info.icon());
    }

    /**
     * Put the application's mark on the process, for every window it opens.
     *
     * <p>Not fatal. A mark that cannot be set costs the application its icon and nothing else, and a window
     * under the OS default is still a window — so this reports and carries on rather than taking the process
     * down on the way up.
     */
    private static void installMark(Icon icon) {
        if (icon == null) {
            return;
        }
        try {
            NativePlatform.current().setApplicationIcon(icon);
        } catch (RuntimeException e) {
            Diagnostics.dropped("VexelApplication.installMark", "the application's icon",
                    e + "; its windows wear the OS default instead");
        }
    }
}

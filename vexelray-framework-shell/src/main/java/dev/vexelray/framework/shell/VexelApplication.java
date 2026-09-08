package dev.vexelray.framework.shell;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.api.RunMode;
import dev.vexelray.framework.core.Disposer;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.os.Decorations;

import java.io.IOException;
import java.io.UncheckedIOException;

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
        AppInfo info = wiring.info();

        Launch launch;
        try {
            launch = Launch.parse(args, info.name(), info.settingKeys());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(Launch.usage(info.name(), info.settingKeys()));
            System.exit(EXIT_USAGE);
            return;
        }

        Shell shell = new Shell(launch, info);
        try (Disposer disposer = shell.disposer()) {
            build(wiring, shell, disposer, info, launch);
        }
    }

    private static void build(Wiring wiring, Shell shell, Disposer disposer, AppInfo info, Launch launch) {
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
        gui.theme(appearance.theme());
        if (appearance.hasMinSize()) {
            gui.minSize(appearance.minWidth(), appearance.minHeight());
        }
        shell.gui(gui);
        // Registered after the Gui so it closes before it: the clock outlives the window but not the process.
        KronoGui krono = disposer.register(KronoGui.attach(gui));
        shell.krono(krono);
        wiring.gui(shell);

        // ---- TREE: the widgets. Buildable with no window, which is what makes a capture possible. --------
        shell.phase(Phase.TREE);
        wiring.tree(shell);

        if (launch.mode() == RunMode.CAPTURE) {
            capture(shell, info, launch);
            return;
        }

        // ---- WINDOW: the device and the window exist. Main-thread from here on. -------------------------
        shell.phase(Phase.WINDOW);
        WindowMemory memory = new WindowMemory(shell.settings());
        shell.memory(memory);
        InputBackend input = disposer.register(InputBackend.open());
        // Placement is read before the window exists, so the window is created where it was left rather than
        // appearing and then moving -- and clamped on the way, because the desk may have changed shape.
        GuiApp app = disposer.register(new GuiApp(
                memory.config(MAIN, info.title(), info.width(), info.height())
                        .decorations(Decorations.CLIENT)));
        shell.app(app);
        wiring.window(shell);

        // ---- ATTACH: everything that needed the handle. -------------------------------------------------
        shell.phase(Phase.ATTACH);
        input.attach(app.windowHandle());
        input.bridge(gui);
        app.input(InputBackend.perWindow());
        disposer.register(ClipboardBackend.open()).installOn(gui);
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

    /**
     * One frame to a PNG, with no window shown and no input backend opened.
     *
     * <p>Reached before {@link Phase#WINDOW}, which is the whole reason it works on a machine with no input
     * backend: the components that only serve a session were never constructed rather than constructed and
     * found wanting.
     */
    private static void capture(Shell shell, AppInfo info, Launch launch) {
        Color page = shell.appearance().page();
        String out = launch.captureOut().toString();
        try {
            GuiApp.capture(shell.gui(), info.width(), info.height(), page.r(), page.g(), page.b(), out);
        } catch (IOException e) {
            throw new UncheckedIOException("capture to " + out + " failed", e);
        }
        System.out.println("captured " + out);
    }
}

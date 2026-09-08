package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.DeadlineSource;
import dev.vexelray.framework.core.Disposer;
import dev.vexelray.framework.core.FrameHooks;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Pacing;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.framework.core.WakeSource;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;

/**
 * What the framework has built so far, and where the wiring hands things back.
 *
 * <p>Passed to {@link Wiring#build} once per {@link Phase}. Deliberately not a bean registry: every accessor
 * here is a typed method returning one known type, so generated code reads {@code shell.app()} and is checked
 * by javac. There is no {@code get(Class)}, no name lookup, and nothing to configure — a service locator with a
 * map would reintroduce at runtime exactly the failure the processor exists to move to compile time.
 *
 * <p><b>Accessors throw before their phase.</b> {@link #app()} does not exist until {@link Phase#WINDOW}, and
 * asking early gets a message naming the phase rather than a {@code NullPointerException} thirty frames later.
 * Generated wiring cannot reach that state — the processor rejects a backwards dependency — so these checks
 * exist for hand-written wiring and for the framework's own mistakes, which is the honest description of a
 * backstop.
 */
public final class Shell {

    private final Launch launch;
    private final AppInfo info;
    private final FrameHooks hooks = new FrameHooks();
    private final Pacing pacing = new Pacing();
    private final Disposer disposer = new Disposer();

    private Appearance appearance = Appearance.DEFAULT;
    private Settings settings;
    private Gui gui;
    private KronoGui krono;
    private WindowMemory memory;
    private GuiApp app;
    private TitleBar titleBar;
    private Phase phase = Phase.CONFIG;

    Shell(Launch launch, AppInfo info) {
        this.launch = launch;
        this.info = info;
    }

    // ---- always available -------------------------------------------------------------------------------

    /** What the command line asked for, settled before anything was constructed. */
    public Launch launch() {
        return launch;
    }

    /** The facts from {@code @VexelApp}. */
    public AppInfo info() {
        return info;
    }

    /** The phase currently being built. */
    public Phase phase() {
        return phase;
    }

    /** Register a per-frame hook. See {@code FrameStage} for why the position is a name and not a number. */
    public FrameHooks hooks() {
        return hooks;
    }

    /** Register a resource to be closed in reverse construction order at shutdown. */
    public Disposer disposer() {
        return disposer;
    }

    /** Contribute a deadline, so the loop does not park past whatever this component is waiting for. */
    public Shell deadline(DeadlineSource source) {
        pacing.add(source);
        return this;
    }

    /**
     * Connect a wake source to the loop's wake.
     *
     * <p>Only legal from {@link Phase#ATTACH}, because before that there is no loop to wake. A source
     * registered earlier would be silently never connected, which is the failure this seam exists to prevent,
     * so it is refused instead.
     */
    public Shell wake(WakeSource source) {
        require(Phase.ATTACH, "wake sources");
        source.onWake(app::postWake);
        return this;
    }

    // ---- phased -----------------------------------------------------------------------------------------

    /**
     * The look and the size floor. Registered in {@link Phase#CONFIG} and applied when the {@code Gui} is
     * created, which is what makes "before anything is built" structural rather than advisory.
     */
    public Shell appearance(Appearance appearance) {
        if (phase.compareTo(Phase.CONFIG) > 0) {
            throw new IllegalStateException(
                    "appearance must be registered in CONFIG, before the Gui exists; this is " + phase);
        }
        this.appearance = appearance == null ? Appearance.DEFAULT : appearance;
        return this;
    }

    /** The look in force. Never null. */
    public Appearance appearance() {
        return appearance;
    }

    /**
     * The one settings store for this application.
     *
     * <p>One, and the container owning it is the whole of a bug two repos carry a comment about: two instances
     * over the same file each hold their own copy, so the second to save drops whatever the first had added.
     */
    public Settings settings() {
        return require(Phase.CONFIG, "settings", settings);
    }

    /** The GUI. Exists from {@link Phase#GUI}. */
    public Gui gui() {
        return require(Phase.GUI, "the Gui", gui);
    }

    /** The frame clock, attached to the {@code Gui}. Exists from {@link Phase#GUI}. */
    public KronoGui krono() {
        return require(Phase.GUI, "the clock", krono);
    }

    /**
     * The window's title bar, built by the framework — place its {@link TitleBar#node()} in the tree.
     *
     * <p>Exists from {@link Phase#GUI}, so the application can put it in its layout at {@code TREE} like any
     * other node. Until {@code ATTACH} it is a working bar against {@code WindowControls.NONE}; the framework
     * hands it the real controls and its instruments once the window exists, which is the seam
     * {@code automation.md} §7 insists on — <i>"a native window cannot photograph itself... only GuiApp owns a
     * window's render bundle, so only GuiApp can make working controls, and nothing else is allowed to
     * try."</i>
     *
     * <p><b>The framework builds it; the application says how it looks and where it goes.</b> Chrome placement
     * is the framework's so that a screenshot instrument means the same thing in every window. Everything about
     * its appearance comes from the application's own {@link Appearance#theme()}.
     *
     * <p>Absent when the application asked for {@link dev.vexelray.os.Decorations#SYSTEM} — there is no
     * application-drawn bar to own — and asking then is an error rather than a null.
     */
    public TitleBar titleBar() {
        if (titleBar == null && !appearance.drawsOwnFrame()) {
            throw new IllegalStateException(
                    "no framework title bar: this application asked for SYSTEM decorations");
        }
        return require(Phase.GUI, "the title bar", titleBar);
    }

    /** Window placement memory. Exists from {@link Phase#WINDOW}. */
    public WindowMemory memory() {
        return require(Phase.WINDOW, "window memory", memory);
    }

    /**
     * The window and the GPU device. Exists from {@link Phase#WINDOW}, and is the main-thread boundary —
     * anything reached through this must be touched on the main thread only.
     */
    public GuiApp app() {
        return require(Phase.WINDOW, "the window", app);
    }

    // ---- set by VexelApplication as each phase opens ----------------------------------------------------

    void phase(Phase phase) {
        this.phase = phase;
    }

    void settings(Settings settings) {
        this.settings = settings;
    }

    void gui(Gui gui) {
        this.gui = gui;
    }

    void krono(KronoGui krono) {
        this.krono = krono;
    }

    void memory(WindowMemory memory) {
        this.memory = memory;
    }

    void titleBar(TitleBar titleBar) {
        this.titleBar = titleBar;
    }

    void app(GuiApp app) {
        this.app = app;
    }

    Pacing pacing() {
        return pacing;
    }

    private <T> T require(Phase from, String what, T value) {
        if (value == null) {
            throw new IllegalStateException(
                    what + " does not exist until phase " + from + "; this is " + phase);
        }
        return value;
    }

    private void require(Phase from, String what) {
        if (phase.compareTo(from) < 0) {
            throw new IllegalStateException(what + " may only be registered from phase " + from
                    + " onwards; this is " + phase);
        }
    }
}

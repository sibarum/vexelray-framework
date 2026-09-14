package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.DeadlineSource;
import dev.vexelray.framework.core.Disposer;
import dev.vexelray.framework.core.FrameHooks;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Pacing;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.framework.core.WakeSource;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.CloseRequest;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Atchung;

/**
 * What the framework has built so far, and where the wiring hands things back.
 *
 * <p>Passed to one {@link Wiring} method per {@link Phase}. Deliberately not a bean registry: every accessor
 * here is a typed method returning one known type, so generated code reads {@code shell.app()} and is checked
 * by javac. There is no {@code get(Class)}, no name lookup, and nothing to configure — a service locator with a
 * map would reintroduce at runtime exactly the failure the processor exists to move to compile time.
 *
 * <p><b>Accessors throw before their phase.</b> {@link #app()} does not exist until {@link Phase#WINDOW}, and
 * asking early gets a message naming the phase rather than a {@code NullPointerException} thirty frames later.
 * Generated wiring will not be able to reach that state, because the processor rejects a backwards dependency —
 * so these checks are there for hand-written wiring and for the framework's own mistakes, which is the honest
 * description of a backstop.
 *
 * <p><b>Today they are not a backstop, they are the only check there is.</b> Every wiring on this stack is
 * hand-written and the processor is unwritten, so the phase rule is enforced here, at startup, by an
 * {@code IllegalStateException} — one step to the right of where this repo's own rule puts it, that <i>a
 * compile error beats a startup error beats a runtime error</i>. That is worth knowing in both directions: it
 * is why these messages name the phase and the thing asked for rather than simply failing, and it is why the
 * class of mistake they catch is an argument for the processor rather than evidence that one is unnecessary.
 */
public final class Shell {

    private final Launch launch;
    private final AppInfo info;
    private final FrameHooks hooks = new FrameHooks();
    private final Pacing pacing = new Pacing();
    private final Disposer disposer = new Disposer();
    private final Atchung bus = Atchung.create();

    private Appearance appearance = Appearance.DEFAULT;
    private Settings settings;
    private Gui gui;
    private KronoGui krono;
    private WindowMemory memory;
    private GuiApp app;
    private TitleBar titleBar;
    private ClipboardBackend clipboard;
    private Modals dialogs;
    private boolean closeGateSet;
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

    /**
     * <b>The application's bus</b> — one for the whole application, rather than one per {@code Gui}.
     *
     * <p>This is the first thing the concurrency model needs, and the thing that was not true before it
     * existed. {@code new Gui()} is {@code this(Atchung.create())}, so every tree used to arrive carrying a
     * fabric of its own: a calculator, which looks like a one-window application, ran two — the framework's
     * and the dialogs' — and nothing published on one could be heard on the other. {@code Gui(Atchung)}'s own
     * javadoc names the intent: <i>"hand in the same bus the application uses so input publishers, widgets,
     * and workers all meet the framework on one fabric."</i>
     *
     * <p><b>Available in every phase</b>, unlike almost everything else here, and not as a convenience: a
     * fabric is what the phases are built <em>on</em> rather than something one of them builds. A component
     * constructed in {@link Phase#MODEL} — before there is a {@code Gui} at all — is exactly the case the
     * component model is heading for, and it cannot be made to depend on a bus that appears two phases later.
     *
     * <p><b>Owned rather than accepted.</b> The framework creates it instead of taking one from the
     * application. An application with a bus already bridged to a peer is a real case and would want the
     * other arrangement, but taking one today means a parameter on every entry point for a case nobody has
     * yet; the seam to add when somebody does is an overload of {@code run}, not a change here.
     *
     * <p><b>What this is not, yet.</b> One bus is not one thread. {@code Gui} still makes a cached worker pool
     * of its own whatever it is handed, and nothing here places a component on a thread or gives it a mailbox
     * — see <i>the concurrency model</i> in {@code docs/architecture.md}. What is true today is that there is
     * one fabric to place them on, which is the part that could not be added later without moving everything
     * already built on top of it.
     */
    public Atchung bus() {
        return bus;
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

    /**
     * Be asked before the main window closes — the seam an application with unsaved work needs.
     *
     * <p>Closing the main window is quitting, so this is also how an application refuses to exit. The handler
     * runs on the handler executor and may answer the {@link CloseRequest} at its leisure, from any thread;
     * until it does, the window stays open and fully live, which is what lets the answer come from a dialog.
     *
     * <p><b>The framework installs no gate of its own</b>, and that is the decision rather than an omission:
     * the default has to be that closing closes. A framework that interposed anything here would be deciding,
     * for every application, that quitting is a question — and most applications have nothing to lose. What
     * the framework owns is the <em>place</em> the answer is given, and the dialog it is given in
     * ({@link #dialogs()}).
     *
     * <p>{@link Phase#ATTACH} onwards, because there is no window to be asked about before that. Once only:
     * {@code GuiApp} holds a single handler, so a second registration would silently replace the first — and
     * in an application with two things worth guarding, the one replaced is as likely as not the one that knew
     * about the unsaved documents.
     */
    public Shell onClose(java.util.function.Consumer<CloseRequest> gate) {
        require(Phase.ATTACH, "a close gate");
        if (closeGateSet) {
            throw new IllegalStateException(
                    "a close gate is already registered; GuiApp holds one, so a second would replace it");
        }
        closeGateSet = true;
        app.onCloseRequest(gate);
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

    /**
     * The OS clipboard, already installed on the main {@code Gui} — here so that an application's
     * <em>other</em> windows can be bound to the same one.
     *
     * <p><b>This accessor exists because of one specific silent bug.</b> A clipboard belongs to a {@code Gui},
     * not to an application, and the text editor binds three of them — the editor, the file tree and the
     * terminal — with the reason written on the loop: <i>"copy out of the terminal's prompt has to reach the
     * same place copy out of a tab does."</i> A window that is forgotten is a window where Ctrl+C does nothing
     * and reports nothing.
     *
     * <p>The framework cannot close that on its own. Both of the editor's other windows own a {@code Gui} from
     * construction, long before any native window exists, and {@code GuiApp.input} — the only per-window seam
     * there is — fires at window creation. So the framework binds the one {@code Gui} it built and hands the
     * backend over for the rest:
     *
     * {@snippet :
     * for (Gui window : files.windows()) {
     *     shell.clipboard().installOn(window);
     * }
     * }
     *
     * <p>Never null, and every method on it is a no-op where there is no backend — see
     * {@link ClipboardBackend}. So an application binds its windows without asking whether there is anything
     * to bind them to.
     */
    public ClipboardBackend clipboard() {
        return require(Phase.ATTACH, "the clipboard", clipboard);
    }

    /**
     * The application's dialogs, installed by the framework so that {@code Modals.show(...)} answers from
     * anywhere without an application having to remember to install them first.
     *
     * <p>Exists from {@link Phase#ATTACH}: a dialog is a real OS window owned by the main window, so there is
     * nothing to own one before then. Registered for shutdown, because <i>"an application that is closing
     * should not be held up by a question nobody is left to answer."</i>
     *
     * <p>Most applications never name this — they call the static {@code Modals.show}, {@code info} and
     * {@code confirm} from wherever the question arises, which is the whole shape of that class. It is here for
     * the one that wants to ask whether a dialog is up.
     */
    public Modals dialogs() {
        return require(Phase.ATTACH, "the dialogs", dialogs);
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

    void clipboard(ClipboardBackend clipboard) {
        this.clipboard = clipboard;
    }

    void dialogs(Modals dialogs) {
        this.dialogs = dialogs;
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

package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.DeadlineSource;
import dev.vexelray.framework.core.Disposer;
import dev.vexelray.framework.core.FrameHooks;
import dev.vexelray.framework.core.Lanes;
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
 * Generated wiring cannot reach that state, because every phase in it was inferred and the processor rejects a
 * part that needs something later than the framework takes it back — so these checks are there for hand-written
 * wiring and for the framework's own mistakes, which is the honest description of a backstop.
 *
 * <p>For a hand-written wiring they are still the only check there is: the phase rule is enforced here, at
 * startup, one step to the right of where this repo's own rule puts it, that <i>a compile error beats a startup
 * error beats a runtime error</i>. It is why these messages name the phase and the thing asked for rather than
 * simply failing.
 *
 * <p><b>What the wiring hands back.</b> Three of the framework's defaults are replaced by handing one back —
 * {@link #appearance(Appearance)}, {@link #input(InputBackend)} and {@link #clipboard(ClipboardBackend)} — each
 * accepted up to the phase before the framework reaches for its own, and each the framework's own answer when
 * nothing is. A generated wiring calls them for a {@code @Provides} method returning that type, which is the whole
 * of <i>"overriding one is a {@code @Provides} method returning that type"</i>.
 */
public final class Shell {

    private final Launch launch;
    private final AppInfo info;
    private final FrameHooks hooks = new FrameHooks();
    private final Pacing pacing = new Pacing();
    private final Disposer disposer = new Disposer();
    private final Atchung bus = Atchung.create();
    private final PointerLock pointerLock = new PointerLock();
    private final Lanes lanes;
    /** Components placed but not yet started. See {@link #place} for why those are two different moments. */
    private final java.util.List<Placement> placements = new java.util.ArrayList<>();

    private Appearance appearance = Appearance.DEFAULT;
    private Settings settings;
    private Gui gui;
    private KronoGui krono;
    private WindowMemory memory;
    private GuiApp app;
    private TitleBar titleBar;
    private InputBackend input;
    private ClipboardBackend clipboard;
    private Modals dialogs;
    private boolean closeGateSet;
    private Phase phase = Phase.CONFIG;

    Shell(Launch launch, AppInfo info) {
        this(launch, info, new Lanes());
    }

    Shell(Launch launch, AppInfo info, Lanes lanes) {
        this.launch = launch;
        this.info = info;
        this.lanes = lanes;
        // First registration, so it is the last thing closed: a lane outlives every tree presented on it, and
        // the whole point of the container owning the threads is that a window closing does not take them.
        disposer.register(lanes);
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
     * <p>One fabric, and — since {@link #lanes()} — one set of threads to place work on it from. The two
     * together are what a component needs: somewhere to publish, and somewhere to run.
     */
    public Atchung bus() {
        return bus;
    }

    /**
     * <b>The application's threads</b> — the handler lane, the offload lane, and the component threads.
     *
     * <p>The second of the two things the concurrency model needed, and the one that was not true until the
     * pool upstream stopped being a field initializer. {@code Gui} used to build a {@code newCachedThreadPool}
     * whatever it was handed, so passing an executor redirected input handlers and left the pool standing: an
     * application's thread count was a property of how many trees it happened to hold rather than of anything
     * it decided, and placement could not be decided in the wiring because there was nothing to decide it
     * <em>with</em>.
     *
     * <p><b>Available in every phase</b>, for the same reason the bus is: a lane is what the phases are built
     * on rather than something one of them builds. A component placed in {@link Phase#MODEL}, before there is
     * a {@code Gui} at all, is exactly the case this is heading for.
     *
     * <p><b>Owned rather than accepted</b>, again like the bus. An application that already has an executor it
     * means to share is a real case and would want the other arrangement; the seam to add when somebody has
     * one is an overload of {@code run}, not a change here.
     *
     * <p>Closed last, after every tree presented on it — registered with the {@link #disposer()} before
     * anything else exists, which is what makes that ordering structural rather than remembered.
     */
    public Lanes lanes() {
        return lanes;
    }

    /**
     * <b>Place a component on a thread of its own</b>, with its mailboxes and its wake — the seam the whole
     * concurrency model is for.
     *
     * <p>Returns a {@link Placement} with nothing running on it: give it its mailboxes, and the framework
     * starts it once every component is constructed. Start order is distinct from construction order because a
     * mailbox must not pump before its publishers exist, and that is a rule the container keeps rather than one
     * a wiring is trusted to remember:
     *
     * {@snippet :
     * Placement compose = shell.place("compose")
     *         .subscribe(EDITS, this::composeNow, 1, Backpressure.COALESCE_LATEST);
     * }
     *
     * <p><b>Available in every phase</b>, like the bus and the lanes it is built from. A component in
     * {@link Phase#MODEL} — what the application knows, before there is anything to draw it with — is the case
     * this is most obviously for, and it exists two phases before there is a {@code Gui}.
     *
     * <p><b>The wake comes with it, and there is nothing to call.</b> Every placement is registered as a
     * {@code WakeSource} when it starts, and wakes the loop itself after any drain that delivered something.
     * A wake an application has to remember is a window which is responsive except for the interactions that
     * happened to arrive that way — a bug this stack has already paid for twice, the second time through a
     * method on this very seam that was there to be called and could therefore be missed.
     *
     * <p>Closed in reverse placement order at shutdown, drain then stop, before the lanes themselves go.
     *
     * @param name what the component is called — on its thread and in whatever reports on it later
     */
    public Placement place(String name) {
        Placement placement = new Placement(name, bus, lanes);
        placements.add(placement);
        disposer.register(placement);
        return placement;
    }

    /**
     * <b>The pointer lock</b> — what carries {@code gui.dragLocksPointer(node, true)} onto the device, so that
     * a drag meaning a displacement keeps turning instead of stopping at the window edge.
     *
     * <p><b>There is nothing to switch on here, and that is the point.</b> A tree declares which of its nodes
     * want the lock, in the place the node is built, through {@code Gui}'s own API — that declaration is
     * upstream and this framework does not duplicate it. What was missing was anyone carrying it out: the
     * dispatcher has been firing {@code Gui.onPointerLock} into an unset sink on every stack that has one, and
     * {@code vexelray-designer}'s viewport has been asking for a held pointer since it was written and never
     * getting it. The framework installs the sink for every window it opens, so a viewport works because it
     * said what it was, not because its application remembered a line.
     *
     * <p>So this accessor is for the application that wants to <em>tune</em> the carrying-out — the capture
     * mode, how far the pointer travels before the cursor is hidden, or refusing the lock outright:
     *
     * {@snippet :
     * shell.pointerLock().mode(PointerLockMode.RECENTER);   // no raw-input plumbing on this platform
     * }
     *
     * <p><b>Available in every phase</b>, like the bus and the lanes, and configurable in any of them before
     * the window exists — it is built with the container and installed at {@link Phase#ATTACH}, so a wiring can
     * settle it in {@code CONFIG} beside the appearance without a phase rule to remember. See
     * {@link PointerLock} for the four visual discontinuities a naive carrying-out produces and what is done
     * about each; the short version is that the cursor is not hidden until the pointer has actually moved, it
     * is not warped, and it does not stay hidden across an alt-tab.
     */
    public PointerLock pointerLock() {
        return pointerLock;
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
     * <b>This application's input backend, in place of the framework's</b> — a recorded session instead of a
     * device, a scripted one in a test, a backend for a platform tactroller does not cover.
     *
     * <p><b>Accepted up to {@link Phase#TREE}</b>, because the framework reaches for the backend at the start of
     * {@link Phase#WINDOW}: it is opened before the window exists and attached once the window does, and a backend
     * handed back in {@code WINDOW} would arrive after the framework had already opened its own. The attach, the
     * bridge onto the bus and the frame's pump stay the framework's, so a replacement supplies a device and not the
     * order it is driven in.
     *
     * <p><b>Taken over</b>: closed at shutdown with everything else, so whoever built it does not also close it.
     * {@code null} is no opinion, and leaves the framework's. Once only, because a second would leave the first
     * open with nothing driving it.
     *
     * <p>Built where the wiring builds it, which for a provider taking nothing is {@code CONFIG} — so a replacement
     * is open in a headless {@link VexelApplication#tree} run too, where the framework's own never is.
     */
    public Shell input(InputBackend input) {
        refuseAfter(Phase.TREE, "an input backend", "the framework opens its own at the start of WINDOW");
        if (input == null) {
            return this;
        }
        if (this.input != null) {
            throw new IllegalStateException("an input backend is already registered; a second would leave the "
                    + "first open with nothing driving it");
        }
        this.input = disposer.register(input);
        return this;
    }

    /**
     * <b>This application's clipboard, in place of the framework's</b> — one confined to the application for a
     * kiosk, or a recording one in a test.
     *
     * <p><b>Accepted up to {@link Phase#WINDOW}</b>, because the framework installs the clipboard on the main
     * {@code Gui} at the start of {@link Phase#ATTACH}. Taken over, {@code null} and once only, on the terms of
     * {@link #input(InputBackend)}. Read back through {@link #clipboard()} from {@code ATTACH}, installed.
     */
    public Shell clipboard(ClipboardBackend clipboard) {
        refuseAfter(Phase.WINDOW, "a clipboard", "the framework installs one at the start of ATTACH");
        if (clipboard == null) {
            return this;
        }
        if (this.clipboard != null) {
            throw new IllegalStateException("a clipboard is already registered; a second would leave the first "
                    + "open and installed on nothing");
        }
        this.clipboard = disposer.register(clipboard);
        return this;
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

    // --- @Setting, resolved -------------------------------------------------------------------------------------

    /**
     * <b>One {@code @Setting}, resolved by the one precedence</b> {@code @Setting}'s own Javadoc states: an
     * explicit {@code --key=value}, then a {@code -Dkey=value} property, then the user's settings file, then
     * {@code def}. Highest wins, and each level is more specific to this launch than the one beneath it.
     *
     * <p>What generated wiring calls for a {@code String} parameter; the overloads below are the same order for
     * the other types {@code Settings} has an accessor for, chosen by the type of the default. <b>A malformed
     * value falls through</b> to the next source rather than failing, which is {@code Settings}' own policy kept
     * rather than reinvented — an application must not refuse to launch over a preferences file, and a typo in a
     * flag is not a better reason.
     */
    public String setting(String key, String def) {
        for (String given : given(key)) {
            return given;
        }
        return settings().getString(key, def);
    }

    /** {@link #setting(String, String)}, for an {@code int}. */
    public int setting(String key, int def) {
        for (String given : given(key)) {
            try {
                return Integer.parseInt(given.trim());
            } catch (NumberFormatException malformed) {
                // Falls through to the next source, as a missing one does.
            }
        }
        return settings().getInt(key, def);
    }

    /** {@link #setting(String, String)}, for a {@code long}. */
    public long setting(String key, long def) {
        for (String given : given(key)) {
            try {
                return Long.parseLong(given.trim());
            } catch (NumberFormatException malformed) {
                // Falls through to the next source, as a missing one does.
            }
        }
        return settings().getLong(key, def);
    }

    /** {@link #setting(String, String)}, for a {@code float}. */
    public float setting(String key, float def) {
        for (String given : given(key)) {
            try {
                return Float.parseFloat(given.trim());
            } catch (NumberFormatException malformed) {
                // Falls through to the next source, as a missing one does.
            }
        }
        return settings().getFloat(key, def);
    }

    /**
     * {@link #setting(String, String)}, for a {@code boolean}. Only {@code true} and {@code false} count as
     * given; anything else falls through rather than being read as false, which is what {@code parseBoolean}
     * would quietly do to a {@code --fast=yes}.
     */
    public boolean setting(String key, boolean def) {
        for (String given : given(key)) {
            String g = given.trim();
            if (g.equalsIgnoreCase("true") || g.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(g);
            }
        }
        return settings().getBoolean(key, def);
    }

    /**
     * {@link #setting(String, String)}, for a list. A list given on the command line or as a property is split on
     * commas; the file keeps its own separator. There is no default: an absent list is empty.
     */
    public java.util.List<String> settingList(String key) {
        for (String given : given(key)) {
            return given.isBlank() ? java.util.List.of() : java.util.List.of(given.split(",", -1));
        }
        return settings().getList(key);
    }

    /**
     * The sources more specific than the file, most specific first: the command line, then the system property.
     * A list rather than the first one present, so a value that does not parse at one level falls through to the
     * next level rather than straight to the file. Startup code, not frame code — the allocation is once per key.
     */
    private java.util.List<String> given(String key) {
        java.util.List<String> out = new java.util.ArrayList<>(2);
        String flag = launch.override(key);
        if (flag != null) {
            out.add(flag);
        }
        String property = System.getProperty(key);
        if (property != null) {
            out.add(property);
        }
        return out;
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
     * to bind them to. The application's own, if it handed one back through {@link #clipboard(ClipboardBackend)}.
     */
    public ClipboardBackend clipboard() {
        // By phase rather than by null: one handed back earlier exists before it is installed.
        return require(Phase.ATTACH, "the clipboard", phase.compareTo(Phase.ATTACH) < 0 ? null : clipboard);
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

    /**
     * Start every placed component, each with its wake already connected.
     *
     * <p>Called once, after the wiring's {@code ATTACH} has returned and before the loop begins — so every
     * publisher a component might hear from exists, and every component's own thread starts at the same known
     * moment rather than wherever its constructor happened to sit.
     *
     * <p>The wake is connected <em>before</em> the thread starts, because a component whose first drain
     * publishes something would otherwise announce it into a wake that is still a no-op. There is no wake in a
     * headless {@code tree} run, where there is no loop to nudge; a placement with none simply does not nudge
     * one, which is the same shape as the accessor that refuses before its phase.
     */
    void startComponents() {
        for (Placement placement : placements) {
            if (app != null) {
                placement.onWake(app::postWake);
            }
            placement.start();
        }
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

    /**
     * The input backend the application handed back, or the framework's, opened now. Called once, at the start of
     * {@link Phase#WINDOW} — which is why {@link #input(InputBackend)} refuses from there on.
     */
    InputBackend openInput() {
        if (input == null) {
            input = disposer.register(InputBackend.open());
        }
        return input;
    }

    /** As {@link #openInput}, for the clipboard, at the start of {@link Phase#ATTACH}. */
    ClipboardBackend openClipboard() {
        if (clipboard == null) {
            clipboard = disposer.register(ClipboardBackend.open());
        }
        return clipboard;
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

    private void refuseAfter(Phase last, String what, String because) {
        if (phase.compareTo(last) > 0) {
            throw new IllegalStateException(what + " must be registered by " + last + ", because " + because
                    + "; this is " + phase);
        }
    }

    private void require(Phase from, String what) {
        if (phase.compareTo(from) < 0) {
            throw new IllegalStateException(what + " may only be registered from phase " + from
                    + " onwards; this is " + phase);
        }
    }
}

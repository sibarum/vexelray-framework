package dev.vexelray.framework.processor;

import dev.vexelray.framework.core.Phase;

import java.util.List;

/**
 * What the framework supplies to generated wiring, and what it takes back — named by string, because the processor
 * cannot depend on {@code -shell} without putting the graphics stack on every application's processor path.
 *
 * <p><b>A string that must be spelled right is the thing this repo refuses elsewhere</b>, so these are held
 * against the code they name: {@code FrameworkTableTest} in {@code -shell} reads this table and checks every
 * accessor exists on {@code Shell} with the return type written here, and every phase method on {@code Wiring}.
 * A renamed accessor fails that test rather than a generated wiring.
 */
final class Framework {

    static final String SHELL = "dev.vexelray.framework.shell.Shell";
    static final String WIRING = "dev.vexelray.framework.shell.Wiring";
    static final String APP_INFO = "dev.vexelray.framework.shell.AppInfo";
    static final String BUILD_INFO = "dev.vexelray.framework.shell.BuildInfo";
    /** What generated code calls to report a {@code @Provides} method that returned null; see {@code Absences}. */
    static final String ABSENCES = "dev.vexelray.framework.shell.Absences";
    /** What the generated wiring calls to subscribe a {@code @Job} to its topic on the offload lane. */
    static final String JOBS = "dev.vexelray.framework.shell.Jobs";
    /** A component's inbox on the logic lane: a component takes one in its constructor, and nothing else may. */
    static final String INBOX = "sibarum.atchung.Inbox";
    /** What the generated wiring calls to put a component on the logic lane: its inbox, mailboxes and senders. */
    static final String INBOXES = "dev.vexelray.framework.shell.Inboxes";
    /** What the generated wiring calls to give a {@code @View} its mailboxes on the GUI thread; see {@code Views}. */
    static final String VIEWS = "dev.vexelray.framework.shell.Views";
    static final String APPEARANCE ="dev.vexelray.framework.shell.Appearance";
    static final String INPUT = "dev.vexelray.framework.shell.InputBackend";
    static final String CLIPBOARD = "dev.vexelray.framework.shell.ClipboardBackend";
    static final String LIVENESS = "dev.vexelray.framework.shell.LivenessPolicy";
    static final String DIALOGS = "dev.vexelray.framework.shell.Dialogs";
    /** The application's fonts, as its own build baked them: vexelray-text's type, taken back as it is. */
    static final String FONTS = "dev.vexelray.text.FontSet";
    /**
     * The one framework value that is a component's and not the main thread's, though it is Vulkan: the device's
     * compute queue of its own, lent to the one component that takes it (T3.1). The generated wiring asks for it
     * ({@code Wiring.computeQueue}) exactly when a component does.
     */
    static final String COMPUTE_QUEUE = "dev.vexelray.gui.core.app.ComputeQueue";
    /**
     * The framework's question before a window closes, and its answer. A window's close is asked of the application
     * ({@code Wiring.answersClose}) exactly when a part hears the first, and {@link Channels} requires one part to and
     * something to send the second.
     */
    static final String CLOSE_REQUESTED = "dev.vexelray.framework.core.CloseRequested";
    static final String CLOSE_ANSWER = "dev.vexelray.framework.core.CloseAnswer";

    /**
     * A value the framework owns and hands out through a {@code Shell} accessor, from the phase that accessor
     * refuses before.
     *
     * @param type       the value's type
     * @param accessor   the {@code Shell} method returning it, or empty for the shell itself
     * @param phase      the first phase an application part is handed it, which is what a part taking it is
     *                   inferred into. Usually the phase {@code Shell} has it from; the {@code Gui} and what reaches
     *                   it are the exception, held by the shell from {@code GUI}, where the framework configures
     *                   them, and handed to parts from {@code TREE}
     * @param onMain     whether it is the main thread's: T2.2 and T3.1 read this, since the framework's own types
     *                   cannot carry {@code @MainThread} without their repos learning this one exists
     */
    record Root(String type, String accessor, Phase phase, boolean onMain) {

        String expression() {
            return accessor.isEmpty() ? "shell" : "shell." + accessor + "()";
        }
    }

    /**
     * The roots. <b>The shell itself is an {@link Phase#ATTACH} value</b>: it reaches everything, so a provider
     * taking it could touch anything, and the only phase where everything exists is the last one.
     *
     * <p><b>Not here, since 0.2</b> ({@link #REFUSED}): the bus, {@code Senders}, the lanes, the frame hooks and the
     * disposer. A part sends by taking a {@code Sender<T>} per topic, which the wiring builds (T4.5); what runs where,
     * and what runs in a frame, is the framework's (T2.5, T7.1); and a part that holds a resource is closed by being
     * {@code AutoCloseable}. The shell still reaches them, and leaves an application's reach in phase 5.
     */
    static final List<Root> ROOTS = List.of(
            new Root(SHELL, "", Phase.ATTACH, false),
            new Root("dev.vexelray.framework.core.Launch", "launch", Phase.CONFIG, false),
            new Root(APP_INFO, "info", Phase.CONFIG, false),
            // Hands no code to anyone: it publishes, and a subscriber hears it on its own lane (T7.2).
            new Root("dev.vexelray.framework.core.Timer", "timer", Phase.CONFIG, false),
            new Root("dev.vexelray.framework.shell.PointerLock", "pointerLock", Phase.CONFIG, false),
            // From the first phase and not the main thread's: what it hands out holds a question until there is a
            // window to ask it in, and asks whichever dialogs end up answering.
            new Root(DIALOGS, "dialogs", Phase.CONFIG, false),
            new Root("dev.vexelray.gui.core.app.Settings", "settings", Phase.CONFIG, false),
            // The Gui is configured in GUI -- the look, the clock, the minimum size, the zoom range -- and handed to
            // the application at TREE, so a part that takes it is built after all of that by ordering, not by care.
            // The other two move with it because each reaches the Gui: Window.gui() through Windows.main(), and the
            // title bar is a widget on it. Left in GUI, either would be a way to the Gui one phase early, and GUI would
            // still hold widget-building parts. KronoGui is not here at all: it runs code on the GUI thread over time,
            // so it is the host's, and a view states a tempo (vexelray-gui's Timing) that the tree's clock runs (T7.2).
            new Root("dev.vexelray.gui.core.Gui", "gui", Phase.TREE, false),
            new Root("dev.vexelray.gui.widget.TitleBar", "titleBar", Phase.TREE, false),
            // Not the main thread's, though a window is: every command on a Window only posts, and the framework
            // claims the window itself, so a recipe holding one is not coloured by a GuiApp it never took.
            new Root("dev.vexelray.framework.shell.Windows", "windows", Phase.TREE, false),
            // "Vulkan, the window and present stay on the main thread" -- vexelray-gui/CLAUDE.md.
            new Root("dev.vexelray.gui.core.app.GuiApp", "app", Phase.WINDOW, true),
            // The exception to it, and the only one: a queue nothing on the main thread submits to.
            new Root(COMPUTE_QUEUE, "computeQueue", Phase.WINDOW, false),
            new Root(CLIPBOARD, "clipboard", Phase.ATTACH, false));

    /**
     * A default the framework replaces with the application's own when one is provided: the generated wiring hands
     * the provided value to {@code Shell.<setter>}, and the framework uses it instead of its own.
     *
     * <p><b>Not a {@code @Default} provider in a starter</b>, though that was the plan, because the framework's
     * answer has to exist without any wiring at all — a hand-written one is still supported, and it would otherwise
     * start with no input. So the default lives in {@code -shell}, next to the setter, and one definition of it is
     * what there is rather than a starter's and a fallback's that have to agree.
     *
     * @param type   the value's type
     * @param setter the {@code Shell} method taking it back
     * @param last   the last phase it is accepted in: the framework reaches for its own in the next
     * @param why    what the framework does with it at the start of the next phase — the reason for {@code last}
     * @param owned  whether the shell closes it, so the wiring must not register it a second time
     */
    record HandBack(String type, String setter, Phase last, String why, boolean owned) {
    }

    static final List<HandBack> HAND_BACKS = List.of(
            new HandBack(APPEARANCE, "appearance", Phase.CONFIG, "The look is applied before the first widget is"
                    + " constructed — \"a role resolves at the moment a widget writes a prop\"", false),
            new HandBack(FONTS, "fonts", Phase.CONFIG, "The fonts are settled beside the look, before anything"
                    + " measures text, and uploaded with the window at the start of WINDOW", false),
            new HandBack(INPUT, "input", Phase.TREE, "The input backend is opened at the start of WINDOW, before"
                    + " the window exists, and attached once it does", true),
            new HandBack(CLIPBOARD, "clipboard", Phase.WINDOW, "The clipboard is installed on the Gui at the start"
                    + " of ATTACH", true),
            new HandBack(LIVENESS, "liveness", Phase.ATTACH, "The watchdog starts with the components, after"
                    + " ATTACH, and reads the policy once when it does", false),
            new HandBack(DIALOGS, "dialogs", Phase.WINDOW, "The framework puts its own dialogs in place at the"
                    + " start of ATTACH", false));

    /** The hand-back for a type, by qualified name, or {@code null}. */
    static HandBack handBack(String qualifiedName) {
        for (HandBack h : HAND_BACKS) {
            if (h.type().equals(qualifiedName)) {
                return h;
            }
        }
        return null;
    }

    /**
     * What an application part may not take, by qualified name, and why: the framework's own machinery for sending,
     * running and closing, which 0.1 handed out and 0.2 keeps (ROADMAP phase 4). {@code java.util.concurrent}'s
     * executors are refused beside these, by {@code Graph}, since no table lists every one.
     */
    static final java.util.Map<String, String> REFUSED = java.util.Map.of(
            "sibarum.atchung.Atchung", "T4.5: the raw bus is not handed to an application part, because a send"
                    + " through it is one no declaration shows. Take a Sender<T> for each topic it sends on, and the"
                    + " wiring builds it",
            "dev.vexelray.framework.shell.Senders", "T4.5: Senders hands out a sender for any topic, which no"
                    + " declaration shows. Take a Sender<T> for each topic it sends on, and the wiring builds it",
            "dev.vexelray.framework.core.Lanes", "T2.5: the lanes are the framework's. Work that blocks is a @Job,"
                    + " a component that needs a core declares @Component(lane = ...), and nothing else picks a thread",
            "dev.vexelray.framework.core.FrameHooks", "T7.1: no application code runs in the frame. Work over time"
                    + " belongs to a @Component hearing a Timer, and what changes on screen to a view",
            "dev.vexelray.framework.core.Disposer", "the disposer runs code at shutdown that nothing declares. A"
                    + " part that holds a resource implements AutoCloseable, and the wiring closes it");

    /** The framework's own packages: a starter there (the automation driver, the host's) may still take the shell. */
    static final String OWN = "dev.vexelray.framework.";

    /**
     * Why an application part may not take the {@link #SHELL}, though the framework's own starters may (ROADMAP
     * phase 5). It reaches everything: the frame hooks, the disposer, the wake source, the windows' commands, the
     * restart, every value in every phase, so a part holding it could run code anywhere and send anything with nothing
     * declared. What a part needs from it is a root of its own, or a message.
     */
    static final String SHELL_REFUSED = "the Shell is the framework's, and reaches everything it does: the frame"
            + " hooks, the disposer, every value in every phase. Take what is needed as a value of its own (Windows,"
            + " Dialogs, Settings, Launch, the Gui), send a message (WindowCommand, Ask, PickFile), or hear one: a"
            + " recipe that took the Shell to be built last can hear WindowOpened for the main window instead";

    /** The root for a type, by qualified name, or {@code null}. */
    static Root root(String qualifiedName) {
        for (Root r : ROOTS) {
            if (r.type().equals(qualifiedName)) {
                return r;
            }
        }
        return null;
    }

    /**
     * The {@code Wiring} method a phase's construction goes in. The six construction phases' names, lower-cased,
     * are exactly {@code Wiring}'s six methods; {@link Phase#RUN} constructs nothing.
     */
    static String method(Phase phase) {
        return phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    private Framework() {
    }
}

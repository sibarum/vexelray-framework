package dev.vexelray.framework.automation;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.gui.automation.Capability;
import dev.vexelray.gui.automation.Sockets;
import dev.vexelray.gui.automation.Timeline;
import dev.vexelray.gui.automation.Windows;
import dev.vexelray.gui.automation.window.AutomationWindow;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.app.LoopCounters;
import dev.vexelray.gui.inspector.link.InspectorLink;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import sibarum.probe.Log;

/**
 * The automation sockets, wired once instead of in every application: remote control and diagnostics, switched on by
 * the launch or by hand while the application runs.
 *
 * <p>This method was near-identical in {@code calculator-vexel-demo}, {@code text-editor-vexel-demo},
 * {@code vexelray-designer} and the {@code mainframe-template} scaffold, down to the same comment about a
 * debugging port that must not stop an application launching. It is the clearest remaining case of the
 * duplication this framework exists to remove.
 *
 * <p><b>Off unless requested, loopback-only when it is, and never out of sight.</b> Remote control hands whatever
 * connects full control of the application's input, so it is off by default
 * ({@code vexelray-gui/docs/reference/automation.md} §5). Requested means a launch setting — {@code --automation}
 * for both on one port, {@code --automation.control} and {@code --automation.diagnostics} for each, or
 * {@value #ENVIRONMENT} in an agent's environment — or the automation button this puts in the title bar, beside the
 * key bindings, which switches either while the application runs. Release builds carry it too, since macros and
 * agents use the same hand a person does; what keeps that honest is that the button is also each socket's lamp,
 * showing whether it is open and whether anything is, or has been, connected. {@link #AVAILABLE} takes either socket,
 * or both, out of a run altogether.
 *
 * <p><b>A module of its own rather than part of {@code -shell}.</b> Absorbing it into the shell would put
 * {@code vexelray-gui-automation} on every application's compile path, and a listening socket linked into every
 * native binary is the wrong trade for a framework whose selling point is what it does not include. An
 * application that wants to be driven depends on this module and names {@link AutomationStarter} in its
 * {@code @VexelApp}; one that does not never links it, and has no button either. A hand-written wiring calls
 * {@link #open} itself.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Driver implements AutoCloseable {

    private static final Log LOG = Log.of("framework.automation");

    /**
     * The setting for both on one socket: {@code off} (the default), {@code on} for the default port, or a port
     * number. One of {@code Launch.FRAMEWORK_KEYS}, as are {@link Sockets#CONTROL_SETTING} and
     * {@link Sockets#DIAGNOSTICS_SETTING}, so {@code --automation=7654} parses without the application declaring it.
     */
    public static final String SETTING = Sockets.SETTING;

    /**
     * The flight recorder's ring size in rows: {@code --recorder=<rows>}, handed on as the {@code -Drecorder}
     * property the recorder reads ({@code Recorder.ROWS_PROPERTY}). Also one of {@code Launch.FRAMEWORK_KEYS}.
     */
    public static final String RECORDER = "recorder";

    /**
     * The environment variable an agent's shell sets so every application it launches opens its socket without a
     * flag: {@code VEXELRAY_AUTOMATION=0} for a free port. Read after the flag and the property, and only when neither
     * names any automation setting ({@link #resolver}). A release edition has no automation module, so a shipped binary
     * ignores it. Where a run's socket landed is in its discovery file, {@code ~/.vexelray/automation/<app>-<pid>.auto},
     * which {@code Sockets} writes while it listens and {@code ottermate} reads with no {@code --port}.
     */
    public static final String ENVIRONMENT = "VEXELRAY_AUTOMATION";

    /**
     * What this run may switch on at all: {@code all} (the default), {@code none}, {@code control} or
     * {@code diagnostics} ({@link Sockets#available(String)}). With {@code none} there is no automation button and
     * nothing can switch a socket on, by launch or by hand. One of {@code Launch.FRAMEWORK_KEYS}.
     */
    public static final String AVAILABLE = Sockets.AVAILABLE_SETTING;

    /**
     * {@link #AVAILABLE} from the environment, read after the flag and the property: {@code
     * VEXELRAY_AUTOMATION_AVAILABLE=none} keeps every application started from that environment unreachable.
     */
    public static final String AVAILABLE_ENVIRONMENT = "VEXELRAY_AUTOMATION_AVAILABLE";

    private final Shell shell;
    private final Set<Capability> available;
    /** Built on first need — the launch asking, or the button clicked — because it needs the window. */
    private volatile Sockets sockets;
    /** The title bar's button, redrawn after every change to the sockets, once they are built. */
    private final List<Runnable> lampWatchers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean closed;

    private Driver(Shell shell, Set<Capability> available) {
        this.shell = shell;
        this.available = available;
    }

    /**
     * Put the automation button in the title bar, and bind what this launch asked for. Registered for shutdown.
     *
     * <p>Call from {@code Wiring.attach} — the sockets need the window's real controls, and those do not exist until
     * the window does; the framework has given the bar its own tools by then, and the button joins them rather than
     * replacing them. The value returned is never null; {@link #bound()} says whether anything is listening.
     *
     * <p>Each setting is resolved from the command line first and the system property second, so
     * {@code --automation=7654} works alongside the {@code -Dautomation=7654} the existing exec configurations pass.
     * That ordering is {@code Setting}'s: a flag is more specific to this launch than a property. Last, and only when
     * neither names any of the three settings, {@value #ENVIRONMENT} stands in for {@code automation}, so an agent's
     * shell opens every application's socket without a flag ({@link #resolver}).
     *
     * <p>{@link #AVAILABLE} is read first, the same way, with {@value #AVAILABLE_ENVIRONMENT} last. A socket the launch
     * asks for that the run does not offer is not opened, and that is said; a value that is not one of the words offers
     * nothing, since whoever wrote it meant to shut something.
     */
    public static Driver open(Shell shell) {
        return open(shell, System::getProperty, System::getenv);
    }

    /** {@link #open(Shell)} with the property and environment lookups handed in, so a test sets neither globally. */
    static Driver open(Shell shell, Function<String, String> properties, Function<String, String> environment) {
        Driver driver = new Driver(shell, available(shell.launch()::override, properties, environment));
        driver.button();
        Function<String, String> asked = resolver(shell.launch()::override, properties, environment);
        if (!Sockets.anyRequested(asked)) {
            return driver;
        }
        String want = SETTING + "=" + asked.apply(SETTING) + " " + Sockets.CONTROL_SETTING + "="
                + asked.apply(Sockets.CONTROL_SETTING) + " " + Sockets.DIAGNOSTICS_SETTING + "="
                + asked.apply(Sockets.DIAGNOSTICS_SETTING)
                + (fromEnvironment(shell.launch()::override, properties) ? " (from " + ENVIRONMENT + ")" : "");
        try {
            // Every value read before anything is built, so a port that is not a number is said without reaching
            // for a window that a launch which failed this early may not have.
            for (String key : List.of(SETTING, Sockets.CONTROL_SETTING, Sockets.DIAGNOSTICS_SETTING)) {
                Sockets.requested(asked.apply(key));
            }
            // And a socket the run does not offer is refused here, for the same reason: nothing is opened.
            for (Capability c : Sockets.asked(asked)) {
                if (!driver.available.contains(c)) {
                    throw new IllegalStateException(Sockets.refusal(c, driver.available));
                }
            }
            driver.sockets().enableRequested(asked);
            int port = driver.port();
            if (port >= 0) {
                // Whoever is looking at the "automation:" line wants to drive the application, and the client is in a
                // sibling checkout, usually not on PATH: without a pointer, the next step is writing a socket client
                // from scratch, which has happened. Not a second "automation:" line, so --launch's match stays put.
                System.out.println("  drive it with ottermate --port " + port + "  (vexelray-gui/"
                        + "vexelray-gui-automation-cli; build it there with: mvn -pl vexelray-gui-automation-cli install)");
            }
            LOG.info("automation: {}", driver.sockets().describe());
        } catch (java.io.IOException | NumberFormatException | IllegalStateException e) {
            // An application that will not start because a debugging port was busy is a worse outcome than one
            // nobody can drive. The button still switches it on, at another port.
            Diagnostics.dropped("Driver.open", "the automation socket this launch asked for",
                    e.getMessage() + "; '" + want + "' bound nothing it had not bound before"
                            + (driver.sockets == null ? "" : " (" + driver.sockets.describe() + ")"));
        }
        return driver;
    }

    /** What this run offers: {@link #AVAILABLE} from the flag, the property, then {@value #AVAILABLE_ENVIRONMENT}. */
    static Set<Capability> available(Function<String, String> flags, Function<String, String> properties,
                                     Function<String, String> environment) {
        String v = flags.apply(AVAILABLE);
        if (v == null) {
            v = properties.apply(AVAILABLE);
        }
        if (v == null) {
            v = environment.apply(AVAILABLE_ENVIRONMENT);
        }
        try {
            return Sockets.available(v);
        } catch (IllegalArgumentException e) {
            Diagnostics.dropped("Driver.open", "the automation sockets", e.getMessage() + "; offering none");
            return EnumSet.noneOf(Capability.class);
        }
    }

    /** What this run may switch on: neither, one, or both. */
    public Set<Capability> available() {
        return available;
    }

    /**
     * How each setting is read: the flag, then the property, and for {@link #SETTING} alone, the environment's
     * {@value #ENVIRONMENT} last (mainframe {@code docs/stack-env.md} §1).
     *
     * <p><b>The environment counts only when the launch named no socket at all.</b> {@code --automation} puts both
     * capabilities on one socket and wins over the other two ({@code Sockets.enableRequested}), so an environment
     * value filling it in beside {@code --automation.control=7000} would override the launch's own, more specific,
     * flag. The environment is the agent's shell saying "every application", and a launch that says anything about
     * its sockets has said something more specific.
     */
    static Function<String, String> resolver(Function<String, String> flags, Function<String, String> properties,
                                             Function<String, String> environment) {
        Function<String, String> launch = key -> {
            String v = flags.apply(key);
            return v != null ? v : properties.apply(key);
        };
        if (!fromEnvironment(flags, properties)) {
            return launch;
        }
        String fallback = environment.apply(ENVIRONMENT);
        return key -> {
            String v = launch.apply(key);
            return v == null && key.equals(SETTING) && fallback != null && !fallback.isBlank() ? fallback.trim() : v;
        };
    }

    /** Whether no flag or property names any automation setting, so {@value #ENVIRONMENT} is the one asked. */
    private static boolean fromEnvironment(Function<String, String> flags, Function<String, String> properties) {
        for (String key : new String[] {SETTING, Sockets.CONTROL_SETTING, Sockets.DIAGNOSTICS_SETTING}) {
            if (flags.apply(key) != null || properties.apply(key) != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * The sockets themselves, built on first use: for an application that switches them from a control of its own,
     * or watches them. Needs the window, so not before {@code ATTACH}.
     */
    public synchronized Sockets sockets() {
        if (sockets == null) {
            if (closed) {
                throw new IllegalStateException("the driver has been closed");
            }
            // The flight recorder sizes its ring from the property when Windows builds it, so a --recorder flag is
            // handed on first, the same flag-over-property order as the settings.
            String rows = shell.launch().override(RECORDER);
            if (rows != null) {
                System.setProperty(RECORDER, rows);
            }
            // The GuiApp is the loop's counters: without them every recorder row is frame 0, and frames and why
            // answer err. app() throws before the window exists, which Wiring.attach is after.
            LoopCounters counters = Objects.requireNonNull(shell.app(), "the GuiApp the recorder stamps frames from");
            // The clock goes with the Gui, so settle waits out a transition as well as the frame loop, with the
            // reasons, so a settle that never goes quiet says which loop is running. The sampled form, because the
            // sockets answer on threads of their own.
            Windows windows = new Windows(shell.windows()::views,
                    Timeline.of(shell.krono()::quiescentAtLastTick, shell.krono()::whyBusyAtLastTick), counters);
            // Named after @VexelApp, so the discovery file and hello say "calculator" rather than the main class,
            // and ottermate --app calculator finds it (vexelray-gui docs/plans/discovery.md).
            sockets = new Sockets(windows, shell.info().name(), shell.info().title(), System.out, available)
                    // The Inspector's link beside whichever socket serves diagnostics (vexelray-gui
                    // docs/plans/flight-recorder.md section 6). -Dautomation.link=off leaves it out.
                    .attach(Capability.DIAGNOSTICS, server -> InspectorLink.start(server, windows));
            // The button was put up before the sockets existed; from here on it follows them.
            sockets.watch(s -> lampWatchers.forEach(Runnable::run));
            lampWatchers.forEach(Runnable::run);
        }
        return sockets;
    }

    /**
     * Where {@code c} stands: the sockets', or off (unavailable) before they are built. Read without this object's
     * lock, since it is asked from whatever thread changed the sockets, which may be holding theirs.
     */
    private Sockets.State state(Capability c) {
        Sockets s = sockets;
        if (s != null) {
            return s.state(c);
        }
        return available.contains(c) ? Sockets.State.OFF : Sockets.State.UNAVAILABLE;
    }

    /** Whether a socket is actually listening, for either capability. */
    public synchronized boolean bound() {
        return sockets != null && (sockets.on(Capability.CONTROL) || sockets.on(Capability.DIAGNOSTICS));
    }

    /** The control socket's port; the diagnostics one's while control is off; {@code -1} when nothing listens. */
    public synchronized int port() {
        if (sockets == null) {
            return -1;
        }
        int control = sockets.port(Capability.CONTROL);
        return control >= 0 ? control : sockets.port(Capability.DIAGNOSTICS);
    }

    /**
     * The automation button, after whatever the bar already shows — the framework's tools, the key bindings — in a
     * window whose frame the GUI draws. A native frame has no bar to put it in, and a run that offers neither socket
     * ({@link #AVAILABLE}) has nothing for it to switch.
     *
     * <p>The button is the sockets' lamps ({@code AutomationWindow}): beside the plug, each socket shows whether its
     * port is open and whether anything is, or was this session, connected to it, so whether anything can drive or
     * read this application is in its title bar at a glance.
     */
    private void button() {
        if (!shell.appearance().drawsOwnFrame() || available.isEmpty()) {
            return;
        }
        List<WindowInstrument> tools = new ArrayList<>(shell.titleBar().instruments());
        tools.add(WindowInstrument.automation("Remote control and diagnostics",
                controls -> AutomationWindow.window(shell.app(), shell.gui(), sockets()).show(),
                () -> AutomationWindow.lamps(shell.gui(), state(Capability.CONTROL), state(Capability.DIAGNOSTICS)),
                new WindowInstrument.Live() {
                    @Override
                    public Runnable watch(Runnable changed) {
                        lampWatchers.add(changed);
                        return () -> lampWatchers.remove(changed);
                    }

                    @Override
                    public String tooltip() {
                        Sockets s = sockets;
                        return s == null ? null : AutomationWindow.describe(s);
                    }
                }));
        shell.titleBar().instruments(List.copyOf(tools));
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (sockets != null) {
            sockets.close();
        }
    }
}

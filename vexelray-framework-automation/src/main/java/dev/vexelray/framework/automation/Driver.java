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
import java.util.List;
import java.util.Objects;
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
 * <p><b>Off unless requested, and loopback-only when it is.</b> This hands anyone who can reach it full control
 * of the application's input, so it is a debugging instrument and not a service
 * ({@code vexelray-gui/docs/reference/automation.md} §5). Requested means a launch setting — {@code --automation}
 * for both on one port, {@code --automation.control} and {@code --automation.diagnostics} for each — or the
 * automation button this puts in the title bar, beside the key bindings, which switches either while the
 * application runs.
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

    private final Shell shell;
    /** Built on first need — the launch asking, or the button clicked — because it needs the window. */
    private Sockets sockets;
    private boolean closed;

    private Driver(Shell shell) {
        this.shell = shell;
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
     * That ordering is {@code Setting}'s: a flag is more specific to this launch than a property.
     */
    public static Driver open(Shell shell) {
        Driver driver = new Driver(shell);
        driver.button();
        Function<String, String> asked = key -> {
            String v = shell.launch().override(key);
            return v != null ? v : System.getProperty(key);
        };
        if (!Sockets.anyRequested(asked)) {
            return driver;
        }
        String want = SETTING + "=" + asked.apply(SETTING) + " " + Sockets.CONTROL_SETTING + "="
                + asked.apply(Sockets.CONTROL_SETTING) + " " + Sockets.DIAGNOSTICS_SETTING + "="
                + asked.apply(Sockets.DIAGNOSTICS_SETTING);
        try {
            // Every value read before anything is built, so a port that is not a number is said without reaching
            // for a window that a launch which failed this early may not have.
            for (String key : List.of(SETTING, Sockets.CONTROL_SETTING, Sockets.DIAGNOSTICS_SETTING)) {
                Sockets.requested(asked.apply(key));
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
        } catch (java.io.IOException | NumberFormatException e) {
            // An application that will not start because a debugging port was busy is a worse outcome than one
            // nobody can drive. The button still switches it on, at another port.
            Diagnostics.dropped("Driver.open", "the automation socket this launch asked for",
                    e.getMessage() + "; '" + want + "' bound nothing it had not bound before"
                            + (driver.sockets == null ? "" : " (" + driver.sockets.describe() + ")"));
        }
        return driver;
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
            sockets = new Sockets(windows, shell.info().name(), shell.info().title())
                    // The Inspector's link beside whichever socket serves diagnostics (vexelray-gui
                    // docs/plans/flight-recorder.md section 6). -Dautomation.link=off leaves it out.
                    .attach(Capability.DIAGNOSTICS, server -> InspectorLink.start(server, windows));
        }
        return sockets;
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
     * window whose frame the GUI draws. A native frame has no bar to put it in.
     */
    private void button() {
        if (!shell.appearance().drawsOwnFrame()) {
            return;
        }
        List<WindowInstrument> tools = new ArrayList<>(shell.titleBar().instruments());
        tools.add(WindowInstrument.automation("Remote control and diagnostics",
                controls -> AutomationWindow.window(shell.app(), shell.gui(), sockets()).show()));
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

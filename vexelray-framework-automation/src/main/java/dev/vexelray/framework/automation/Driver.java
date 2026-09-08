package dev.vexelray.framework.automation;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.gui.automation.Automation;
import dev.vexelray.gui.automation.AutomationServer;

/**
 * The driving socket, wired once instead of in every application.
 *
 * <p>This method is near-identical in {@code calculator-vexel-demo}, {@code text-editor-vexel-demo},
 * {@code vexelray-designer} and the {@code mainframe-template} scaffold, down to the same comment about a
 * debugging port that must not stop an application launching. It is the clearest remaining case of the
 * duplication this framework exists to remove.
 *
 * <p><b>Off unless requested, and loopback-only when it is.</b> This hands anyone who can reach it full control
 * of the application's input, so it is a debugging instrument and not a service
 * ({@code vexelray-gui/docs/automation.md} §5).
 *
 * <p><b>A module of its own rather than part of {@code -shell}.</b> Absorbing it into the shell would put
 * {@code vexelray-gui-automation} on every application's compile path, and a listening socket linked into every
 * native binary is the wrong trade for a framework whose selling point is what it does not include. An
 * application that wants to be driven depends on this module; one that does not never links it. Once the
 * annotation processor exists this becomes a starter guarded by {@code @ConditionalOnType}, and the dependency
 * decides itself.
 */
public final class Driver implements AutoCloseable {

    /**
     * The setting that turns it on: {@code off} (the default), {@code on} for the default port, or a port
     * number. Already one of {@code Launch.FRAMEWORK_KEYS}, so {@code --automation=7654} parses without the
     * application declaring it.
     */
    public static final String SETTING = "automation";

    private final AutomationServer server;

    private Driver(AutomationServer server) {
        this.server = server;
    }

    /**
     * Bind the socket if this launch asked for one, and register it for shutdown.
     *
     * <p>Call from {@code Wiring.attach} — the driver needs real window controls, and those do not exist until
     * the window does. The value returned is never null; {@link #bound()} says whether anything is listening.
     *
     * <p>Resolved from the command line first and the system property second, so {@code --automation=7654}
     * works alongside the {@code -Dautomation=7654} the existing exec configurations pass. That ordering is
     * {@code Setting}'s: a flag is more specific to this launch than a property.
     */
    public static Driver open(Shell shell) {
        String want = shell.launch().override(SETTING);
        if (want == null) {
            want = System.getProperty(SETTING, "off");
        }
        if (want.isBlank() || want.equals("off") || want.equals("false")) {
            return new Driver(null);
        }
        try {
            int port = want.equals("on") || want.equals("true")
                    ? AutomationServer.DEFAULT_PORT
                    : Integer.parseInt(want);
            AutomationServer server = AutomationServer.start(
                    new Automation(shell.gui(), shell.app().controls()), port);
            System.out.println("automation: localhost:" + server.port());
            return new Driver(server);
        } catch (java.io.IOException | NumberFormatException e) {
            // An application that will not start because a debugging port was busy is a worse outcome than one
            // nobody can drive.
            System.out.println("automation unavailable (" + e.getMessage() + "); running undriven");
            return new Driver(null);
        }
    }

    /** Whether a socket is actually listening. */
    public boolean bound() {
        return server != null;
    }

    /** The port, or {@code -1} when nothing is listening. */
    public int port() {
        return server == null ? -1 : server.port();
    }

    @Override
    public void close() {
        if (server != null) {
            server.close();
        }
    }
}

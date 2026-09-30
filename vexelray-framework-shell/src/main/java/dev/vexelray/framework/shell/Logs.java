package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import sibarum.probe.Logging;

import java.util.HashMap;
import java.util.Map;

/**
 * Where an application's logging is configured: once, at the edge, before anything has a reason to log.
 *
 * <p>The framework does this for every application so that none of them has to, and so that the answer is the
 * same on every application on the desk: the mode comes from what the launch says, the level from {@code --log},
 * and everything else from the defaults {@code sibarum.probe.LogConfig} describes. An application that wants
 * something different sets a property ({@code -Dlog.console=off}); it does not configure logging itself.
 *
 * <p><b>Launch flags first, because they are the only thing known this early.</b> {@code --automation=0} is an
 * argument, not a system property, so {@code Logging} cannot see it unless it is handed over — and the mode it
 * implies (loud, with the probe on) has to be settled before the first record, and before the probe class
 * loads, because the probe decides once, in a static initialiser.
 */
final class Logs {

    private Logs() {
    }

    /** Configure logging for {@code info}'s application from what this launch said. */
    static void start(AppInfo info, Launch launch) {
        Map<String, String> settings = new HashMap<>();
        String automation = launch.override("automation");
        if (automation != null) {
            settings.put("automation", automation);
        }
        String level = launch.override("log");
        if (level != null) {
            settings.put("log.level", level);
        }
        Logging.configure(info.name(), null, settings);
    }
}

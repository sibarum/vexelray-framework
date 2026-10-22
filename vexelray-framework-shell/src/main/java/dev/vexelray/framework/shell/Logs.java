package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.os.NativePlatform;
import sibarum.probe.LogConfig;
import sibarum.probe.Logging;

import java.nio.file.Path;
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
        // Either socket alone is a driven run too: diagnostics asked for at launch is the one run that most needs
        // the probe this mode arms, since nothing can arm it later.
        for (String key : new String[] {"automation", "automation.control", "automation.diagnostics"}) {
            String automation = launch.override(key);
            if (automation != null) {
                settings.put(key, automation);
            }
        }
        String level = launch.override("log");
        if (level != null) {
            settings.put("log.level", level);
        }
        Logging.configure(info.name(), null, settings);
        keepStandardError();
    }

    /**
     * Give standard error somewhere to go when the process has none, and read in what an earlier one left there.
     *
     * <p>The installed build is a GUI-subsystem program, so it starts with no standard error. Before this, what was
     * only ever written there was lost, and a crash left no trace: a native fault's report from the image's runtime,
     * the stack {@code Fatal.HALT} prints after {@link Faults} has logged its one line. Now {@code System.err}
     * becomes log records, and native standard error goes to a file of this process's own, which the next start
     * reads into the log ({@link Logging#recoverStandardError}).
     */
    private static void keepStandardError() {
        LogConfig config = Logging.config();
        if (!config.fileEnabled()) {
            return;
        }
        Logging.recoverStandardError();
        Path file = config.stderrFile(ProcessHandle.current().pid());
        try {
            if (NativePlatform.current().redirectStandardError(file)) {
                Logging.captureStandardError(file);
            }
        } catch (RuntimeException e) {
            Diagnostics.dropped("Logs.keepStandardError", "standard error kept in the log", e.toString());
        }
    }
}

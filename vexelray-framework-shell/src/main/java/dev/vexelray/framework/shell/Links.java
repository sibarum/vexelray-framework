package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Open;
import dev.vexelray.framework.core.Opened;
import dev.vexelray.os.NativePlatform;
import sibarum.probe.Log;

/**
 * The framework's answer to {@link Open}: a target handed to the desktop, a web address to the browser, a file to
 * whatever opens it. What the About window's links send, what an application's parts send, and the only place the
 * framework starts another program on the person's behalf.
 *
 * <p>A job the framework runs for every application, with no recipe or {@code Core} entry: it is wired by the shell
 * before the application's own wiring ({@link #serve}), on the job lane, since the opener blocks while it starts. The
 * opener is the platform's ({@link NativePlatform#open}), since which program that is differs by OS and the stack keeps
 * every such difference in an OS module.
 */
final class Links {

    private static final Log LOG = Log.of("framework.links");

    private Links() {
    }

    /** Answer every {@link Open} on {@code shell}'s bus with an {@link Opened}, on the job lane. */
    static void serve(Shell shell) {
        Jobs.reply(shell, "dev.vexelray.framework.shell.Links", Open.TOPIC, Links::open, Opened.TOPIC);
    }

    /** Open {@code request}'s target; what went wrong, if anything, is in the answer, and logged. */
    static Opened open(Open request) {
        String target = request.target();
        if (target.isBlank()) {
            return new Opened(target, "there is nothing to open");
        }
        try {
            if (NativePlatform.current().open(target)) {
                return Opened.done(target);
            }
            LOG.warn("could not open {}: this platform has no opener, or it would not start", target);
            return new Opened(target, "this desktop has no opener for it, or it would not start");
        } catch (RuntimeException e) {
            LOG.warn("could not open " + target, e);
            String m = e.getMessage();
            return new Opened(target, m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
        }
    }
}

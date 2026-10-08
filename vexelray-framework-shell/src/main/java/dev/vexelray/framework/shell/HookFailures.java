package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.FrameStage;
import sibarum.probe.Log;

/**
 * Says so, once and with its stack, when a frame hook threw and was stopped.
 *
 * <p>{@code FrameHooks} contains the failure and this reports it. A contained hook is the one outcome that must
 * never be quiet: the application keeps running without whatever the hook kept up to date, and a log line is
 * the only place that says which. So it is an {@code ERROR} with the stack, in the log a run leaves behind, and
 * recorded through {@link Diagnostics} so a test can prove it fired. The stack names the hook; the stage and the
 * index say where it sat in the walk.
 *
 * <p>Found by the calculator: closing its settings window left {@code WindowMemory.poll} reading the bounds of a
 * window that no longer existed, and the frame loop went down with the user's tape.
 */
final class HookFailures {

    private static final Log LOG = Log.of("framework.frame");

    private HookFailures() {
    }

    static void failed(FrameStage stage, int index, RuntimeException thrown) {
        LOG.error("a frame hook threw in FrameStage." + stage + " (#" + index + " in the walk) and has been"
                + " stopped for the rest of the run; the application continues without it", thrown);
        Diagnostics.dropped("VexelApplication.frame/" + stage + "#" + index,
                "the FrameStage." + stage + " hook at #" + index,
                "it threw " + thrown + " and will not run again; the stack is in the log");
    }
}

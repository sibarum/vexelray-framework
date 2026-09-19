package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.FrameStage;

import java.util.concurrent.TimeUnit;

/**
 * Says so when something held the main thread long enough for the window to stop.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The frame loop parks when there is nothing to do and wakes when there is, so a window that has stopped
 * drawing looks exactly like a window with nothing to draw. There is no exception, no log line and no dropped
 * frame counter — the application is simply not there for a third of a second, and the report that comes back
 * is <i>"it feels slow sometimes"</i>. That is the same class of failure {@link Diagnostics} was built for,
 * one level up: nothing throws, nothing warns, and what the user sees is plausible enough to be read as a
 * taste decision about performance rather than as a bug with an address.
 *
 * <p>It is also a failure this stack keeps having. The text editor read and wrote files on this thread, and
 * the only reason anybody found out was a port that went looking. A framework whose answer to <i>why did my
 * window freeze</i> is "read your code again" has not finished the job it started when it took ownership of
 * the threads.
 *
 * <h2>What it can and cannot see</h2>
 *
 * <p>This watches the two places an application's own code runs on the main thread: the {@link FrameStage}
 * hooks, and — upstream, in {@code GuiApp} — the posted task queue. Both are places where writing an ordinary
 * blocking call is easy and nothing objects.
 *
 * <p>It is deliberately <b>not</b> a profiler. A slow layout or an expensive frame is a performance question
 * and belongs to {@code atchung-probe}, which is built for it and off unless asked. This answers a narrower
 * and more urgent question — <i>did somebody block this thread</i> — and so it is always on, costs two clock
 * reads per stage, and reports in whole hundreds of milliseconds rather than in percentiles.
 */
final class Stalls {

    /**
     * How long a stage may hold the main thread before this says something.
     *
     * <p>A frame's budget is about 16ms, so this is fifteen of them: long enough that the window has visibly
     * stopped and nobody would call it jitter, and long enough that a genuinely heavy first frame — device,
     * swapchain, the first font atlas — does not cry wolf on the way up. Erring high is deliberate. A warning
     * that fires when nothing is wrong is one that gets filtered out, and then the one that mattered goes with
     * it.
     *
     * <p><b>Turn it down to take a census.</b> {@code -Dvexelray.stall.ms=16} reports anything that misses a
     * single frame, which is the setting for finding out what blocks rather than for being told that something
     * did. That is a real question this framework has open — the handler lane cannot be bounded until somebody
     * knows what still blocks on it — and it is better answered by running the applications than by reading
     * them.
     */
    private static final long DEFAULT_MS = 250;

    private Stalls() {
    }

    /** The configured threshold in nanoseconds, or zero if it has been switched off entirely. */
    static long thresholdNanos() {
        long ms = Long.getLong("vexelray.stall.ms", DEFAULT_MS);
        return ms <= 0 ? 0L : TimeUnit.MILLISECONDS.toNanos(ms);
    }

    /**
     * Report that {@code stage} held the main thread for {@code nanos}.
     *
     * <p>Keyed by stage, so each one says this once — these run every frame, and a stall that repeats every
     * frame would otherwise bury the first and most useful report under thousands of copies of itself.
     */
    static void stalled(FrameStage stage, long nanos) {
        Diagnostics.dropped("VexelApplication.frame/" + stage,
                "every frame owed while FrameStage." + stage + " ran",
                TimeUnit.NANOSECONDS.toMillis(nanos) + " ms on the main thread, so the window did not draw"
                        + " and took no input for that long — " + stage.whenSlow() + ". Blocking work belongs"
                        + " on the offload lane (GuiApp.offload, which hands the result back here) or in a"
                        + " component of its own (Shell.place)");
    }
}

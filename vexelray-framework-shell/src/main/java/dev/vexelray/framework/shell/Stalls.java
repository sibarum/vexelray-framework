package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.core.FrameHooks;
import dev.vexelray.gui.nfd.Nfd;

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
 * <p>Since 0.2 no application code runs on the main thread (T7.1), so what this times is the framework's own: the
 * {@link FrameStage} hooks (input, the clock, window memory, and the logic's tick in a {@code --logic=main} run, the
 * one place an application's code is on this thread), and — upstream, in {@code GuiApp} — the posted task queue,
 * where window operations land. A slow one there is the framework's bug, or the logic's in single-thread mode, and
 * still worth a line.
 *
 * <p>It is deliberately <b>not</b> a profiler. A slow layout or an expensive frame is a performance question
 * and belongs to {@code atchung-probe}, which is built for it and off unless asked. This answers a narrower
 * and more urgent question — <i>did somebody block this thread</i> — and so it is always on, costs two clock
 * reads per stage, and reports in whole hundreds of milliseconds rather than in percentiles.
 *
 * <h2>Half of the main thread's watch</h2>
 *
 * <p>This half reports a stage that was <em>slow</em> and finished: a line in the log, and the loop goes on. The
 * other half is the watchdog's ({@code Watchdog.watchMain}, through {@code MainThreadBeat}), which notices a main
 * thread that has <em>not</em> finished — no frame begun for the liveness policy's threshold, while not parked and
 * not in a dialog — which this cannot, since it measures a stage only once it returns. That one goes to the
 * application's {@link LivenessPolicy}, whose default halts. {@link #servingOs} is what keeps it from calling a person
 * a wedge.
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
     * did. It is better answered by running the applications than by reading them.
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
                        + " in a @Job, whose reply comes back as a message, and logic in a @Component, which"
                        + " runs on the logic lane");
    }

    /**
     * The window's message pump, by method: {@code NativeWindow.pumpEvents} and {@code NativeWindow.waitEvents}, as
     * every platform's window spells them. Named by string because a stack frame names a method by string;
     * {@code StallsTest} holds them to the interface, so a rename fails the build rather than this.
     */
    static final String PUMP = "pumpEvents";
    static final String WAIT = "waitEvents";

    /**
     * Whether the main thread, whose stack this is, is serving the operating system rather than stuck in a frame: its
     * innermost frame that says either is the window's message pump or a native file dialog, and not the frame's own
     * work ({@code FrameHooks}). For the watchdog, about a main thread that has made no progress for the threshold.
     *
     * <p><b>Why the pump means a person.</b> The operating system runs a modal loop of its own from inside the pump
     * while a window is dragged or resized, while its system menu is open, and through a drag and drop; each holds the
     * loop for as long as a person does, and each is still answering the OS, so the window is not hung. A posted task
     * or a frame's update that never returns has no pump above it, since the pump returned before either ran.
     *
     * <p><b>Errs towards yes.</b> A stack with no frames (a runtime that keeps none) is taken for serving, and so is a
     * wedge inside a callback the pump made — input, a window message — since both have the pump below them. Missing a
     * wedge leaves the window as frozen as it was; calling a person one would halt it under them, and the check that
     * refuses safe code is the one this repo does not write.
     */
    static boolean servingOs(StackTraceElement[] stack) {
        if (stack.length == 0) {
            return true;
        }
        String frameWork = FrameHooks.class.getName();
        String dialogs = Nfd.class.getPackageName() + ".";
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.equals(frameWork)) {
                return false;
            }
            if (cls.startsWith(dialogs)) {
                return true;
            }
            String method = frame.getMethodName();
            if (method.equals(PUMP) || method.equals(WAIT)) {
                return true;
            }
        }
        return false;
    }
}

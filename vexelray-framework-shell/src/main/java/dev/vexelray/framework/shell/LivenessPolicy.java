package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.core.Lanes;
import dev.vexelray.framework.core.Stalled;

import java.time.Duration;

/**
 * What this application does when a component stops draining, or its main thread stops, and how long it waits before
 * deciding so.
 *
 * <p><b>The guarantee is the framework's and the policy is the application's.</b> A wedged component never
 * stops the window responding, the user always being able to close, or the application ending itself
 * ({@code docs/v1.md}, <i>the liveness guarantee</i>); that is frozen. What is <em>done</em> about a wedge is not,
 * so it is handed back like the look and the input backend: a {@code @Provides} method returning this type.
 * Every method has a default, so a replacement overrides only what it means to change:
 *
 * {@snippet :
 * @Provides LivenessPolicy liveness() {
 *     return new LivenessPolicy() {
 *         public Duration stallThreshold() { return Duration.ofSeconds(30); }
 *         public void onStall(Stall stall, Context context) { context.restart(); }
 *     };
 * }
 * }
 *
 * <p><b>The defaults are not contractual.</b> Today a stall is reported and the application exits, which is the
 * bus-fault precedent ({@code Faults}). The target is a main-thread fallback that offers the user a restart, and
 * a better default may replace this one; it may not weaken the guarantee, which is why the policy acts through a
 * {@link Context} rather than returning an enum — a new action is a new method there, and an existing policy
 * keeps compiling.
 *
 * <p><b>What a stall is.</b> A lane that has been inside <em>one delivery</em> for {@link #stallThreshold}. A
 * lane that is parked on an empty mailbox is idle and is never a stall; a component that loops, blocks or
 * deadlocks is. That is {@link #onStall}.
 *
 * <p><b>The main thread is told apart, because less can be done for it</b> ({@link #onMainThreadStall}). It stalls
 * when the frame loop has made no progress for {@link #mainThreadStallThreshold} while it was neither parked with
 * nothing to do nor holding a native dialog a person is answering, nor inside the operating system's own modal loop
 * (a window being dragged, its system menu). A wedged main thread is a wedged window: nothing can be shown, no close
 * gate can run, and no fallback on that thread can help, so what a policy can do there is a narrower
 * {@link MainThreadContext}, and the way out is a halt.
 *
 * <p><b>Exiting throws away unsaved work</b>, so the threshold is long and {@link Context#exit} goes through the
 * window's close gate, which is the application's bounded chance to save. Then {@link #exitGrace} is how long the
 * process is given before it is ended regardless.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public interface LivenessPolicy {

    /** The logic lane's name, which every component that declares no lane shares: {@code "logic"}. */
    String LOGIC_LANE = Logic.LANE;

    /** The job lane's name, where every {@code @Job} runs: {@code "job"}. Interrupting it reaches every job in flight. */
    String JOB_LANE = Lanes.JOB_LANE;

    /**
     * How long a lane may stay inside one delivery before it is a stall. Default ten seconds: long enough that
     * ordinary slow work is never called a wedge, short enough that a real one is not left to the user to
     * discover. Change it if it becomes a problem.
     */
    default Duration stallThreshold() {
        return Duration.ofSeconds(10);
    }

    /**
     * How long the main thread may go without beginning a frame, while not parked and not in a native dialog, before
     * it is a stall. Default {@link #stallThreshold}, so one setting moves both; separate because what follows by
     * default differs: a lane's stall exits through the close gate, a main thread's halts with no chance to save.
     */
    default Duration mainThreadStallThreshold() {
        return stallThreshold();
    }

    /**
     * How long an orderly shutdown may take, from the first thing being closed, before the process is ended with
     * {@code Runtime.halt}. Default ten seconds. This is the backstop that does not depend on any thread
     * cooperating, so it is a total bound and not a per-component one.
     */
    default Duration closeBound() {
        return Duration.ofSeconds(10);
    }

    /**
     * How long {@link Context#exit} and {@link Context#restart} wait for the window to close by itself — including for
     * a person answering a save prompt — before ending the process regardless. Default thirty seconds.
     */
    default Duration exitGrace() {
        return Duration.ofSeconds(30);
    }

    /**
     * A lane stalled. Called once per stalled delivery, on the watchdog's thread — which is on no lane, so it
     * runs even when the application's own threads do not. The watchdog publishes each stall as a
     * {@link Stalled} message, and this hears it directly on the bus; a part that only
     * wants to know hears the same message in its mailbox, once its own lane is free to.
     *
     * <p>The default reports it, naming the lane, and exits.
     */
    default void onStall(Stall stall, Context context) {
        Diagnostics.dropped("LivenessPolicy/" + stall.lane(), "the component lane " + stall.lane(),
                "it has been inside one delivery for " + stall.stalledFor().toSeconds() + " s, so nothing on that"
                        + " lane is running; the window is unaffected, but the application is exiting because"
                        + " the default policy does — override LivenessPolicy to do something else");
        context.exit();
    }

    /**
     * The main thread stalled: the window has drawn nothing and taken no input for
     * {@link Stall#stalledFor}, and not because it was idle or a person was in a dialog. Called once per stall, on the
     * watchdog's thread. {@link Stall#lane} is {@link Stalled#MAIN}. Never handed to {@link #onStall}, so a policy
     * written before the main thread was watched is not called for something it cannot act on.
     *
     * <p>The default reports it and halts, which is the contract's way out ({@code docs/v1.md}: a wedged main thread
     * is ended once the threshold passes, guaranteed and delayed). Unsaved work is lost: the close gate that would
     * have saved it runs on the very thread that is stuck.
     */
    default void onMainThreadStall(Stall stall, MainThreadContext context) {
        Diagnostics.dropped("LivenessPolicy/" + Stalled.MAIN, "the main thread, and with it the window",
                "it has made no progress for " + stall.stalledFor().toSeconds() + " s while not idle and not in a"
                        + " dialog, so the window has drawn nothing and taken no input; the application is being"
                        + " ended because a stuck main thread cannot run its own close — the stacks follow in the"
                        + " halt report");
        context.halt();
    }

    /** What happened: a lane, or {@link Stalled#MAIN}, and for how long it has been stuck. */
    record Stall(String lane, Duration stalledFor) {
    }

    /**
     * What a policy can do about a stalled lane. Safe from any thread.
     */
    interface Context {

        /**
         * End the application: ask the window to close, through its close gate, and end the process regardless
         * after {@link LivenessPolicy#exitGrace}.
         */
        void exit();

        /**
         * End the application as {@link #exit} does, close gate and grace included, and start it again from the same
         * command: the same program and arguments, environment and working directory, the new process waiting for this
         * one to finish exiting before it opens anything ({@link Relaunch}). The new process starts once the window
         * has closed, or on the way out if the grace runs out first, so a save prompt left open does not turn a
         * restart into an exit.
         *
         * <p><b>An exit, and nothing more, when this process cannot be started again</b> — the operating system does
         * not say how it was started ({@link Relaunch#unavailable}), or it is an automation run, which a harness
         * started and judges by its exit, and which would be left with a process it did not start and cannot reach.
         * Either is said in the log.
         *
         * <p>Not a restart of components in place: Java cannot stop a wedged thread, so the old one would be
         * abandoned and could wake later on stale state. A new process is what reclaims everything.
         *
         * <p>The default exits, for a context that cannot restart.
         */
        default void restart() {
            exit();
        }

        /**
         * Interrupt every thread in the named lane, including any its code created. A request and not a kill: it
         * wakes an interruptible blocking call and cannot stop a thread that never asks. A lane's name is the one its
         * stall reported: {@link LivenessPolicy#LOGIC_LANE} for every component that declares no lane (also reached by
         * {@code Component.DEFAULT_LANE}, which is what such a component's declaration says), a declared
         * {@code @Component(lane = ...)}, or {@link LivenessPolicy#JOB_LANE} for every job in flight.
         *
         * @return whether such a lane exists
         */
        boolean interrupt(String lane);
    }

    /**
     * What a policy can do about a stalled main thread: less than for a lane, because every orderly way out — a close
     * gate, a save prompt, a fallback window — runs on the thread that is stuck. Safe from any thread.
     */
    interface MainThreadContext {

        /**
         * End the process now, with {@code Runtime.halt}, writing every thread's stack first. No close gate runs and
         * nothing is saved.
         */
        void halt();

        /**
         * Start this process again from the same command, as {@link Context#restart} does, then halt this one as
         * {@link #halt} does: with no close gate, for the same reason. A {@link #halt} alone, said in the log, when the
         * process cannot be started again or is an automation run.
         */
        void restart();
    }
}

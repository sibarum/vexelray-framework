package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.Stability;

import java.time.Duration;

/**
 * What this application does when a component stops draining, and how long it waits before deciding so.
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
 * deadlocks is. Only components on a lane are watched — the main thread is not, because it legitimately blocks
 * for as long as a person takes in a native dialog.
 *
 * <p><b>Exiting throws away unsaved work</b>, so the threshold is long and {@link Context#exit} goes through the
 * window's close gate, which is the application's bounded chance to save. Then {@link #exitGrace} is how long the
 * process is given before it is ended regardless.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public interface LivenessPolicy {

    /**
     * How long a lane may stay inside one delivery before it is a stall. Default ten seconds: long enough that
     * ordinary slow work is never called a wedge, short enough that a real one is not left to the user to
     * discover. Change it if it becomes a problem.
     */
    default Duration stallThreshold() {
        return Duration.ofSeconds(10);
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
     * How long {@link Context#exit} waits for the window to close by itself — including for a person answering a
     * save prompt — before ending the process regardless. Default thirty seconds.
     */
    default Duration exitGrace() {
        return Duration.ofSeconds(30);
    }

    /**
     * A lane stalled. Called once per stalled delivery, on the watchdog's thread — which is on no lane, so it
     * runs even when the application's own threads do not.
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

    /** What happened: a lane, and for how long it has been inside a single delivery. */
    record Stall(String lane, Duration stalledFor) {
    }

    /**
     * What a policy can do about it. Safe from any thread.
     */
    interface Context {

        /**
         * End the application: ask the window to close, through its close gate, and end the process regardless
         * after {@link LivenessPolicy#exitGrace}.
         */
        void exit();

        /**
         * Interrupt every thread in the named lane, including any its code created. A request and not a kill: it
         * wakes an interruptible blocking call and cannot stop a thread that never asks. A lane's name is its
         * {@code @Component(lane = ...)}, or {@code "<default>"}.
         *
         * @return whether such a lane exists
         */
        boolean interrupt(String lane);
    }
}

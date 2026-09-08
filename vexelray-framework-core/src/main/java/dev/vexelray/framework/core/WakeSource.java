package dev.vexelray.framework.core;

/**
 * Something that can produce work while the loop is parked, and so needs a way to wake it.
 *
 * <p>The counterpart to {@link DeadlineSource}: a deadline is work the loop can predict, and a wake is work it
 * cannot. A worker thread mutating a node and a timeline posting a value are neither of them OS input, so
 * neither of them lands in the message queue the parked loop is waiting on — and the hand-written edge says
 * what happens if they are not connected: <i>"the wakes, without which the parking above is a hang rather than
 * a saving"</i>.
 *
 * <p>The framework hands each source the loop's wake, at the one phase where a loop exists to be woken. Which
 * is the substance of the seam: the two calls it replaces are unremarkable to write and catastrophic to omit,
 * and omitting one produces a window that is frozen only for the interactions that happened to arrive by that
 * path. The GUI's own record of this is that five missing wakes shipped past a green test suite, because a test
 * that draws its own frames cannot notice a wake that never came.
 */
public interface WakeSource {

    /**
     * Called once, during startup, with the runnable that wakes the frame loop.
     *
     * <p>The runnable is safe to call from any thread — that is its purpose — and is cheap enough to call
     * speculatively. Calling it when no frame is needed costs one frame; not calling it when one is needed
     * costs the application its responsiveness until something else happens to wake the loop.
     */
    void onWake(Runnable wake);
}

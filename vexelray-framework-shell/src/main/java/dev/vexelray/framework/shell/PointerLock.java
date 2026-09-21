package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.Gui;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.PointerLockMode;
import sibarum.tactroller.api.PointerState;
import sibarum.tactroller.api.Tactroller;

/**
 * Carries the GUI's pointer-lock intent onto the device — the other half of a seam that has had only one half
 * on this stack since it was written.
 *
 * <p>{@code Gui.dragLocksPointer(node, true)} declares that dragging a node holds the pointer for the length of
 * the gesture, and states plainly what it is for: <i>"the pointer stops travelling, the motion keeps arriving,
 * and a drag can turn something a dozen times over without running out of desk."</i> The dispatcher honours the
 * declaration and asks — {@code Gui.onPointerLock} is the sink it asks through, with the reason on it:
 * <i>"the framework can say what it wants of a pointer and cannot reach an OS to get it."</i>
 *
 * <p><b>Nothing on this stack was listening.</b> {@code vexelray-designer}'s viewport registers
 * {@code gui.dragLocksPointer(canvas, true)} — <i>"turning is a displacement, so the pointer is held for the
 * gesture"</i> — and no application, this framework included, ever installed the sink. So the intent was
 * stated, the dispatcher fired it, and the pointer was never held: the designer's camera drag runs out of desk
 * at the window edge, exactly as if the line had not been written. This class is the listener, and it is the
 * framework's to own for the same reason the clipboard and the wakes are — it is one call, it is invisible when
 * omitted, and every application would otherwise write it.
 *
 * <h2>A locked drag is where visual discontinuity comes from, so most of this class is about not causing one</h2>
 *
 * <p>Hiding and restoring the OS cursor is a sudden pixel change by construction, and the naive carrying-out —
 * lock when told, unlock when told — produces four of them. Each is handled here rather than left to the
 * application, because each is invisible in a screenshot and none of them throws.
 *
 * <p><b>1. The click that flickers.</b> The dispatcher asks for the lock on the drag's {@code START}, which
 * fires on the <em>press</em>, before any motion. So an ordinary click on a viewport — selecting an object,
 * dismissing something — hides the cursor on the way down and restores it on the way up, a blink of two or
 * three frames on a gesture that was never a drag. This class does not lock on being asked; it locks once the
 * pointer has actually travelled {@link #thresholdPx()}. A press that releases without moving never hides
 * anything. The distinction is {@code DragGesture}'s own, in its own words — <i>"distance decides whether it
 * was a drag"</i> — applied to the one question that is the device's rather than the gesture's.
 *
 * <p><b>2. The cursor that comes back somewhere else.</b> {@link PointerLockMode#RECENTER} works by warping the
 * cursor to a fixed centre point every drain, so on release it is restored at the centre of the window rather
 * than where the user pressed — a jump of however far they were from the middle. {@link PointerLockMode#RAW}
 * does not warp at all, so the cursor reappears exactly where it vanished and the gesture has no visible seam
 * at either end. That is why {@code RAW} is the default here, and it is worth saying that the designer's own
 * comment assumes the other one (<i>"warped back each frame"</i>) and would have shipped the jump.
 *
 * <p><b>3. The lurch on the first locked frame.</b> A lock that engages after this frame's input was drained
 * leaves the frame's motion to be read in the wrong mode, and the accumulated difference arrives as one large
 * delta — the camera snaps. {@code Fathom}, the stack's only hand-written mouselook, records both the ordering
 * and why it is enough: <i>"Reconcile the lock BEFORE pumping so this frame's snapshot drains in the right
 * mode. lockPointer(RAW) zeroes the backend accumulator, so toggling never yields a stray jump."</i> So
 * {@link #reconcile()} is called from inside {@link InputBackend#pump()}, ahead of the snapshot, and not
 * registered as a frame hook of its own — {@code FrameStage} says ordering within a stage is registration order
 * and <i>"depending on it is a mistake"</i>, so a second hook in {@code INPUT} would be a correctness rule
 * resting on the order two lines happen to sit in. One hook that does both cannot be registered wrongly.
 *
 * <p><b>4. The cursor left hidden over somebody else's window.</b> Alt-tabbing mid-drag with the lock held
 * leaves the pointer captured and invisible in another application. So the lock is released whenever focus is
 * lost and re-taken when it returns — without ending the drag, because {@code DragGesture} is explicit that
 * <i>"focus loss does not cancel a drag"</i>: motion and release are positional and keep arriving, and
 * cancelling would discard a gesture the user is in the middle of. Re-taking goes through the travel threshold
 * again, so clicking back into the window does not snatch the cursor before the user has moved it.
 *
 * <h2>Absence is a state</h2>
 *
 * <p>No input backend, or a backend that cannot lock, is not a failure: the drag still works, it simply runs
 * out of desk at the window edge. That is this module's standing answer — <i>"a window nobody can click is a
 * degraded window rather than a failed launch"</i> — so every method here is a no-op in that state, the loss is
 * reported once through {@link Diagnostics}, and no caller needs a null check.
 *
 * <h2>Inside the frame budget</h2>
 *
 * <p>{@link #reconcile()} runs every frame, on the main thread, from {@code FrameStage.INPUT}. It allocates
 * nothing in the two states it is in almost always — idle, and locked. It does allocate one {@link
 * PointerState} per frame while a press is <em>pending</em>, because reading where the pointer is is the only
 * way to know whether it has travelled and tactroller has no out-parameter form of the query. That window is
 * bounded by the gesture: it opens on a press over a lock-declaring node and closes a few frames later, at the
 * threshold or at the release. Recorded rather than hidden, because this module's rule is that nothing
 * allocates in {@code FrameHooks.run} and this is the one place that does.
 */
public final class PointerLock {

    /**
     * How far the pointer must travel before the cursor is hidden, in pixels of device movement.
     *
     * <p>Two, matching {@code DragGesture.DEFAULT_DISTANCE_PX}, and the agreement is the point rather than a
     * coincidence: this threshold and that one are answering the same physical question — did the hand actually
     * move, or is this a click with a tremor in it — and a framework that used a larger number would hide the
     * cursor later than the stack decides a drag began, leaving a band of motion in which the camera turns
     * while the cursor is still visible and sliding across the window.
     */
    public static final int DEFAULT_THRESHOLD_PX = 2;

    private PointerLockMode mode = PointerLockMode.RAW;
    private int thresholdPx = DEFAULT_THRESHOLD_PX;
    private boolean enabled = true;

    /**
     * The four things this needs of a pointer, behind an interface — because the state machine below is the
     * whole substance of the class and {@code Tactroller} is final, opened by a static factory, and requires a
     * platform module that this module's test classpath deliberately does not have.
     *
     * <p>Package-private and adapted in one record, so it is a test seam and not an extension point. The
     * framework's own TODO turns this trade down elsewhere — <i>"a seam taking the backend rather than opening
     * it, which is more API than the assertion is worth today"</i> — and the difference here is what is on the
     * other side of it: those were one-line capability reports, and this is a threshold, a focus rule and an
     * ordering constraint whose failures are all invisible by construction.
     */
    interface Device {

        boolean supportsPointerLock();

        boolean isFocused();

        /** Where the pointer is, in screen space — the space device travel is measured in. */
        PointerState pointer() throws BackendException;

        void lock(PointerLockMode mode) throws BackendException;

        void unlock();
    }

    /** The real one. A record so the adapter is the delegation and nothing else. */
    private record OnTactroller(Tactroller t) implements Device {

        @Override
        public boolean supportsPointerLock() {
            return t.supportsPointerLock();
        }

        @Override
        public boolean isFocused() {
            return t.isFocused();
        }

        @Override
        public PointerState pointer() throws BackendException {
            // screenPointer rather than pointer(): travel is a fact about the desk, so measuring it must not
            // change with the coordinate space the application happens to have set.
            return t.screenPointer();
        }

        @Override
        public void lock(PointerLockMode mode) throws BackendException {
            t.lockPointer(mode);
        }

        @Override
        public void unlock() {
            t.unlockPointer();
        }
    }

    /** The device, or null where there is none. Set by {@link InputBackend} when it bridges. */
    private Device device;
    /** False once the backend has said it cannot lock, or has failed to; stops asking every frame. */
    private boolean supported = true;

    /**
     * What the GUI has asked for. Written by the dispatcher's sink and read by {@link #reconcile()} on the main
     * thread — nominally the same thread, since the sink fires inside {@code Gui.frame}, but not on every path:
     * {@code InputDispatcher.clearHandlers} drops the lock too, and a node can be removed by a worker. Volatile
     * is a cheap way not to depend on which of those happened.
     */
    private volatile boolean wanted;

    /** Whether the device is actually locked right now. Main thread only. */
    private boolean locked;
    /** Whether a press is waiting on {@link #thresholdPx()} before the cursor is hidden. Main thread only. */
    private boolean pending;
    private int anchorX;
    private int anchorY;

    PointerLock() {
    }

    // ---- configuration, before the window exists -------------------------------------------------------

    /**
     * Which capture mode to use. {@link PointerLockMode#RAW} unless changed, and changing it is a decision
     * about the trade in that enum rather than a preference: {@code RECENTER} needs no raw-input plumbing and
     * costs a cursor that reappears at the centre of the window instead of under the hand, plus motion that has
     * been through the OS acceleration curve. For a 3D viewport, {@code RAW} is the one that looks right.
     */
    public PointerLock mode(PointerLockMode mode) {
        this.mode = mode == null ? PointerLockMode.RAW : mode;
        return this;
    }

    /** The mode in force. Never null. */
    public PointerLockMode mode() {
        return mode;
    }

    /**
     * How far the pointer must travel before the cursor is hidden. Zero hides it on the press, which is the
     * unmitigated behaviour and reintroduces the click flicker — see this class's first discontinuity.
     */
    public PointerLock thresholdPx(int px) {
        if (px < 0) {
            throw new IllegalArgumentException("thresholdPx must be >= 0: " + px);
        }
        this.thresholdPx = px;
        return this;
    }

    /** The travel threshold in force. */
    public int thresholdPx() {
        return thresholdPx;
    }

    /**
     * Refuse pointer lock for this application, whatever its trees declare.
     *
     * <p>For an application that wants its viewport drags to keep the cursor — an accessibility setting, a
     * kiosk, a capture run whose recording would otherwise show no pointer. The declaration stays where it is
     * and the drag keeps working; it simply runs out of desk at the window edge, which is the same degraded
     * state as a machine with no lock support.
     */
    public PointerLock enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    // ---- what a caller can ask ------------------------------------------------------------------------

    /** Whether the pointer is held right now — true only once a drag has passed the travel threshold. */
    public boolean isLocked() {
        return locked;
    }

    /** Whether there is a device under this that can hold the pointer at all. */
    public boolean supported() {
        return device != null && supported && enabled;
    }

    // ---- wiring ---------------------------------------------------------------------------------------

    /**
     * Listen to {@code gui}'s lock intent and carry it out on {@code device}.
     *
     * <p>Called by {@link InputBackend} rather than by an application, which is what keeps the reconcile ahead
     * of the snapshot — see this class's third discontinuity for why that ordering is not a detail.
     */
    void attach(Tactroller device, Gui gui) {
        attach(device == null ? null : new OnTactroller(device), gui);
    }

    /** The same, against the seam — {@code gui} may be null for a test driving {@link #want} itself. */
    void attach(Device device, Gui gui) {
        this.device = device;
        if (device == null) {
            return;
        }
        if (!device.supportsPointerLock()) {
            supported = false;
            Diagnostics.dropped("PointerLock.attach", "holding the pointer for a viewport drag",
                    "this input backend cannot lock the pointer; drags that mean a displacement will stop at "
                            + "the window edge instead of turning indefinitely");
            return;
        }
        if (gui != null) {
            gui.onPointerLock(this::want);
        }
    }

    /** What the dispatcher's sink does. Package-private so a test can be the dispatcher. */
    void want(boolean want) {
        this.wanted = want;
    }

    /** A sibling for another window, carrying this one's tuning and none of its state. */
    PointerLock forAnotherWindow() {
        PointerLock copy = new PointerLock();
        copy.mode = mode;
        copy.thresholdPx = thresholdPx;
        copy.enabled = enabled;
        return copy;
    }

    /**
     * Bring the device's lock into line with what the GUI has asked for. Called once per frame from
     * {@link InputBackend#pump()}, immediately before the snapshot that drains this frame's motion.
     *
     * <p>Never throws. A device query that fails is this frame's reconcile abandoned, on the same reasoning the
     * pump beside it uses: the stage is documented as not throwing, and sixty stack traces a second inform
     * nobody.
     */
    void reconcile() {
        if (device == null || !supported || !enabled) {
            return;
        }
        try {
            step();
        } catch (BackendException e) {
            // Transient: the lock stays as it is for one frame. Nothing here is cumulative, so the next frame
            // reconciles from the same intent and reaches the same place.
        }
    }

    /** The state machine, with the device calls that can fail. See {@link #reconcile()}. */
    private void step() throws BackendException {
        // Focus is the second condition on holding the pointer and it is not the GUI's to report: the
        // dispatcher keeps asking for the lock across an alt-tab, correctly, because the drag is still live.
        // Dropping it here is what keeps a hidden cursor from being captured over somebody else's window.
        boolean want = wanted && device.isFocused();
        if (!want) {
            release();
            return;
        }
        if (locked) {
            return;
        }
        if (!pending) {
            // First frame of the press. Take the anchor and hide nothing: the whole point is that a click is
            // still allowed to turn out to be a click.
            PointerState at = device.pointer();
            anchorX = at.x();
            anchorY = at.y();
            pending = true;
            return;
        }
        PointerState at = device.pointer();
        if (Math.abs(at.x() - anchorX) < thresholdPx && Math.abs(at.y() - anchorY) < thresholdPx) {
            return;
        }
        // Travelled. lock() zeroes the backend's accumulator, so the motion this frame's snapshot is about to
        // drain starts from here rather than arriving as one jump -- which is the whole reason this runs
        // before the pump and not as a hook beside it.
        device.lock(mode);
        locked = true;
        pending = false;
    }

    /** Give the pointer back, if we are holding it. Idempotent. */
    private void release() {
        pending = false;
        if (!locked) {
            return;
        }
        device.unlock();
        locked = false;
    }

    /**
     * Give the pointer back unconditionally, for a window going away.
     *
     * <p>{@code Tactroller.close()} clears the lock too, so this is belt and braces on the ordinary path — but
     * a window closed mid-drag while the process keeps running has no other moment that would, and a cursor
     * that stays hidden after its window is gone is the worst version of this class's failure.
     */
    void dispose() {
        wanted = false;
        if (device != null) {
            release();
        }
    }
}

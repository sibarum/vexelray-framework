package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.PointerLockMode;
import sibarum.tactroller.api.PointerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a locked drag has no visible seam at either end.
 *
 * <p>Everything {@link PointerLock} does beyond "lock when told" exists to remove a visual discontinuity, and
 * every one of those discontinuities is invisible to the kind of assertion this suite otherwise makes: nothing
 * throws, no capability is dropped, a screenshot of the window is identical either way. What is different is
 * <em>when</em> the cursor was hidden and shown, so that is what is recorded here — the device calls, in order,
 * as a transcript.
 *
 * <p>The four cases below are the four numbered in {@link PointerLock}'s own documentation, in the same order,
 * so a reader who deletes a mitigation finds the paragraph that argued for it.
 *
 * <p>No GPU and no input stack: the state machine is driven against {@link PointerLock.Device}, which is what
 * that seam is for. {@code want(true)} is the dispatcher saying a lock-declaring drag began, and
 * {@code reconcile()} is one frame.
 */
final class PointerLockTest {

    /**
     * A pointer that goes where it is told and writes down what was asked of it.
     *
     * <p>The transcript is the assertion in most of these: "hide" and "show" are what the user sees, so a test
     * that counted only the final state would pass on a lock that flickered twice on the way there.
     */
    private static final class FakePointer implements PointerLock.Device {

        private final List<String> calls = new ArrayList<>();
        private boolean supports = true;
        private boolean focused = true;
        private int x;
        private int y;
        private boolean fail;

        @Override
        public boolean supportsPointerLock() {
            return supports;
        }

        @Override
        public boolean isFocused() {
            return focused;
        }

        @Override
        public PointerState pointer() throws BackendException {
            if (fail) {
                throw new BackendException("the device is having a moment");
            }
            return new PointerState(x, y, Set.of());
        }

        @Override
        public void lock(PointerLockMode mode) {
            calls.add("hide:" + mode);
        }

        @Override
        public void unlock() {
            calls.add("show");
        }

        void at(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private FakePointer pointer;
    private PointerLock lock;

    @BeforeEach
    void setUp() {
        Diagnostics.reset();
        pointer = new FakePointer();
        lock = new PointerLock();
        lock.attach(pointer, null);
    }

    @AfterEach
    void tearDown() {
        Diagnostics.reset();
    }

    /** Run {@code frames} frames with nothing moving. */
    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            lock.reconcile();
        }
    }

    // ---- 1. the click that flickers --------------------------------------------------------------------

    @Test
    void aClickOnAViewportNeverHidesTheCursor() {
        // The dispatcher asks for the lock on the press, before any motion -- so this is what an ordinary
        // click on a lock-declaring node looks like arriving here.
        lock.want(true);
        frames(3);
        lock.want(false);
        frames(1);

        assertEquals(List.of(), pointer.calls,
                "press and release without moving is a click; hiding the cursor for three frames of it is the "
                        + "flicker this threshold exists to remove");
        assertFalse(lock.isLocked());
    }

    @Test
    void aDragHidesTheCursorOnceThePointerHasActuallyTravelled() {
        lock.want(true);
        lock.reconcile();                       // anchors at 0,0 and hides nothing
        assertEquals(List.of(), pointer.calls, "the anchor frame must not hide anything");

        pointer.at(1, 0);                       // inside the threshold: still a click so far
        lock.reconcile();
        assertEquals(List.of(), pointer.calls, "one pixel is hand tremor, not a drag");

        pointer.at(4, 0);                       // past it
        lock.reconcile();
        assertEquals(List.of("hide:RAW"), pointer.calls);
        assertTrue(lock.isLocked());

        lock.want(false);
        lock.reconcile();
        assertEquals(List.of("hide:RAW", "show"), pointer.calls, "released exactly once, at the end");
    }

    @Test
    void aThresholdOfZeroIsTheUnmitigatedBehaviourAndSaysSo() {
        // Documented as reintroducing the flicker, so it had better actually be reachable -- an option that
        // silently kept the mitigation would be worse than not offering it.
        lock.thresholdPx(0);
        lock.want(true);
        frames(2);

        assertEquals(List.of("hide:RAW"), pointer.calls, "zero hides on the press");
    }

    // ---- 2. the cursor that comes back somewhere else --------------------------------------------------

    @Test
    void theDefaultModeIsTheOneThatDoesNotWarpTheCursor() {
        assertEquals(PointerLockMode.RAW, lock.mode(),
                "RECENTER warps the cursor to the centre every drain, so the release restores it at the middle "
                        + "of the window instead of under the hand");

        lock.want(true);
        lock.reconcile();
        pointer.at(50, 50);
        lock.reconcile();

        assertEquals(List.of("hide:RAW"), pointer.calls);
    }

    @Test
    void anApplicationThatAsksForRecenterGetsIt() {
        lock.mode(PointerLockMode.RECENTER);
        lock.want(true);
        lock.reconcile();
        pointer.at(50, 50);
        lock.reconcile();

        assertEquals(List.of("hide:RECENTER"), pointer.calls);
    }

    // ---- 4. the cursor left hidden over somebody else's window -----------------------------------------

    @Test
    void losingFocusMidDragGivesThePointerBackWithoutEndingTheDrag() {
        lock.want(true);
        lock.reconcile();
        pointer.at(50, 50);
        lock.reconcile();
        assertTrue(lock.isLocked());

        pointer.focused = false;                // alt-tab, with the button still down
        lock.reconcile();
        assertEquals(List.of("hide:RAW", "show"), pointer.calls,
                "a hidden cursor captured over another application is the worst version of this");
        assertFalse(lock.isLocked());

        // The dispatcher has not withdrawn the intent, because the drag is still live -- DragGesture is
        // explicit that focus loss does not cancel one.
        pointer.focused = true;
        lock.reconcile();
        assertEquals(List.of("hide:RAW", "show"), pointer.calls,
                "coming back to the window must not snatch the cursor before the hand has moved");

        pointer.at(100, 100);
        lock.reconcile();
        assertEquals(List.of("hide:RAW", "show", "hide:RAW"), pointer.calls,
                "and it is retaken once it has");
    }

    // ---- absence is a state ----------------------------------------------------------------------------

    @Test
    void aBackendThatCannotLockIsReportedOnceAndThenLeftAlone() {
        FakePointer cannot = new FakePointer();
        cannot.supports = false;
        PointerLock refused = new PointerLock();
        refused.attach(cannot, null);

        refused.want(true);
        for (int i = 0; i < 5; i++) {
            refused.reconcile();
            cannot.at(i * 10, i * 10);
        }

        assertEquals(List.of(), cannot.calls, "nothing to ask, so it is not asked every frame");
        assertFalse(refused.supported());
        assertEquals(1, Diagnostics.recorded().size(), "" + Diagnostics.recorded());
        assertTrue(Diagnostics.recorded().get(0).contains("window edge"),
                "the report has to say what it costs: " + Diagnostics.recorded().get(0));
    }

    @Test
    void noDeviceAtAllIsANoOpAndNotACrash() {
        PointerLock none = new PointerLock();
        none.attach((PointerLock.Device) null, null);

        none.want(true);
        none.reconcile();
        none.dispose();

        assertFalse(none.supported());
    }

    @Test
    void anApplicationCanRefuseTheLockAndTheDragStillRuns() {
        lock.enabled(false);
        lock.want(true);
        lock.reconcile();
        pointer.at(80, 80);
        lock.reconcile();

        assertEquals(List.of(), pointer.calls, "declared upstream, refused here, and nothing throws");
        assertFalse(lock.isLocked());
    }

    @Test
    void aDeviceThatFailsForAFrameLosesThatFrameAndNotTheLoop() {
        lock.want(true);
        lock.reconcile();
        pointer.fail = true;
        lock.reconcile();                       // must not throw: the stage it runs in is documented not to
        assertEquals(List.of(), pointer.calls);

        pointer.fail = false;
        pointer.at(60, 60);
        lock.reconcile();
        assertEquals(List.of("hide:RAW"), pointer.calls, "nothing here is cumulative, so it recovers");
    }

    // ---- teardown --------------------------------------------------------------------------------------

    @Test
    void aWindowClosedMidDragHandsTheCursorBack() {
        lock.want(true);
        lock.reconcile();
        pointer.at(70, 70);
        lock.reconcile();
        assertTrue(lock.isLocked());

        lock.dispose();

        assertEquals(List.of("hide:RAW", "show"), pointer.calls,
                "a cursor still hidden after its window is gone has nothing left to give it back");
        assertFalse(lock.isLocked());
    }

    @Test
    void aSecondWindowsLockCarriesTheTuningAndNoneOfTheState() {
        lock.mode(PointerLockMode.RECENTER).thresholdPx(9).enabled(false);
        lock.want(true);

        PointerLock second = lock.forAnotherWindow();

        assertEquals(PointerLockMode.RECENTER, second.mode());
        assertEquals(9, second.thresholdPx());
        assertFalse(second.enabled());
        assertFalse(second.isLocked(), "a lock is one pointer over one window; two cannot hold it at once");
    }
}

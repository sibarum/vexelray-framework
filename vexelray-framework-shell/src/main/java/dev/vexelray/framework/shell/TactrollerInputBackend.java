package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.WindowInput;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;

/**
 * {@link InputBackend} on tactroller — the framework's copy of four helper methods that every application on
 * this stack used to write for itself.
 *
 * <p>{@code Tactroller.open()} throws where there is no backend, and every hand-written edge answered it the same
 * way; this holds a {@code null} in that case and every method is a no-op, which is how the interface's
 * <i>absence is a state</i> is kept here.
 */
final class TactrollerInputBackend implements InputBackend {

    private final Tactroller input;

    private TactrollerInputBridge bridge;
    private PointerLock pointerLock;

    private TactrollerInputBackend(Tactroller input) {
        this.input = input;
    }

    /** See {@link InputBackend#open()}. */
    static InputBackend open() {
        try {
            Tactroller t = Tactroller.open();
            System.out.println("input: " + t.backendName());
            return new TactrollerInputBackend(t);
        } catch (BackendException e) {
            Diagnostics.dropped("InputBackend.open", "pointer and keyboard input for this application",
                    e.getMessage() + "; the window renders and nothing in it can be clicked");
            return new TactrollerInputBackend(null);
        }
    }

    @Override
    public boolean present() {
        return input != null;
    }

    /**
     * Attach to the window and settle the coordinate space.
     *
     * <p><b>{@code CLIENT}, and density deliberately left at 1.0.</b> Both follow from one fact: the engine's
     * window and {@code Canvas} are in <em>logical</em> coordinates, not framebuffer pixels. On a scaled
     * display that has two consequences, and getting either wrong is visible immediately — {@code FRAMEBUFFER}
     * coordinates are {@code CLIENT} times {@code contentScale}, so every press would land past its target; and
     * the OS is already scaling a logical window's output, so feeding {@code contentScale()} into
     * {@code Gui.dpi} scales the content a second time.
     */
    @Override
    public void attach(long windowHandle) {
        if (input == null) {
            return;
        }
        try {
            input.attach(sibarum.tactroller.api.NativeWindow.ofHwnd(windowHandle));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            Diagnostics.dropped("InputBackend.attach", "pointer input for the main window",
                    e.getMessage() + "; the backend opened but could not be bound to the window handle");
        }
    }

    /**
     * Bridge this backend's events onto {@code gui}'s bus, and carry {@code gui}'s pointer-lock intent onto
     * this backend. Call once, after {@link #attach}.
     *
     * <p>The two together rather than separately, because the lock has to be reconciled <em>before</em> each
     * frame's snapshot and taking them as one argument list is what makes that impossible to get wrong — see
     * {@link #pump()}.
     */
    @Override
    public void bridge(Gui gui, PointerLock lock) {
        if (input != null) {
            bridge = new TactrollerInputBridge(input, gui.bus());
        }
        pointerLock = lock;
        if (lock != null) {
            lock.attach(input, gui);
        }
    }

    /**
     * Reconcile the pointer lock, then snapshot this frame's input onto the bus.
     *
     * <p><b>In that order, in one method, on purpose.</b> A lock that engages after the snapshot has drained
     * leaves this frame's motion read in the wrong mode, and it arrives as one large delta — the camera snaps.
     * {@code Fathom}, the stack's hand-written mouselook, has the rule and the reason: <i>"Reconcile the lock
     * BEFORE pumping so this frame's snapshot drains in the right mode. lockPointer(RAW) zeroes the backend
     * accumulator, so toggling never yields a stray jump."</i> Two frame hooks in {@code FrameStage.INPUT}
     * would express the same thing and rest it on registration order, which {@code FrameStage} says outright is
     * a mistake to depend on. One hook cannot be registered in the wrong order.
     *
     * <p>A transient poll failure drops this frame's input rather than tearing down the loop — the frame stage
     * this runs in is documented as not throwing, and sixty stack traces a second inform nobody.
     */
    @Override
    public void pump() {
        if (pointerLock != null) {
            pointerLock.reconcile();
        }
        if (bridge == null) {
            return;
        }
        try {
            bridge.pump();
        } catch (BackendException e) {
            // Transient: this frame goes without input.
        }
    }

    /**
     * Input for a window the framework opened on its own — a dialog, a named window: its own backend, attached
     * to that window's handle, bridged onto that window's bus, pumped by the frame loop.
     *
     * <p>Note the two unrelated {@code NativeWindow} types in play, which is why both are written out in full
     * here. The parameter is the engine's ({@code dev.vexelray.os.NativeWindow}); the one tactroller attaches
     * to is its own ({@code sibarum.tactroller.api.NativeWindow}). Importing either shadows the other in a file
     * that names both, with no complaint at the import.
     *
     * <p><b>Each such window gets a {@link PointerLock} of its own</b>, tuned like the main window's and
     * sharing none of its state — a lock is a property of one pointer over one window, and two windows cannot
     * hold it at once. Giving the other windows the lock as well is not thoroughness: the framework's own TODO
     * already names this failure shape, <i>"a line to repeat per window, correct on the window under test and
     * missing on the one being used"</i>, and a viewport in a second window is exactly the case the designer
     * has.
     *
     * @param tuning the main window's lock, read for its mode and threshold only
     */
    static WindowInput.Factory perWindow(PointerLock tuning) {
        return (window, windowGui) -> {
            Tactroller backend;
            try {
                backend = Tactroller.open();
                backend.attach(sibarum.tactroller.api.NativeWindow.ofHwnd(window.osHandle()));
                backend.setCoordinateSpace(CoordinateSpace.CLIENT);
            } catch (BackendException e) {
                // The one of these that used to say nothing at all, and the one that most needed to. A second
                // window that renders and hears no device is indistinguishable, by eye, from a window whose
                // application forgot to wire a handler — which is Diagnostics' own fault "a capability that is
                // silently dropped", arriving as something that reads like a taste decision.
                Diagnostics.dropped("InputBackend.perWindow", "pointer and keyboard input for a window the "
                        + "framework opened", e.getMessage() + "; that window renders and takes no input");
                return WindowInput.NONE;
            }
            TactrollerInputBridge windowBridge = new TactrollerInputBridge(backend, windowGui.bus());
            PointerLock windowLock = tuning == null ? null : tuning.forAnotherWindow();
            if (windowLock != null) {
                windowLock.attach(backend, windowGui);
            }
            return new WindowInput() {

                @Override
                public void pump() {
                    // Before the drain, for the reason InputBackend.pump gives.
                    if (windowLock != null) {
                        windowLock.reconcile();
                    }
                    try {
                        windowBridge.pump();
                    } catch (BackendException e) {
                        // As above: drop the frame's input, keep the loop.
                    }
                }

                @Override
                public void close() {
                    // Before the backend goes, so a window closed mid-drag hands the cursor back rather than
                    // leaving it hidden over whatever is behind it.
                    if (windowLock != null) {
                        windowLock.dispose();
                    }
                    backend.close();
                }
            };
        };
    }

    @Override
    public void close() {
        // The lock first: Tactroller.close clears it too, but only once it has managed to stop its event loop,
        // and a shutdown that times out there would otherwise leave the cursor hidden on the way out.
        if (pointerLock != null) {
            pointerLock.dispose();
        }
        if (input != null) {
            input.close();
        }
    }
}

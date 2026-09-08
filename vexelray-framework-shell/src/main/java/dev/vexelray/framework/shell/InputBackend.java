package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.WindowInput;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;

/**
 * The input backend, attached to the window and bridged onto the bus — the framework's copy of four helper
 * methods that every application on this stack currently writes for itself.
 *
 * <p><b>Absence is a state, not a failure.</b> {@code Tactroller.open()} throws where there is no backend, and
 * every hand-written edge answers it the same way, for a reason worth keeping verbatim: <i>"a window nobody can
 * click is a degraded window rather than a failed launch"</i>. So this class is always constructible, reports
 * {@link #present()} false when there is nothing underneath it, and every method is a no-op in that state. No
 * caller needs a null check, and CI keeps rendering.
 */
public final class InputBackend implements AutoCloseable {

    private final Tactroller input;

    private TactrollerInputBridge bridge;

    private InputBackend(Tactroller input) {
        this.input = input;
    }

    /** Open the backend, or report its absence. Never throws, never returns null. */
    public static InputBackend open() {
        try {
            Tactroller t = Tactroller.open();
            System.out.println("input: " + t.backendName());
            return new InputBackend(t);
        } catch (BackendException e) {
            System.out.println("input unavailable (" + e.getMessage() + "); running without pointer input");
            return new InputBackend(null);
        }
    }

    /** Whether there is a backend at all. */
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
    public void attach(long windowHandle) {
        if (input == null) {
            return;
        }
        try {
            input.attach(sibarum.tactroller.api.NativeWindow.ofHwnd(windowHandle));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.out.println("input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /** Bridge this backend's events onto {@code gui}'s bus. Call once, after {@link #attach}. */
    public void bridge(Gui gui) {
        if (input != null) {
            bridge = new TactrollerInputBridge(input, gui.bus());
        }
    }

    /**
     * Snapshot this frame's input onto the bus.
     *
     * <p>A transient poll failure drops this frame's input rather than tearing down the loop — the frame stage
     * this runs in is documented as not throwing, and sixty stack traces a second inform nobody.
     */
    public void pump() {
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
     */
    public static WindowInput.Factory perWindow() {
        return (window, windowGui) -> {
            Tactroller backend;
            try {
                backend = Tactroller.open();
                backend.attach(sibarum.tactroller.api.NativeWindow.ofHwnd(window.osHandle()));
                backend.setCoordinateSpace(CoordinateSpace.CLIENT);
            } catch (BackendException e) {
                return WindowInput.NONE;
            }
            TactrollerInputBridge windowBridge = new TactrollerInputBridge(backend, windowGui.bus());
            return new WindowInput() {

                @Override
                public void pump() {
                    try {
                        windowBridge.pump();
                    } catch (BackendException e) {
                        // As above: drop the frame's input, keep the loop.
                    }
                }

                @Override
                public void close() {
                    backend.close();
                }
            };
        };
    }

    @Override
    public void close() {
        if (input != null) {
            input.close();
        }
    }
}

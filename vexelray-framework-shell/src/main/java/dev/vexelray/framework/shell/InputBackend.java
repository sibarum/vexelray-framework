package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.WindowInput;

/**
 * The input backend, attached to the window and bridged onto the bus.
 *
 * <p><b>An interface, because a provider returns one.</b> {@code @Provides} returns an interface so that the
 * wiring can construct something else without a call site knowing, and this is the first thing an application
 * would want to swap: a recorded session instead of a device, a scripted one in a test, a backend for a platform
 * tactroller does not cover. {@link #open()} is the framework's answer, on tactroller, and it is the only one
 * there is today.
 *
 * <p><b>Absence is a state, not a failure</b>, and every implementation keeps it. {@code Tactroller.open()}
 * throws where there is no backend, and every hand-written edge answers it the same way, for a reason worth
 * keeping verbatim: <i>"a window nobody can click is a degraded window rather than a failed launch"</i>. So a
 * backend is always there to be had, reports {@link #present()} false when there is nothing underneath it, and
 * every method is a no-op in that state. No caller needs a null check, and CI keeps rendering.
 */
public interface InputBackend extends AutoCloseable {

    /** The framework's backend, on tactroller, or its absence reported. Never throws, never returns null. */
    static InputBackend open() {
        return TactrollerInputBackend.open();
    }

    /**
     * Input for each window the framework opens on its own — a dialog, a named window — each with its own
     * backend and its own {@link PointerLock}, tuned like the main window's.
     *
     * @param tuning the main window's lock, read for its mode and threshold only
     */
    static WindowInput.Factory perWindow(PointerLock tuning) {
        return TactrollerInputBackend.perWindow(tuning);
    }

    /** Whether there is a backend at all. */
    boolean present();

    /** Attach to the main window's handle, in the logical coordinates the engine's window and canvas use. */
    void attach(long windowHandle);

    /**
     * Bridge this backend's events onto {@code gui}'s bus, and carry {@code gui}'s pointer-lock intent onto it.
     * Once, after {@link #attach}; the two together, because the lock has to be reconciled before each frame's
     * snapshot — see {@link #pump()}.
     */
    void bridge(Gui gui, PointerLock lock);

    /**
     * Reconcile the pointer lock, then snapshot this frame's input onto the bus — in that order, in one call, so
     * the order cannot rest on how two hooks happened to be registered. Runs in {@code FrameStage.INPUT}, so it
     * must not throw: a transient failure drops this frame's input and keeps the loop.
     */
    void pump();

    /** Hand back the pointer, then release the device. Never throws. */
    @Override
    void close();
}

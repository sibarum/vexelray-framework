package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;

/**
 * <b>Asked before a window closes</b> — the seam an application with unsaved work needs. Set on a {@link Window} with
 * {@link Window#onClose}; for the main window, closing is quitting, so this is also how an application refuses to exit.
 *
 * {@snippet :
 * windows.main().onClose((proceed, cancel) -> {
 *     if (nothingUnsaved()) {
 *         proceed.run();
 *     } else {
 *         dialogs.confirm("Quit?", "Your changes will be lost.", "Quit", proceed, cancel);
 *     }
 * });
 * }
 *
 * <p><b>Two callbacks, and exactly one of them is run</b>, at the gate's leisure and from any thread. Until one is, the
 * window stays open and fully live, which is what lets the answer come from a dialog. A gate that runs neither leaves the
 * window open for good, and that is the gate's choice to make.
 *
 * <p><b>Two {@code Runnable}s rather than gui-core's {@code CloseRequest}</b>, because a {@code CloseRequest} cannot be
 * made outside gui-core, so a test could not hand a gate one: the template and the text editor each grew a
 * {@code guardClose(Runnable, Runnable)} beneath the real gate to get round it (Vex FN-6). Shaped like that, the gate a
 * test calls is the gate the window calls.
 *
 * <p><b>The framework installs none of its own</b>, and that is the decision rather than an omission: the default has to
 * be that closing closes. Most applications have nothing to lose.
 */
@Stability(Stability.Level.EXPERIMENTAL)
@FunctionalInterface
public interface CloseGate {

    /**
     * The window has been asked to close. Run {@code proceed} to let it, or {@code cancel} to keep it open; exactly one,
     * once, from any thread. Called on the handler lane.
     */
    void ask(Runnable proceed, Runnable cancel);
}

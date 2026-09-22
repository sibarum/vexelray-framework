package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;

/**
 * The OS clipboard, installed on a {@code Gui} so text fields can be pasted into.
 *
 * <p><b>An interface, for the reason {@link InputBackend} is one:</b> {@code @Provides} returns an interface, so
 * the wiring can construct another — a clipboard confined to the application for a kiosk, or a recording one in
 * a test — without a call site knowing. {@link #open()} is the framework's answer, and the only one today.
 *
 * <p><b>Absent, not failed</b>, on the same terms as {@link InputBackend}: where there is no backend, the GUI's
 * in-memory default stays in place and paste works within the application only.
 *
 * <p><b>Installed per {@code Gui}, not per application</b>, and that is the bug this replaces. The text editor
 * binds the clipboard to every window it opens, in a loop, with a comment explaining why: <i>"Every window gets
 * the OS clipboard, not just the main one: copy out of the terminal's prompt has to reach the same place copy
 * out of a tab does."</i> A second window that forgets is a window where copy silently does nothing — which is
 * why {@link #installOn} takes the {@code Gui} rather than being called once at startup.
 */
public interface ClipboardBackend extends AutoCloseable {

    /** The framework's clipboard, on tactroller, or its absence reported. Never throws, never returns null. */
    static ClipboardBackend open() {
        return TactrollerClipboardBackend.open();
    }

    /** Whether there is a backend at all. */
    boolean present();

    /** Bind this clipboard to {@code gui}. A no-op when absent, leaving the in-memory default in place. */
    ClipboardBackend installOn(Gui gui);

    /** Release the OS clipboard. Never throws. */
    @Override
    void close();
}

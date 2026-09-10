package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

/**
 * The OS clipboard, installed on a {@code Gui} so text fields can be pasted into.
 *
 * <p>Absent when there is no backend, on the same terms as {@link InputBackend}: the GUI's in-memory default
 * stays in place and paste works within the application only.
 *
 * <p><b>Installed per {@code Gui}, not per application</b>, and that is the bug this replaces. The text editor
 * binds the clipboard to every window it opens, in a loop, with a comment explaining why: <i>"Every window gets
 * the OS clipboard, not just the main one: copy out of the terminal's prompt has to reach the same place copy
 * out of a tab does."</i> A second window that forgets is a window where copy silently does nothing —
 * which is why {@link #installOn} takes the {@code Gui} rather than being called once at startup.
 */
public final class ClipboardBackend implements AutoCloseable {

    private final Clipboard clipboard;

    private ClipboardBackend(Clipboard clipboard) {
        this.clipboard = clipboard;
    }

    /** Open the OS clipboard, or report its absence. Never throws, never returns null. */
    public static ClipboardBackend open() {
        try {
            return new ClipboardBackend(Clipboard.open());
        } catch (ClipboardException e) {
            Diagnostics.dropped("ClipboardBackend.open", "the OS clipboard",
                    e.getMessage() + "; copy and paste work inside this application and reach nothing outside it");
            return new ClipboardBackend(null);
        }
    }

    /** Whether there is a backend at all. */
    public boolean present() {
        return clipboard != null;
    }

    /** Bind this clipboard to {@code gui}. A no-op when absent, leaving the in-memory default in place. */
    public ClipboardBackend installOn(Gui gui) {
        if (clipboard == null) {
            return this;
        }
        gui.clipboard(new TextClipboard() {

            @Override
            public String get() {
                try {
                    return clipboard.getText().orElse("");
                } catch (ClipboardException e) {
                    return "";
                }
            }

            @Override
            public void set(String text) {
                try {
                    clipboard.setText(text);
                } catch (ClipboardException e) {
                    // Best effort: a transient clipboard failure just drops the copy.
                }
            }
        });
        return this;
    }

    @Override
    public void close() {
        if (clipboard != null) {
            clipboard.close();
        }
    }
}

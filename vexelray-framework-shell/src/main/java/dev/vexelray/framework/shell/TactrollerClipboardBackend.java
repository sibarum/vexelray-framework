package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

/**
 * {@link ClipboardBackend} on tactroller's standalone OS clipboard. Holds a {@code null} where there is none,
 * and every method is then a no-op, which is how the interface's <i>absent, not failed</i> is kept here.
 */
final class TactrollerClipboardBackend implements ClipboardBackend {

    private final Clipboard clipboard;

    private TactrollerClipboardBackend(Clipboard clipboard) {
        this.clipboard = clipboard;
    }

    /** See {@link ClipboardBackend#open()}. */
    static ClipboardBackend open() {
        try {
            return new TactrollerClipboardBackend(Clipboard.open());
        } catch (ClipboardException e) {
            Diagnostics.dropped("ClipboardBackend.open", "the OS clipboard",
                    e.getMessage() + "; copy and paste work inside this application and reach nothing outside it");
            return new TactrollerClipboardBackend(null);
        }
    }

    @Override
    public boolean present() {
        return clipboard != null;
    }

    @Override
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

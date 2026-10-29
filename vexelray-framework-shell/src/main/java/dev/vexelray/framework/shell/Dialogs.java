package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.widget.Modal;
import sibarum.atchung.Sender;

/**
 * <b>Whatever answers this application's questions</b>: shows a {@link Modal}, and runs the OS's picker for a file to
 * open, a folder, or a place to save. The framework's own does both over the main window; an application, or a test,
 * may put its own in place.
 *
 * <p><b>Most parts never take this.</b> A question that is only words is an {@link dev.vexelray.framework.core.Ask}
 * message, answered by an {@link dev.vexelray.framework.core.Answer}; a picker is a {@link PickFile}, answered by a
 * {@link Picked}. Any part sends those, a component on the logic lane as well as a view, and the framework hands each
 * to the dialogs here. A view that wants a dialog of its own shape, a {@code Modal} whose buttons send its own
 * messages, takes this and calls {@link #ask}. A {@code Modal} is data: its buttons name the messages they send, so
 * nothing of the application's runs at the press.
 *
 * <p><b>Replaceable, which is how a test answers.</b> A {@code @Provides} method returning this type, or
 * {@code Shell.dialogs(Dialogs)} by {@code WINDOW}, is the application's dialogs in place of the framework's: a test's
 * that answers each question as it is asked, a kiosk's that refuses to browse. Every question asked, by message or by
 * {@link #ask}, then goes to that one, in a headless run as well as a windowed one.
 *
 * <p><b>Called on the main thread, in the frame.</b> The framework hands these every question in the main window's
 * frame, after whatever was asked before there was anything to answer it, in the order it was asked. What
 * {@link Shell#dialogs()} hands a part is safe from any thread, and holds a question until then.
 *
 * <p>Phase 5 of the 0.2 roadmap: the pickers took a {@code Consumer<Path>} and a {@code Runnable}, and {@code confirm}
 * two {@code Runnable}s, run on a thread of the dialogs' own. A callback cannot cross a lane; a message can.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public interface Dialogs {

    /** Show {@code modal}, or queue it behind the one that is up. Its buttons send the answer. */
    void ask(Modal modal);

    /**
     * Run the picker {@code request} asks for, and send exactly one {@link Picked} with its {@code id} on
     * {@code answer}, from any thread: the path, or {@link Picked#cancelled} when the person cancelled or the picker
     * could not be shown, so the asker is never left waiting.
     */
    void pick(PickFile request, Sender<Picked> answer);

    /** A message with one OK button: the "your export finished" dialog. */
    default void info(String title, String message) {
        ask(Modal.of(title, message).defaultButton("OK"));
    }
}

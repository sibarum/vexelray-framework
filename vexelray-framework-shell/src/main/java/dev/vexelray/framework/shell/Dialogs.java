package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>Every question this application asks the person using it</b> — a {@link Modal} with buttons, and the OS's own
 * pickers for a file to open, a folder, and a place to save — from any thread, from any part, at any phase.
 *
 * {@snippet :
 * @Provides
 * Actions actions(Gui gui, Model model, Dialogs dialogs) {
 *     return new Actions(gui, model, dialogs);     // and later, from a handler:
 * }                                                 // dialogs.ask(Modal.of("Delete file", "...")...);
 * }
 *
 * <p><b>A value a part is handed, not a static it reaches for.</b> The dialogs used to be {@code Modals}, a
 * process-wide static the framework installs once the window exists. So a tree built headless
 * ({@link VexelApplication#tree}) could not run any path that asks a question — it threw — and a test could not answer
 * one, which is why the text editor and the template each grew an {@code ask} field of their own (Vex FN-6). The file
 * pickers were worse: each needs the window's handle and a way onto the main thread, so each application that opened
 * a file took the {@code GuiApp} for it and was main-thread for a handle it only passed along.
 *
 * <p><b>Not the main thread's, and there from the start.</b> {@link Shell#dialogs()} exists in every phase and every
 * method here is safe from any thread. What it hands out holds a question asked before there is a window to ask it in,
 * and asks it when there is, in the order it was asked.
 *
 * <p><b>Replaceable, which is how a test answers.</b> A {@code @Provides} method returning this type is the
 * application's dialogs in place of the framework's — a test's that answers each question as it is asked, a kiosk's
 * that refuses to browse — and every part that takes {@code Dialogs} is then asking that one, in a headless run as
 * well as a windowed one. The framework's own is a {@code Modals} dialog for a {@link Modal}, and NFD's native pickers,
 * both parented to the main window, with the answer delivered on the handler lane.
 *
 * <p><b>Every question has two ends, and both are answered.</b> A picker calls {@code picked} or {@code cancelled},
 * exactly once, and a failure to show it at all is a cancel: an application that only handles the pick leaves its
 * caller waiting forever on the day the dialog cannot open. A {@link Modal} answers through its buttons, and its
 * cancel button is what Escape and the close box take.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public interface Dialogs {

    /** Ask {@code modal}: shown, or queued behind the one that is up. Its buttons' actions are the answer. */
    void ask(Modal modal);

    /**
     * Ask for a file to open, starting in {@code start} (null for wherever the OS likes) and offering {@code filters}
     * (empty for any file).
     */
    void openFile(Path start, List<FileDialog.Filter> filters, Consumer<Path> picked, Runnable cancelled);

    /** Ask for a folder, starting in {@code start}. */
    void openFolder(Path start, Consumer<Path> picked, Runnable cancelled);

    /** Ask where to save, starting in {@code start} with {@code name} filled in, offering {@code filters}. */
    void saveFile(Path start, String name, List<FileDialog.Filter> filters, Consumer<Path> picked,
                  Runnable cancelled);

    /** A message with one OK button: the "your export finished" dialog. */
    default void info(String title, String message) {
        ask(Modal.of(title, message).defaultButton("OK", () -> { }));
    }

    /**
     * A yes-or-no question. {@code onCancel} runs for Cancel, Escape and the close box alike, so "the user did not
     * answer" is answered too.
     */
    default void confirm(String title, String message, String confirmLabel, Runnable onConfirm, Runnable onCancel) {
        ask(Modal.of(title, message).defaultButton(confirmLabel, onConfirm).cancelButton("Cancel", onCancel));
    }
}

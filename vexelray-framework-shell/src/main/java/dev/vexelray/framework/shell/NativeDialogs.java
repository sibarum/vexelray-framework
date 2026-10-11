package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.nfd.Nfd;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import sibarum.probe.Log;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The framework's {@link Dialogs}: {@link Modals} for a question, NFD's native pickers for the rest, all parented to
 * the main window. Made at {@code ATTACH}, when there is a window to parent them to.
 *
 * <p>Where a picker runs is {@code FileDialog}'s decision, not this class's: on Windows it is NFD's own dialog thread,
 * so the window keeps drawing behind it, and on macOS AppKit insists on the main thread, so the request is posted to
 * the frame loop. Either way the answer comes back on the handler lane, where an application's handlers run.
 *
 * <p>This is the text editor's {@code NativeDialogs}, moved here: it was written once and then needed by Vexplore's
 * folder chooser and Pix's opener, each of which took the {@code GuiApp} to reach it.
 */
final class NativeDialogs implements Dialogs, AutoCloseable {

    private static final Log LOG = Log.of("framework.dialogs");

    private final GuiApp app;
    private final Modals modals;
    private final Executor answers;

    NativeDialogs(GuiApp app, Modals modals, Executor answers) {
        this.app = app;
        this.modals = modals;
        this.answers = answers;
    }

    @Override
    public void ask(Modal modal) {
        modals.present(modal);
    }

    @Override
    public void openFile(Path start, List<FileDialog.Filter> filters, Consumer<Path> picked, Runnable cancelled) {
        answer(FileDialog.openAsync(app::post, app.windowHandle(), filters, start), picked, cancelled);
    }

    @Override
    public void openFolder(Path start, Consumer<Path> picked, Runnable cancelled) {
        answer(FileDialog.pickFolderAsync(app::post, app.windowHandle(), start), picked, cancelled);
    }

    @Override
    public void saveFile(Path start, String name, List<FileDialog.Filter> filters, Consumer<Path> picked,
                         Runnable cancelled) {
        answer(FileDialog.saveAsync(app::post, app.windowHandle(), filters, start, name), picked, cancelled);
    }

    private void answer(CompletableFuture<Optional<Path>> dialog, Consumer<Path> picked, Runnable cancelled) {
        dialog.whenCompleteAsync((answer, failure) -> {
            if (failure != null) {
                // A dialog that cannot be shown is a cancel, so whoever asked is not left waiting.
                LOG.warn("a file dialog could not be shown", failure);
                cancelled.run();
            } else if (answer.isPresent()) {
                picked.accept(answer.get());
            } else {
                cancelled.run();
            }
        }, answers);
    }

    /**
     * Release NFD on this thread, at shutdown on the main thread. {@link Nfd#quit} releases only the calling thread's
     * own initialisation, so it matters on macOS, where the pickers ran there, and is a no-op on Windows.
     */
    @Override
    public void close() {
        Nfd.quit();
    }
}

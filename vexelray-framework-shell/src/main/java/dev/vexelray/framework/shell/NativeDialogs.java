package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.nfd.Nfd;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import sibarum.atchung.Sender;
import sibarum.probe.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The framework's {@link Dialogs}: {@link Modals} for a question, NFD's native pickers for the rest, all parented to
 * the main window. Made at {@code ATTACH}, when there is a window to parent them to.
 *
 * <p>Where a picker runs is {@code FileDialog}'s decision, not this class's: on Windows it is NFD's own dialog thread,
 * so the window keeps drawing behind it, and on macOS AppKit insists on the main thread, so the request is posted to
 * the frame loop. Either way the answer is a {@link Picked} sent from wherever the picker finished, and nothing else
 * runs there: whoever asked hears it on its own lane, as it hears every message. That is why there is no thread of
 * this class's own any more: it ran the application's callback until phase 5, and a publish needs no thread.
 *
 * <p>This is the text editor's {@code NativeDialogs}, moved here: it was written once and then needed by Vexplore's
 * folder chooser and Pix's opener, each of which took the {@code GuiApp} to reach it.
 */
final class NativeDialogs implements Dialogs, AutoCloseable {

    private static final Log LOG = Log.of("framework.dialogs");

    private final GuiApp app;
    private final Modals modals;

    NativeDialogs(GuiApp app, Modals modals) {
        this.app = app;
        this.modals = modals;
    }

    @Override
    public void ask(Modal modal) {
        modals.present(modal);
    }

    @Override
    public void pick(PickFile request, Sender<Picked> answer) {
        request.to(pickers).whenComplete((path, failure) -> {
            if (failure != null) {
                // A picker that cannot be shown is a cancel, so whoever asked is not left waiting.
                LOG.warn("a file dialog could not be shown", failure);
                answer.send(Picked.cancelled(request.id()));
            } else {
                answer.send(path.map(p -> new Picked(request.id(), p.toString()))
                        .orElseGet(() -> Picked.cancelled(request.id())));
            }
        });
    }

    /** NFD's picker for each kind of request, parented to the main window. */
    private final PickFile.Sink<CompletableFuture<Optional<Path>>> pickers = new PickFile.Sink<>() {
        @Override
        public CompletableFuture<Optional<Path>> open(PickFile p) {
            return FileDialog.openAsync(app::post, app.windowHandle(), filters(p.filters()), start(p));
        }

        @Override
        public CompletableFuture<Optional<Path>> folder(PickFile p) {
            return FileDialog.pickFolderAsync(app::post, app.windowHandle(), start(p));
        }

        @Override
        public CompletableFuture<Optional<Path>> save(PickFile p) {
            return FileDialog.saveAsync(app::post, app.windowHandle(), filters(p.filters()), start(p), p.name());
        }
    };

    private static Path start(PickFile p) {
        return p.start().isEmpty() ? null : Path.of(p.start());
    }

    private static List<FileDialog.Filter> filters(List<PickFile.Filter> filters) {
        List<FileDialog.Filter> out = new ArrayList<>(filters.size());
        for (PickFile.Filter f : filters) {
            out.add(new FileDialog.Filter(f.description(), f.extensions()));
        }
        return out;
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

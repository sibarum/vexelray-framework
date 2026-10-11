package dev.vexelray.framework.shell;

import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What {@link Shell#dialogs()} hands out: one value from the first phase to the last, asking whichever dialogs answer
 * — the application's, when it provided some, or the framework's from {@code ATTACH}. A question asked before either
 * exists is held and asked when one does, in the order it came.
 *
 * <p>The same value throughout, rather than the dialogs themselves, because a part takes {@code Dialogs} in whatever
 * phase it is built in, and the framework's own cannot exist until there is a window to parent them to. A part built
 * in {@code MODEL} that kept the dialogs it was given would otherwise have kept nothing.
 */
final class HeldDialogs implements Dialogs {

    /** Guarded by {@code this}, with {@link #held}. */
    private Dialogs target;
    private final List<Consumer<Dialogs>> held = new ArrayList<>();

    /** Ask {@code target} from now on, after everything held. Once. */
    synchronized void target(Dialogs target) {
        if (this.target != null) {
            throw new IllegalStateException("the dialogs are already decided");
        }
        this.target = target;
        // Under the lock, so a question asked meanwhile cannot overtake one asked earlier. Each only queues or
        // posts, so holding the lock across them costs nothing worth avoiding.
        for (Consumer<Dialogs> question : held) {
            question.accept(target);
        }
        held.clear();
    }

    /** Whether something is answering yet. */
    synchronized boolean decided() {
        return target != null;
    }

    private void put(Consumer<Dialogs> question) {
        Dialogs t;
        synchronized (this) {
            t = target;
            if (t == null) {
                held.add(question);
                return;
            }
        }
        question.accept(t);
    }

    @Override
    public void ask(Modal modal) {
        put(d -> d.ask(modal));
    }

    @Override
    public void openFile(Path start, List<FileDialog.Filter> filters, Consumer<Path> picked, Runnable cancelled) {
        put(d -> d.openFile(start, filters, picked, cancelled));
    }

    @Override
    public void openFolder(Path start, Consumer<Path> picked, Runnable cancelled) {
        put(d -> d.openFolder(start, picked, cancelled));
    }

    @Override
    public void saveFile(Path start, String name, List<FileDialog.Filter> filters, Consumer<Path> picked,
                         Runnable cancelled) {
        put(d -> d.saveFile(start, name, filters, picked, cancelled));
    }
}

package ${packageName};

import ${packageName}.core.Markdown;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Tabs;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The open files: a {@link Tabs} bar, one {@link Buffer} per tab, and the bookkeeping that keeps the two in step.
 *
 * <h2>Changed on one lane, so no lock</h2>
 *
 * <p>Tabs are added, closed, fronted and cycled only by the {@link View}, on its lane, one message at a time, so the
 * bar and the buffer list cannot be changed by two threads at once and there is no lock to take. What is read from
 * elsewhere is made for it: the buffer list is copied on write, so the tab menu built on a worker reads a whole
 * snapshot, and the tab in front is a volatile the caret's listener reads.
 *
 * <h2>The bar never closes a tab on its own</h2>
 *
 * <p>{@link Tabs#closable} is off. The bar's own Close removes a tab the moment it is chosen, and a tab with unsaved
 * work has to be asked about first; the editor asks, and then tells the view to {@link #close}.
 */
final class Workspace {

    private final Gui gui;
    private final Motion motion;
    private final Markdown.Style markup;
    private final Consumer<Buffer.Position> caret;
    private final Tabs tabs;
    private final Node hint;
    private final Node root;
    private final Color accent;

    /** Parallel to the bar's tabs. Written on the view's lane only. */
    private final List<Buffer> buffers = new CopyOnWriteArrayList<>();
    private final Map<Long, Buffer> byId = new ConcurrentHashMap<>();
    private final Map<Long, Node> headers = new ConcurrentHashMap<>();
    /** The tab in front, for the caret's listener, which runs on whatever thread moved the caret. */
    private volatile long front = -1;
    private volatile BiConsumer<Long, Boolean> edited = (id, dirty) -> { };

    Workspace(Gui gui, Motion motion, Markdown.Style markup, Consumer<Buffer.Position> caret) {
        this.gui = gui;
        this.motion = motion;
        this.markup = markup;
        this.caret = caret;
        this.accent = gui.theme().color(Role.ACCENT);

        tabs = new Tabs(gui).closable(false).slide(motion.change);
        tabs.node().width(Length.FILL).height(Length.grow(1f)).visible(false);
        gui.landmark(Landmarks.TABS, tabs.node());

        Node empty = gui.text("Open a file from the navigator, or type a path above it (Ctrl+L)")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.EMPTY, empty);
        hint = gui.row().width(Length.FILL).height(Length.grow(1f))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .children(empty);

        root = gui.column().width(Length.FILL).height(Length.FILL).children(hint, tabs.node());
    }

    Node node() {
        return root;
    }

    Tabs tabs() {
        return tabs;
    }

    /** Told whenever a tab's text goes unsaved or comes back to what is on disk. */
    void onEdited(BiConsumer<Long, Boolean> listener) {
        this.edited = listener;
    }

    /** Open {@code text} as {@code path} in a new tab, in front. {@code focus}: whether it takes the keyboard. */
    Buffer add(long id, Path path, String text, boolean focus) {
        Buffer buffer = new Buffer(gui, id, path, text, Markdown.handles(String.valueOf(path.getFileName())) ? markup : null,
                dirty -> edited.accept(id, dirty),
                position -> {
                    if (front == id) {
                        caret.accept(position);
                    }
                });
        tabs.add(buffer.name(), buffer.field.node());
        int index = buffers.size();
        buffers.add(buffer);
        byId.put(id, buffer);
        headers.put(id, tabs.header(index));
        front = id;
        tabs.select(index);
        showing(true);
        if (focus) {
            gui.focus(buffer.field.node());
        }
        return buffer;
    }

    /** Close tab {@code id} without asking: the editor has already asked. */
    void close(long id) {
        Buffer gone = byId.remove(id);
        if (gone == null) {
            return;
        }
        int i = buffers.indexOf(gone);
        buffers.remove(i);
        tabs.remove(i);
        headers.remove(id);
        showing(!buffers.isEmpty());
        gone.close();
    }

    /** The bar and its pages, or the hint that says how to open something. */
    private void showing(boolean tabsShown) {
        tabs.node().visible(tabsShown);
        hint.visible(!tabsShown);
    }

    /** The file in front, or null. */
    Buffer front() {
        List<Buffer> all = buffers;
        int i = tabs.selected();
        return i >= 0 && i < all.size() ? all.get(i) : null;
    }

    /** The bar's selection moved: the caret now follows the file in front, which is returned. */
    Buffer fronted() {
        Buffer b = front();
        if (b != null) {
            front = b.id;
            caret.accept(Buffer.position(b.field.document().value()));
        }
        return b;
    }

    /** The open tab for {@code file}, or null. */
    Buffer find(Path file) {
        Path want = file.toAbsolutePath().normalize();
        for (Buffer b : buffers) {
            if (b.path.equals(want)) {
                return b;
            }
        }
        return null;
    }

    Buffer byId(long id) {
        return byId.get(id);
    }

    /** Every open file, in tab order: a snapshot, safe from any thread. */
    List<Buffer> all() {
        return List.copyOf(buffers);
    }

    /**
     * Bring an open tab to the front (asked for a file that is already open) and ring its header, so the answer to
     * "where did it go" is visible.
     */
    void show(Buffer buffer, boolean focus) {
        int i = buffers.indexOf(buffer);
        if (i >= 0) {
            front = buffer.id;
            tabs.select(i);
        }
        Node header = headers.get(buffer.id);
        if (header != null) {
            motion.cues.play(header, Cue.ring(accent, 1));
        }
        if (focus) {
            gui.focus(buffer.field.node());
        }
    }

    /** Move {@code delta} tabs along the bar, wrapping at the ends. */
    void cycle(int delta) {
        int n = buffers.size();
        if (n < 2) {
            return;
        }
        int i = Math.floorMod(tabs.selected() + delta, n);
        Buffer next = buffers.get(i);
        front = next.id;
        tabs.select(i);
        gui.focus(next.field.node());
    }

    /** What a tab's header says, as the editor decided it. */
    void retitle(long id, String title) {
        Node header = headers.get(id);
        if (header != null) {
            header.text(title);
        }
    }
}

package ${packageName};

import ${packageName}.Intent.Command;
import ${packageName}.core.Messages;
import ${packageName}.core.Messages.AddTab;
import ${packageName}.core.Messages.Answer;
import ${packageName}.core.Messages.Ask;
import ${packageName}.core.Messages.Caption;
import ${packageName}.core.Messages.Choice;
import ${packageName}.core.Messages.CloseTab;
import ${packageName}.core.Messages.Closing;
import ${packageName}.core.Messages.Flush;
import ${packageName}.core.Messages.FrontTab;
import ${packageName}.core.Messages.Fronted;
import ${packageName}.core.Messages.Go;
import ${packageName}.core.Messages.Open;
import ${packageName}.core.Messages.Purpose;
import ${packageName}.core.Messages.QuitAnswer;
import ${packageName}.core.Messages.Retitle;
import ${packageName}.core.Messages.Save;
import ${packageName}.core.Messages.SaveFailed;
import ${packageName}.core.Messages.ShowFolder;
import ${packageName}.core.Messages.Status;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.widget.Modal;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The view: what the editor decided, shown, and what the person did, told. On a lane of its own.
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Every decision is the {@code Editor}'s, in {@code -core}: whether a file is already open, what a typed path names,
 * whether closing needs a question. This turns the editor's messages into widgets ({@code AddTab} is a tab,
 * {@code Ask} is a dialog) and the person's gestures into messages ({@code Open}, {@code Closing}, {@code Save}).
 *
 * <h2>Threads</h2>
 *
 * <p>Every {@code @Subscribe} here runs on the {@code view} lane, one at a time, and the tab list ({@link Workspace})
 * is changed only there, so it needs no lock. Input arrives elsewhere: a chord or a menu item on the handler lane, a
 * tab selection on whichever worker the bar delivers it. Those only send an {@link Intent} to this lane, or a message
 * straight to the editor when nothing of the view's is needed to say it. Writing a widget from here is the
 * framework's idiom: a prop written off the GUI thread is queued and applied by the next drain.
 */
@Component(lane = "view")
@Publishes({Messages.OPEN, Messages.GO, Messages.FRONTED, Messages.EDITED, Messages.CLOSING, Messages.SAVE,
        Messages.ANSWER, Intent.TOPIC})
final class View {

    /** As the editor's: a whole set of tabs is a message each, and a full mailbox ends the application. */
    static final int SET = 4096;

    private final Gui gui;
    private final Ui ui;
    private final Workspace ws;
    private final Navigator nav;
    private final Atchung bus;
    private final Dialogs dialogs;
    private final QuitGate gate;
    private long batches;

    View(Gui gui, Ui ui, Atchung bus, Dialogs dialogs, QuitGate gate) {
        this.gui = gui;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.bus = bus;
        this.dialogs = dialogs;
        this.gate = gate;

        ws.onEdited((id, dirty) -> bus.publish(Messages.EDITED_TOPIC, new Messages.Edited(id, dirty)));
        // Delivered on a worker, after the fact: the view asks what is in front now, on its own lane.
        ws.tabs().onSelect(i -> tell(Command.FRONTED, Messages.NO_TAB));
        ws.tabs().onContextMenu((index, menu) -> {
            List<Buffer> all = ws.all();
            if (index < 0 || index >= all.size()) {
                return;
            }
            long id = all.get(index).id;
            menu.item("Save", () -> tell(Command.SAVE, id))
                    .separator()
                    .item("Close", () -> tell(Command.CLOSE, id))
                    .item("Close others", all.size() > 1, () -> tell(Command.CLOSE_OTHERS, id))
                    .item("Close all", () -> tell(Command.CLOSE_ALL, id))
                    .separator()
                    .item("Reveal in navigator", () -> tell(Command.REVEAL, id));
        });
        // From the tree the keyboard stays in the tree, so walking it with the arrow keys keeps walking.
        nav.onOpenFile(file -> bus.publish(Messages.OPEN_TOPIC, new Open(file, false)));
        nav.onGo(text -> bus.publish(Messages.GO_TOPIC, new Go(text)));
        shortcuts();
    }

    /**
     * Every chord, as a {@code GLOBAL} claim. Claims rather than key handlers, which is how this framework does
     * preemption: a focused field outranks a global claim by claiming the same chord itself, so the chords chosen here
     * are ones a text field leaves alone. Moving the keyboard needs nothing of the view's, so those two act at once.
     */
    private void shortcuts() {
        gui.shortcut(Key.S, () -> tell(Command.SAVE_FRONT, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.W, () -> tell(Command.CLOSE_FRONT, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.L, nav::focusPath, Modifier.CONTROL);
        gui.shortcut(Key.E, nav::focusTree, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.TAB, () -> tell(Command.NEXT_TAB, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.TAB, () -> tell(Command.PREVIOUS_TAB, Messages.NO_TAB), Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.PAGE_DOWN, () -> tell(Command.NEXT_TAB, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.PAGE_UP, () -> tell(Command.PREVIOUS_TAB, Messages.NO_TAB), Modifier.CONTROL);
    }

    private void tell(Command command, long id) {
        bus.publish(Intent.INTENTS, new Intent(command, id));
    }

    @Subscribe(topic = Intent.TOPIC)
    public void intent(Intent intent) {
        intent.command().on(this, intent.id());
    }

    // ------------------------------------------------------------------ what the editor says

    @Subscribe(topic = Messages.SHOW_FOLDER)
    public void showFolder(ShowFolder m) {
        nav.show(m.path());
    }

    @Subscribe(topic = Messages.ADD_TAB)
    public void addTab(AddTab m) {
        ws.add(m.id(), m.path(), m.text(), m.focus());
    }

    @Subscribe(topic = Messages.FRONT_TAB)
    public void frontTab(FrontTab m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            ws.show(b, m.focus());
        }
    }

    @Subscribe(topic = Messages.CLOSE_TAB, capacity = SET)
    public void closeTab(CloseTab m) {
        ws.close(m.id());
    }

    @Subscribe(topic = Messages.RETITLE, capacity = SET)
    public void retitle(Retitle m) {
        ws.retitle(m.id(), m.title());
    }

    @Subscribe(topic = Messages.CAPTION)
    public void caption(Caption m) {
        ui.caption(m.file());
    }

    @Subscribe(topic = Messages.STATUS)
    public void status(Status m) {
        ui.status(m.message(), m.alert());
    }

    /** A question, as the framework's dialog: its answer goes back to the editor, from whichever thread presses it. */
    @Subscribe(topic = Messages.ASK)
    public void ask(Ask m) {
        dialogs.ask(Modal.of(m.title(), m.message())
                .defaultButton(m.confirm(), () -> answer(m, Choice.CONFIRM))
                .button(m.other(), () -> answer(m, Choice.OTHER))
                .cancelButton("Cancel", () -> answer(m, Choice.CANCEL)));
    }

    private void answer(Ask m, Choice choice) {
        bus.publish(Messages.ANSWER_TOPIC, new Answer(m.question(), choice));
    }

    @Subscribe(topic = Messages.FLUSH, capacity = SET)
    public void flush(Flush m) {
        Buffer b = ws.byId(m.id());
        if (b == null) {
            bus.publish(Messages.SAVE_TOPIC, new Save(m.id(), "", false));
        } else {
            save(b);
        }
    }

    @Subscribe(topic = Messages.SAVE_FAILED)
    public void saveFailed(SaveFailed m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            b.saveFailed();
        }
    }

    @Subscribe(topic = Messages.QUIT_ANSWER)
    public void quitAnswer(QuitAnswer m) {
        gate.answer(m.gate(), m.proceed());
    }

    // ------------------------------------------------------------------ what the person did, on this lane

    void saveFront() {
        Buffer b = ws.front();
        if (b != null) {
            save(b);
        }
    }

    void save(long id) {
        Buffer b = ws.byId(id);
        if (b != null) {
            save(b);
        }
    }

    /** The text, taken now and marked saved now: what is written is what the document said when Save was asked for. */
    private void save(Buffer b) {
        bus.publish(Messages.SAVE_TOPIC, new Save(b.id, b.takeForSave(), true));
    }

    void closeFront() {
        Buffer b = ws.front();
        if (b != null) {
            closing(List.of(b), ++batches, Purpose.TABS);
        }
    }

    void close(long id) {
        Buffer b = ws.byId(id);
        if (b != null) {
            closing(List.of(b), ++batches, Purpose.TABS);
        }
    }

    void closeOthers(long keep) {
        List<Buffer> rest = new ArrayList<>(ws.all());
        rest.removeIf(b -> b.id == keep);
        closing(rest, ++batches, Purpose.TABS);
    }

    void closeAll() {
        closing(ws.all(), ++batches, Purpose.TABS);
    }

    /** The window was asked to close: tell the editor every tab, and whether each is unsaved now. */
    void quit(long ticket) {
        List<Buffer> all = ws.all();
        if (all.isEmpty()) {
            bus.publish(Messages.CLOSING_TOPIC, new Closing(ticket, 1, Messages.NO_TAB, false, Purpose.QUIT));
        } else {
            closing(all, ticket, Purpose.QUIT);
        }
    }

    /** One {@link Closing} per tab, each carrying how many make the set and whether its tab is unsaved now. */
    private void closing(List<Buffer> buffers, long batch, Purpose purpose) {
        for (Buffer b : buffers) {
            bus.publish(Messages.CLOSING_TOPIC, new Closing(batch, buffers.size(), b.id, b.dirty(), purpose));
        }
    }

    void cycle(int delta) {
        ws.cycle(delta);
    }

    /** The bar's selection moved: the caret follows the tab in front, and the editor hears which it is. */
    void fronted() {
        Buffer b = ws.fronted();
        if (b != null) {
            bus.publish(Messages.FRONTED_TOPIC, new Fronted(b.id));
        }
    }

    /** Unfold the tree to a tab's file. Nothing to decide, so the view says itself when it cannot. */
    void reveal(long id) {
        Buffer b = ws.byId(id);
        if (b != null && !nav.reveal(b.path)) {
            ui.status(b.name() + " is not inside the folder shown", false);
        }
    }
}

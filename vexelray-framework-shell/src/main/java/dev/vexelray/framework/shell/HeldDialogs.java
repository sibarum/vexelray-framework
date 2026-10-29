package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.widget.Modal;
import sibarum.atchung.Atchung;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What {@link Shell#dialogs()} hands out, and where every question goes: the framework's dialog component. One value
 * from the first phase to the last, asking whichever dialogs answer, the application's when it provided some or the
 * framework's from {@code ATTACH}.
 *
 * <p><b>A mailbox, not a lock.</b> Every question arrives as a message in the main window's frame: an {@link Ask} or
 * a {@link PickFile} from any part, and a {@link Modal} handed to {@link #ask} from any thread, which is published to
 * the same frame. So everything here past the publish runs on the main thread, one question at a time, in the order
 * they were asked, and a question asked before anything can answer it is held there and asked when something can.
 * Until {@link #install} (the {@code GUI} phase, before any lane runs or any part is built that could ask from
 * another thread) there is no frame, and a question asked meanwhile waits in a list until there is one.
 */
final class HeldDialogs implements Dialogs {

    /** How many questions may wait for one frame: far more than a person is ever asked at once. */
    private static final int CAPACITY = 64;

    /** A {@code Modal} on its way to the frame. The framework's own channel, so no application declares it. */
    private static final Topic<Modal> MODALS = Topic.edge(Modal.class);

    private final Atchung bus;
    private final Sender<Answer> answers;
    private final Sender<Picked> picked;

    /** What was asked before {@link #install}, to publish then: startup, on the main thread alone. */
    private List<Runnable> early = new ArrayList<>();

    // Main thread, in the frame, from here down.
    private Dialogs target;
    private final List<Consumer<Dialogs>> held = new ArrayList<>();

    HeldDialogs(Atchung bus) {
        this.bus = bus;
        Senders senders = new Senders(bus);
        this.answers = senders.to(Answer.TOPIC);
        this.picked = senders.to(Picked.TOPIC);
    }

    /** Hear the questions in {@code gui}'s frame, the main window's, from now on. At {@code GUI}, main thread. */
    void install(Gui gui) {
        gui.mailbox(MODALS, this::show, CAPACITY);
        gui.mailbox(Ask.TOPIC, this::asked, CAPACITY);
        gui.mailbox(PickFile.TOPIC, this::picker, CAPACITY);
        List<Runnable> before = early;
        early = null;
        for (Runnable publish : before) {
            publish.run();
        }
    }

    /** Ask {@code target} from now on, after everything held. Once; on the main thread, or before the first frame. */
    void target(Dialogs target) {
        if (this.target != null) {
            throw new IllegalStateException("the dialogs are already decided");
        }
        this.target = target;
        for (Consumer<Dialogs> question : held) {
            question.accept(target);
        }
        held.clear();
    }

    /** Whether something is answering yet. */
    boolean decided() {
        return target != null;
    }

    @Override
    public void ask(Modal modal) {
        publish(MODALS, modal);
    }

    /**
     * {@code request}, as if it were sent as a message, which is how a part should ask: the answer is published on
     * {@link Picked#TOPIC}, which is the only channel a {@code Sender<Picked>} can name.
     */
    @Override
    public void pick(PickFile request, Sender<Picked> answer) {
        publish(PickFile.TOPIC, request);
    }

    /** Publish to the frame, or hold it until {@link #install} when there is no frame yet. */
    private <T> void publish(Topic<T> topic, T message) {
        if (early != null) {
            early.add(() -> bus.publish(topic, message));
        } else {
            bus.publish(topic, message);
        }
    }

    private void picker(PickFile request) {
        put(d -> d.pick(request, picked));
    }

    private void show(Modal modal) {
        put(d -> d.ask(modal));
    }

    /** An {@link Ask}, as a dialog whose buttons each send its {@link Answer}. */
    private void asked(Ask a) {
        Modal modal = Modal.of(a.title(), a.message());
        int last = a.buttons().size() - 1;
        for (int i = 0; i <= last; i++) {
            Answer pressed = new Answer(a.id(), i);
            if (i == 0) {
                modal.defaultButton(a.buttons().get(i), answers, pressed);
            } else if (i == last) {
                modal.cancelButton(a.buttons().get(i), answers, pressed);
            } else {
                modal.button(a.buttons().get(i), answers, pressed);
            }
        }
        show(modal);
    }

    private void put(Consumer<Dialogs> question) {
        if (target == null) {
            held.add(question);
        } else {
            question.accept(target);
        }
    }
}

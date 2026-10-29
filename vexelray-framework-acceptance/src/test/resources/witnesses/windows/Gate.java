package ${packageName};

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowOpened;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ask the person the witness's question, please: "Count one more?" */
record Question() {
    static final Topic<Question> TOPIC = Topic.edge(Question.class);
}

/**
 * The witness's questions, on the logic lane. It hears every window's close request, since a close is a question as
 * soon as any part hears one: the palette's is put to the person, and every other window's is answered yes at once.
 * And it asks the witness's own question, counting on its default button.
 *
 * <p>In 0.1 the palette's window took an {@code onClose} callback and the question was a {@code Modal} whose buttons
 * ran code; here both are messages, and what is waiting on an answer is this component's own map.
 */
@Component
final class Gate {

    /** What the launch asked for: a window to open as the main one does, and whether to ask the question then. */
    private final String open;
    private final String ask;
    private final Sender<Ask> asks;
    private final Sender<CloseAnswer> closes;
    private final Sender<Bump> bump;
    private final Sender<WindowCommand> windows;
    /** A palette close waiting on the person: the question's id, and the close's ticket. */
    private final Map<Long, Long> closing = new HashMap<>();
    private final Set<Long> questions = new HashSet<>();
    private long asked;

    Gate(@Setting(value = "witness.open", def = "") String open, @Setting(value = "witness.ask", def = "") String ask,
         Sender<Ask> asks, Sender<CloseAnswer> closes, Sender<Bump> bump,
         Sender<WindowCommand> windows) {
        this.open = open;
        this.ask = ask;
        this.asks = asks;
        this.closes = closes;
        this.bump = bump;
        this.windows = windows;
    }

    /** As the main window opens: what the launch asked for. */
    @Subscribe
    public void opened(WindowOpened w) {
        if (!w.main()) {
            return;
        }
        if (open.equals(Notes.KEY)) {
            windows.send(WindowCommand.show(Notes.KEY));
        }
        if (ask.equals("early")) {
            question(new Question());
        }
    }

    @Subscribe
    public void question(Question q) {
        long id = ++asked;
        questions.add(id);
        asks.send(new Ask(id, "Witness question", "Count one more?", List.of("Count", "Cancel")));
    }

    @Subscribe
    public void closeRequested(CloseRequested r) {
        if (!r.window().equals(Palette.KEY)) {
            closes.send(new CloseAnswer(r.ticket(), true));
            return;
        }
        long id = ++asked;
        closing.put(id, r.ticket());
        asks.send(new Ask(id, "Close the palette?", "It keeps its count either way.", List.of("Close", "Cancel")));
    }

    @Subscribe
    public void answered(Answer a) {
        Long ticket = closing.remove(a.id());
        if (ticket != null) {
            closes.send(new CloseAnswer(ticket, a.button() == 0));
        } else if (questions.remove(a.id()) && a.button() == 0) {
            bump.send(new Bump());
        }
    }
}

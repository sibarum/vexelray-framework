package ${packageName}.core.examples.later;

import ${packageName}.core.examples.later.LaterMessages.NeverMind;
import ${packageName}.core.examples.later.LaterMessages.RemindMe;
import ${packageName}.core.examples.later.LaterMessages.Reminders;
import ${packageName}.core.examples.later.LaterMessages.Settled;
import ${packageName}.core.examples.later.LaterMessages.Typed;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import sibarum.atchung.Broken;
import sibarum.atchung.Chain;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;
import sibarum.atchung.Quiet;
import sibarum.atchung.Step;

import java.time.Duration;

/**
 * <b>Example: do something later, or once the typing stops.</b> Time is a branch of a chain: a step never sleeps,
 * starts a timer or asks for a message later. It ends, and the chain waits.
 *
 * <p><b>Flow:</b> the view sends {@code Typed} at each keystroke, on the logic lane here; a chain waits for no
 * {@code Typed} within {@link #QUIET} ({@code .none(...).within(...)}), so each keystroke breaks the run before it and
 * only the last one's wait goes quiet, and its step sends the view {@code Settled}. A reminder is the same wait on
 * another message: no {@code NeverMind} within {@link #REMINDER}, and the reminder is due; a {@code NeverMind} breaks
 * the wait, and the run goes on with {@code Broken}, which its own step takes.
 *
 * <p><b>The trap:</b> a wait hears only what arrives after it is entered, so the keystroke that started a run never
 * breaks its own wait; and an outcome no link takes ends the run quietly, which is what a broken search wait wants and
 * what a reminder must not do (so it says {@code .or(Broken.class, ...)}). The reasoning is the framework's
 * {@code docs/plans/chains.md}, <i>Ruled 2026-11-02: chains as trees, with bounded waits</i>.
 *
 * <p>Every so often (a beat, a poll) is the framework's {@code Timer}, not a chain: a chain has no repeat.
 */
@Component
@Hears({Typed.class, RemindMe.class, NeverMind.class})
@Publishes({Settled.class, Reminders.class})
public final class Later {

    /** How long the typing has to stop before a search: long enough to skip a word's keystrokes, short enough not to wait. */
    public static final Duration QUIET = Duration.ofMillis(400);

    /** How long a reminder waits. */
    public static final Duration REMINDER = Duration.ofSeconds(3);

    /** A person types one keystroke at a time; a mailbox this deep is never full. */
    static final int CAPACITY = 64;

    /**
     * Everything this example knows: the search field's text and how many keystrokes it has had since the last search,
     * and how many reminders are waiting.
     */
    public record State(String typed, int keystrokes, int waiting) {

        static final State EMPTY = new State("", 0, 0);
    }

    private final Ledger<State> ledger;

    public Later(Inbox inbox) {
        Ledger.Builder<State> b = Ledger.of(State.EMPTY);
        Step<State, Typed> typed = b.step("typed", Later::typed);
        Step<State, Quiet> settled = b.step("settled", Later::settled);
        Step<State, RemindMe> remind = b.step("remind", Later::remind);
        Step<State, Quiet> due = b.step("due", Later::due);
        Step<State, Broken> calledOff = b.step("calledOff", Later::calledOff);
        ledger = b.build(inbox, "later", CAPACITY);

        Chain.start("search as you type", ledger, typed).on(LaterMessages.TYPED_TOPIC)
                .none(LaterMessages.TYPED_TOPIC).within(QUIET)
                .then(Quiet.class, ledger, settled)
                .build();
        Chain.start("remind me", ledger, remind).on(LaterMessages.REMIND_ME_TOPIC)
                .none(LaterMessages.NEVER_MIND_TOPIC).within(REMINDER)
                .then(Quiet.class, ledger, due)
                .or(Broken.class, ledger, calledOff)
                .build();
    }

    /** What this example knows, as last committed: for reading, from any thread. */
    public State state() {
        return ledger.state().value();
    }

    private static State typed(State s, Typed t, Out out) {
        out.result(t);
        return new State(t.text(), s.keystrokes() + 1, s.waiting());
    }

    /** No keystroke for {@link #QUIET}: search for what is there now. An application sends its search job here. */
    private static State settled(State s, Quiet quiet, Out out) {
        out.send(LaterMessages.SETTLED_TOPIC, new Settled(s.typed(), s.keystrokes()));
        return new State(s.typed(), 0, s.waiting());
    }

    private static State remind(State s, RemindMe r, Out out) {
        out.result(r);
        int waiting = s.waiting() + 1;
        out.send(LaterMessages.REMINDERS_TOPIC,
                new Reminders(waiting, "Reminding you in " + REMINDER.toSeconds() + " seconds"));
        return new State(s.typed(), s.keystrokes(), waiting);
    }

    private static State due(State s, Quiet quiet, Out out) {
        int waiting = s.waiting() - 1;
        out.send(LaterMessages.REMINDERS_TOPIC,
                new Reminders(waiting, "Reminder: " + REMINDER.toSeconds() + " seconds have passed"));
        return new State(s.typed(), s.keystrokes(), waiting);
    }

    private static State calledOff(State s, Broken by, Out out) {
        int waiting = s.waiting() - 1;
        out.send(LaterMessages.REMINDERS_TOPIC, new Reminders(waiting, "Never mind: the reminder was called off"));
        return new State(s.typed(), s.keystrokes(), waiting);
    }
}

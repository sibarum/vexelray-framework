package ${packageName}.core.examples.later;

import ${packageName}.core.examples.later.LaterMessages.NeverMind;
import ${packageName}.core.examples.later.LaterMessages.RemindMe;
import ${packageName}.core.examples.later.LaterMessages.Reminders;
import ${packageName}.core.examples.later.LaterMessages.Settled;
import ${packageName}.core.examples.later.LaterMessages.Typed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;
import sibarum.atchung.HandClock;
import sibarum.atchung.Inbox;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Later} alone, on a tick of this test's with a clock of this test's: time passes only when a test says
 * ({@code clock.advance}), so a 3-second reminder is tested in no time at all, and in the same order every run. A
 * default bound that runs out fails the test, so a wait left unbounded by mistake would show here.
 */
class LaterTest {

    private Fatal previous;
    private Atchung bus;
    private Tick tick;
    private HandClock clock;
    private Later later;
    /** Everything the example sent, in order. */
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        previous = Atchung.fatal();
        Atchung.onFatal(Fatal.THROW);
        bus = Atchung.create();
        clock = new HandClock(bus);
        tick = bus.tick(null).alarm(clock).whenOverdue(Tick.Overdue.FAIL);
        later = new Later(tick.inbox("later"));
        Inbox heard = tick.inbox("heard");
        heard.subscribe(LaterMessages.SETTLED_TOPIC, sent::add, 64);
        heard.subscribe(LaterMessages.REMINDERS_TOPIC, sent::add, 64);
    }

    @AfterEach
    void restoreTheFatalPolicy() {
        Atchung.onFatal(previous);
    }

    /** Publish {@code message} as the view would, and run the tick until nothing is left to deliver. */
    private <T> void tell(Topic<T> topic, T message) {
        bus.publish(topic, message);
        settle();
    }

    /** Let {@code time} pass, and deliver what it made due. */
    private void wait(Duration time) {
        clock.advance(time);
        settle();
    }

    private void settle() {
        tick.run(System.nanoTime() + 60_000_000_000L);
    }

    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    @Test
    void aSearchWaitsUntilTheTypingStopsAndSearchesOnce() {
        tell(LaterMessages.TYPED_TOPIC, new Typed("c"));
        wait(Duration.ofMillis(150));
        tell(LaterMessages.TYPED_TOPIC, new Typed("ca"));
        wait(Duration.ofMillis(150));
        tell(LaterMessages.TYPED_TOPIC, new Typed("cat"));
        assertEquals(List.of(), took(Settled.class), "still typing");

        wait(Later.QUIET);

        assertEquals(List.of(new Settled("cat", 3)), took(Settled.class), "one search, for the last text");
    }

    @Test
    void theKeystrokeThatStartsAWaitDoesNotBreakIt() {
        tell(LaterMessages.TYPED_TOPIC, new Typed("x"));
        wait(Later.QUIET);
        assertEquals(List.of(new Settled("x", 1)), took(Settled.class));
    }

    @Test
    void aReminderIsDueWhenItsTimeHasPassed() {
        tell(LaterMessages.REMIND_ME_TOPIC, new RemindMe());
        assertEquals(List.of(new Reminders(1, "Reminding you in 3 seconds")), took(Reminders.class));

        wait(Later.REMINDER.minusMillis(1));
        assertEquals(List.of(), took(Reminders.class), "not yet");
        wait(Duration.ofMillis(1));

        assertEquals(List.of(new Reminders(0, "Reminder: 3 seconds have passed")), took(Reminders.class));
    }

    @Test
    void neverMindCallsOffEveryReminderStillWaiting() {
        tell(LaterMessages.REMIND_ME_TOPIC, new RemindMe());
        wait(Duration.ofSeconds(1));
        tell(LaterMessages.REMIND_ME_TOPIC, new RemindMe());
        took(Reminders.class);

        tell(LaterMessages.NEVER_MIND_TOPIC, new NeverMind());
        assertEquals(List.of(new Reminders(1, "Never mind: the reminder was called off"),
                new Reminders(0, "Never mind: the reminder was called off")), took(Reminders.class));

        wait(Later.REMINDER);
        assertEquals(List.of(), took(Reminders.class), "a called-off reminder is never due");
        assertEquals(0, later.state().waiting());
    }
}

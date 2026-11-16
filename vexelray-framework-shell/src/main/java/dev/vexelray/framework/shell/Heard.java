package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * <b>Every message a headless test heard on one topic</b>, in the order it was sent: what {@link Frames#hear} hands back.
 *
 * <p>It fills as {@link Frames} runs frames, and only then, on the thread that runs them: a message sent between two
 * frames is here after the second. So a test reads it as it reads the view, on its own thread, with no lock and no
 * concurrent list, and what it reads holds still until the next frame. Every message, a state topic's too: a view hears
 * only a state topic's last write per key in a frame, a test hears each one.
 *
 * {@snippet :
 * Heard<Caption> captions = frames.hear(Messages.CAPTION_TOPIC);
 * shell.bus().publish(Messages.OPEN_TOPIC, new Open(file, true));
 * frames.untilHeard(captions, new Caption("a.txt"));
 * assertEquals(new Caption("a.txt"), captions.last());
 * }
 *
 * @param <T> the topic's payload
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Heard<T> {

    private final Topic<T> topic;
    /** The thread that runs the frames is the only one that writes or reads this. */
    private final List<T> messages = new ArrayList<>();

    Heard(Topic<T> topic) {
        this.topic = topic;
    }

    /** What it hears. */
    public Topic<T> topic() {
        return topic;
    }

    /** Everything heard so far, oldest first: a copy, so it does not change under a test that holds it. */
    public List<T> all() {
        return List.copyOf(messages);
    }

    /** Whether {@code message} was heard, by {@code equals}: a record is compared by its fields. */
    public boolean contains(T message) {
        return messages.contains(message);
    }

    /** Whether nothing has been heard yet. */
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    /** How many messages have been heard. */
    public int size() {
        return messages.size();
    }

    /**
     * The latest message heard.
     *
     * @throws NoSuchElementException when nothing has been heard yet, saying on which topic: wait for one first, with
     *                                {@link Frames#until} or {@link Frames#untilHeard}
     */
    public T last() {
        if (messages.isEmpty()) {
            throw new NoSuchElementException("nothing heard on " + topic.name() + " yet: wait for it first, with "
                    + "frames.until(...) or frames.untilHeard(...)");
        }
        return messages.getLast();
    }

    /** Called by {@link Frames} as it runs a frame, on its thread. */
    void add(T message) {
        messages.add(message);
    }

    /** The topic and what was heard on it, the latest few: what a failed wait says. */
    @Override
    public String toString() {
        int n = messages.size();
        if (n == 0) {
            return topic.name() + ": nothing";
        }
        int from = Math.max(0, n - SHOWN);
        return topic.name() + ": " + n + (n == 1 ? " message" : " messages")
                + (from > 0 ? ", the last " + SHOWN + " " : " ") + messages.subList(from, n);
    }

    /** How many of the latest messages a failure shows: enough to see what came instead, few enough to read. */
    private static final int SHOWN = 5;
}

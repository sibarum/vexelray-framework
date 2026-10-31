package dev.vexelray.framework.shell;

import sibarum.atchung.Inbox;
import sibarum.atchung.Sender;
import sibarum.atchung.Subscriber;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What the generated wiring calls to put a component on the logic lane ({@link Logic}): its inbox, its
 * {@code @Subscribe} methods' mailboxes, and its senders. An application does not call it: a component asks for its
 * inbox by taking an {@link Inbox} in its constructor, declares a mailbox with {@code @Subscribe}, and declares a send by
 * taking a {@link Sender} in its constructor, and the processor writes each call.
 *
 * <p><b>Not for application code, and that is a convention rather than a wall.</b> Java has no visibility between
 * "this package" and "everyone", and the generated wiring lives in the application's package, so the one door it needs
 * has to be public. It is this class: {@code Shell#inbox} is not, because an inbox an application makes by hand is
 * decided later than the wiring, and the processor cannot see it (T1.3).
 *
 * <p>Each call names the component's inbox and its lane, {@code Component.DEFAULT_LANE} for the logic lane's own share.
 * One component, one inbox, one lane: asking for it again on another lane is refused.
 */
public final class Inboxes {

    private Inboxes() {
    }

    /**
     * The inbox named {@code name} on {@code shell}'s logic lane, on {@code lane}, made the first time it is asked for.
     * {@code hears} is what the component declared it hears ({@code @Hears}, its payloads' topics); when the lane
     * starts, the inbox must be subscribed to exactly those, as well as its ledgers' own topics, or the application
     * does not start.
     */
    public static Inbox of(Shell shell, String name, String lane, Topic<?>... hears) {
        List<String> names = new ArrayList<>(hears.length);
        for (Topic<?> t : hears) {
            names.add(t.name());
        }
        return shell.inbox(name, lane, names);
    }

    /**
     * A mailbox for a component: {@code topic} delivered to {@code subscriber} on the component's inbox, at most
     * {@code capacity} edges queued (a state topic holds one message per key and cannot overflow). {@code keys}, when
     * any, are the keys of a state topic the subscription hears and the only ones it is handed (T4.8). The topic
     * is the payload's own declared constant, the one the {@code @Subscribe} method's parameter names, so its kind is the
     * one its declaration chose. {@code mailbox} is its name in the bus graph, {@code class#method}, which each
     * delivery's {@code receive} row says, so a reader following a message's cause sees which method it reached.
     */
    public static <T> void mailbox(Shell shell, String component, String lane, String mailbox, Topic<T> topic,
                                   Subscriber<T> subscriber, int capacity, String... keys) {
        shell.inbox(component, lane).subscribe(mailbox, topic, subscriber, capacity,
                keys.length == 0 ? null : Set.of(keys));
    }

    /**
     * A sender on {@code topic} for a component: what it sends through it is its own, queued for the round after the
     * one it was sent in, in the order it sent (ROADMAP fork 5: a send is declared by taking the sender). The topic is
     * the payload's own declared constant, which is how the processor found it from the sender's type.
     */
    public static <T> Sender<T> sender(Shell shell, String component, String lane, Topic<T> topic) {
        return shell.inbox(component, lane).sender(topic);
    }
}

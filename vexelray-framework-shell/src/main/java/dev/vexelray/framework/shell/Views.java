package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import sibarum.atchung.Subscriber;
import sibarum.atchung.Topic;

/**
 * What the generated wiring calls to give a {@code @View} its mailboxes: each {@code @Subscribe} method drained on
 * the GUI thread in each frame's widget tick, after input is dispatched and before layout ({@code docs/threading.md},
 * T7.4). An application does not call it.
 *
 * <p>The view's mailboxes are the frame's own queue, the one the widgets drain, so a view hears a widget's event in the
 * frame the input arrived and what it writes is drawn in that frame. On a state topic only the last write per key
 * since the last frame waits; on an edge every message, and one more than {@code capacity} is a fault naming the topic.
 */
public final class Views {

    private Views() {
    }

    /**
     * A mailbox for a view: {@code topic} delivered to {@code subscriber} on the GUI thread, in the widget tick.
     * {@code declared} is the name {@code @Subscribe} gave; the topic is the payload owner's own constant where it has
     * one, so its kind is the publisher's, and the two names must agree or the application does not start.
     */
    public static <T> void mailbox(Gui gui, String declared, Topic<T> topic, Subscriber<T> subscriber, int capacity,
                                   String... keys) {
        Inboxes.named(declared, topic);
        if (keys.length == 0) {
            gui.mailbox(topic, subscriber, capacity);
        } else {
            gui.mailbox(topic, subscriber, capacity, java.util.Set.of(keys));
        }
    }
}

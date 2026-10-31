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
     * The topic is the payload's own declared constant, the one the {@code @Subscribe} method's parameter names, so its
     * kind is the publisher's. {@code keys}, when any, are the keys of a state topic it is handed and the only ones.
     * {@code mailbox} is its name in the bus graph, {@code class#method}, which each delivery's {@code receive} row
     * says after the widgets' inbox, so a reader following a message's cause sees which view method it reached.
     */
    public static <T> void mailbox(Gui gui, String mailbox, Topic<T> topic, Subscriber<T> subscriber, int capacity,
                                   String... keys) {
        gui.mailbox(mailbox, topic, subscriber, capacity, keys.length == 0 ? null : java.util.Set.of(keys));
    }

    /**
     * A view's {@code @Draw} method, registered with the tree to be asked on every frame it draws (T7.6,
     * vexelray-gui's {@code docs/plans/frame-contract.md}). The generated {@code part} reads the view's state field and
     * calls the static method with it; the processor has checked the method's body, so this is the one place
     * application code is handed to the host's asking, and it is code the compiler has read.
     */
    public static void draw(Gui gui, dev.vexelray.gui.core.spi.Asked part) {
        dev.vexelray.gui.core.spi.Seams.of(gui).ask(part);
    }
}

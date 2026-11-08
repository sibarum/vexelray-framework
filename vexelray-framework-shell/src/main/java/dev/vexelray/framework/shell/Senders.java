package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Atchung;
import sibarum.atchung.Inbox;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

/**
 * A {@link Sender} for any topic, for a part that declares what a gesture sends: a view's shortcut, a menu's item, a
 * dialog's button ({@code docs/threading.md}, T7.5: a node declares the message, and the dispatcher sends it).
 *
 * <p>The widget library's declarations take a {@code Sender} rather than code, so nothing of the application's runs at
 * the click. A sender is made by an inbox, and a view has none, since it hears in the frame and not on the logic lane.
 * These come from an inbox that is never delivered to, so every send is an ordinary publish, staged for whoever hears
 * the topic, exactly as {@code Atchung.publish} would be. <b>Interim:</b> ROADMAP fork 5 has the wiring hand each part
 * a sender per topic it declares it sends, which the processor can then check; until that is built, this is how a
 * view gets one, and what it sends should still be listed in its {@code @Publishes}.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Senders {

    private final Inbox inbox;

    Senders(Atchung bus) {
        this.inbox = bus.tick(null).inbox("senders");
    }

    /** A sender on {@code topic}: what a declaration names, and what a part calls to send a message itself. */
    public <T> Sender<T> to(Topic<T> topic) {
        return inbox.sender(topic);
    }
}

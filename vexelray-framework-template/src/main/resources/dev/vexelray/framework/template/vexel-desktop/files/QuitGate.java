package ${packageName};

import ${packageName}.core.Messages;
import dev.vexelray.framework.api.Publishes;
import sibarum.atchung.Atchung;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The main window's close gate, turned into messages.
 *
 * <p>The framework asks a close gate with two callbacks, {@code proceed} and {@code cancel}, and a callback cannot be
 * sent to another lane: it would carry whatever it closes over. So the gate keeps them here, under a ticket, and sends
 * the ticket instead: to the {@link View}, which tells the editor which tabs are open and unsaved, and the editor's
 * {@link Messages.QuitAnswer} comes back with the ticket and is answered here. Exactly one of the two runs, once.
 *
 * <p>Shared between the handler lane, which asks, and the view's lane, which answers, so its one map is concurrent.
 * It holds nothing else.
 */
@Publishes(Intent.TOPIC)
final class QuitGate {

    private record Pending(Runnable proceed, Runnable cancel) {
    }

    private final Atchung bus;
    private final AtomicLong tickets = new AtomicLong();
    private final Map<Long, Pending> waiting = new ConcurrentHashMap<>();

    QuitGate(Atchung bus) {
        this.bus = bus;
    }

    /** The framework's {@code CloseGate}: the window has been asked to close. */
    void guard(Runnable proceed, Runnable cancel) {
        long ticket = tickets.incrementAndGet();
        waiting.put(ticket, new Pending(proceed, cancel));
        bus.publish(Intent.INTENTS, new Intent(Intent.Command.QUIT, ticket));
    }

    /** What the editor decided for {@code ticket}. A ticket already answered, or never issued, is ignored. */
    void answer(long ticket, boolean proceed) {
        Pending p = waiting.remove(ticket);
        if (p != null) {
            (proceed ? p.proceed() : p.cancel()).run();
        }
    }
}

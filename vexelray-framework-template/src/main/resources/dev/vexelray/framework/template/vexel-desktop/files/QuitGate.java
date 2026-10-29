package ${packageName};

import ${packageName}.core.Messages;
import sibarum.atchung.Sender;

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
 * <p>Asked from the frame, where the window's close request is handled, and answered from the view's frame; its one map
 * is concurrent in case the two are ever not one thread. It holds nothing else, and goes with phase 5's close gate,
 * which makes the question and its answer messages.
 */
final class QuitGate {

    private record Pending(Runnable proceed, Runnable cancel) {
    }

    private final Sender<Intent> intents;
    private final AtomicLong tickets = new AtomicLong();
    private final Map<Long, Pending> waiting = new ConcurrentHashMap<>();

    QuitGate(Sender<Intent> intents) {
        this.intents = intents;
    }

    /** The framework's {@code CloseGate}: the window has been asked to close. */
    void guard(Runnable proceed, Runnable cancel) {
        long ticket = tickets.incrementAndGet();
        waiting.put(ticket, new Pending(proceed, cancel));
        intents.send(new Intent(Intent.Command.QUIT, ticket));
    }

    /** What the editor decided for {@code ticket}. A ticket already answered, or never issued, is ignored. */
    void answer(long ticket, boolean proceed) {
        Pending p = waiting.remove(ticket);
        if (p != null) {
            (proceed ? p.proceed() : p.cancel()).run();
        }
    }
}

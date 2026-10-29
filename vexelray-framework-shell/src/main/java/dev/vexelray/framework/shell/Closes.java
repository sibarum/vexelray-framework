package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.gui.core.Gui;
import sibarum.atchung.Atchung;
import sibarum.probe.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>Every window's close, as a question and its answer</b> ({@link CloseRequested}, {@link CloseAnswer}): the
 * framework's half of the close gate, one per application.
 *
 * <p>A window's close request arrives as two callbacks, {@code proceed} and {@code cancel}, from the window's own
 * route. They stay here, under a ticket, and only the ticket is published; the answer, heard in the main window's
 * frame, runs one of them. So the callbacks never leave the main thread, and everything here is the main thread's:
 * the close route calls {@link #ask} in the frame, and {@link #answer} is drained in the frame. No lock.
 *
 * <p><b>Asked only when something hears the question</b>, which the generated wiring says ({@code
 * Wiring.answersClose}). Otherwise a close goes straight through, as it always has by default: an application with
 * nothing to lose should not have to say so.
 */
final class Closes {

    private static final Log LOG = Log.of("framework.shell");

    /** One unanswered question per window, so far more than are ever open at once. */
    private static final int CAPACITY = 64;

    private record Pending(String window, Runnable proceed, Runnable cancel) {
    }

    private final Atchung bus;
    private final boolean asked;
    private final Map<Long, Pending> waiting = new HashMap<>();
    /** The windows with a question out, so a second press of a close button asks nothing new. */
    private final Set<String> open = new HashSet<>();
    private long tickets;

    /**
     * @param asked whether anything hears {@link CloseRequested}; when not, every close goes straight through
     * @param gui   the main window's tree, whose frame hears the answers
     */
    Closes(Atchung bus, boolean asked, Gui gui) {
        this.bus = bus;
        this.asked = asked;
        if (asked) {
            gui.mailbox(CloseAnswer.TOPIC, this::answer, CAPACITY);
        }
    }

    /** Window {@code window} has been asked to close. Main thread. Exactly one of the two runs, now or later. */
    void ask(String window, Runnable proceed, Runnable cancel) {
        if (!asked) {
            proceed.run();
            return;
        }
        if (!open.add(window)) {
            // The question is up already; this press is the same request, and the answer to the first decides it.
            cancel.run();
            return;
        }
        long ticket = ++tickets;
        waiting.put(ticket, new Pending(window, proceed, cancel));
        bus.publish(CloseRequested.TOPIC, new CloseRequested(window, ticket));
    }

    /** The answer to a ticket. Main thread, in the frame. */
    private void answer(CloseAnswer a) {
        Pending p = waiting.remove(a.ticket());
        if (p == null) {
            LOG.debug("close answer for ticket " + a.ticket() + ", which is not waiting: ignored");
            return;
        }
        open.remove(p.window());
        (a.proceed() ? p.proceed() : p.cancel()).run();
    }
}

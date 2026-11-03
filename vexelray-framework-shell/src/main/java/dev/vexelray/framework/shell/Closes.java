package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.gui.core.Gui;
import sibarum.atchung.Atchung;
import sibarum.probe.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Every window's close, as a question and its answers</b> ({@link CloseRequested}, {@link CloseAnswer}): the
 * framework's half of the close gate, one per application.
 *
 * <p>A window's close request arrives as two callbacks, {@code proceed} and {@code cancel}, from the window's own
 * route. They stay here, under a ticket, and only the ticket is published; the answer, heard in the main window's
 * frame, runs one of them. So the callbacks never leave the main thread, and everything here is the main thread's:
 * the close route calls {@link #ask} in the frame, and {@link #answer} is drained in the frame. No lock.
 *
 * <p><b>Each part that hears the question is asked in turn</b> ({@code Wiring.closeHearers}, in its order), by a
 * request addressed to it under a ticket of its own. A part that lets the window close passes the question to the next;
 * the last one's yes closes it, and the first no keeps it open and asks no one after. So one part's dialog is up at a
 * time, and no part acts on a close another then refuses.
 *
 * <p><b>Asked only when something hears the question.</b> Otherwise a close goes straight through, as it always has
 * by default: an application with nothing to lose should not have to say so.
 */
final class Closes {

    private static final Log LOG = Log.of("framework.shell");

    /** One unanswered question per window, so far more than are ever open at once. */
    private static final int CAPACITY = 64;

    /** A window's close, and which of the hearers it is waiting on. */
    private record Pending(String window, int turn, Runnable proceed, Runnable cancel) {
    }

    private final Atchung bus;
    private final List<String> hearers;
    private final Map<Long, Pending> waiting = new HashMap<>();
    /** The windows with a question out, so a second press of a close button asks nothing new. */
    private final Set<String> open = new HashSet<>();
    private long tickets;

    /**
     * @param hearers the parts that hear {@link CloseRequested}, in the order they are asked; when none, every close
     *                goes straight through
     * @param gui     the main window's tree, whose frame hears the answers
     */
    Closes(Atchung bus, List<String> hearers, Gui gui) {
        this.bus = bus;
        this.hearers = List.copyOf(hearers);
        if (!this.hearers.isEmpty()) {
            gui.mailbox(CloseAnswer.TOPIC, this::answer, CAPACITY);
        }
    }

    /** Window {@code window} has been asked to close. Main thread. Exactly one of the two runs, now or later. */
    void ask(String window, Runnable proceed, Runnable cancel) {
        if (hearers.isEmpty()) {
            proceed.run();
            return;
        }
        if (!open.add(window)) {
            // The question is up already; this press is the same request, and the answers to the first decide it.
            cancel.run();
            return;
        }
        put(new Pending(window, 0, proceed, cancel));
    }

    /** Ask {@code p}'s turn, under a ticket of its own, so a late answer from an earlier turn answers nothing. */
    private void put(Pending p) {
        long ticket = ++tickets;
        waiting.put(ticket, p);
        bus.publish(CloseRequested.TOPIC, new CloseRequested(p.window(), ticket, hearers.get(p.turn())));
    }

    /** The answer to a ticket. Main thread, in the frame. */
    private void answer(CloseAnswer a) {
        Pending p = waiting.remove(a.ticket());
        if (p == null) {
            LOG.debug("close answer for ticket " + a.ticket() + ", which is not waiting: ignored");
            return;
        }
        if (a.proceed() && p.turn() + 1 < hearers.size()) {
            put(new Pending(p.window(), p.turn() + 1, p.proceed(), p.cancel()));
            return;
        }
        open.remove(p.window());
        (a.proceed() ? p.proceed() : p.cancel()).run();
    }
}

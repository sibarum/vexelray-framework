package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

import java.util.List;
import java.util.Objects;

/**
 * <b>A question for the person using the application</b>, as data: a title, a message and the buttons to answer with.
 * The framework shows it as a dialog over the main window and publishes the {@link Answer} with the same {@code id},
 * addressed to the part that asked ({@code from}). Any part may send it, a component on the logic lane as well as a
 * view, since it carries no widget and no code.
 *
 * <p><b>The buttons, in order.</b> The first is the default, which Enter presses. With two or more, the last is the
 * cancel, which Escape and the dialog's close box answer, so "the person did not answer" is answered too. With one, it
 * is an acknowledgement: Escape puts it away unanswered, and an {@code Answer} comes only from pressing the button
 * (a {@code Modal} has no button that is both default and cancel).
 *
 * <p><b>The answer comes back to the asker, and only to it.</b> {@code from} names the part asking, and the generated
 * wiring hands each part that hears {@code Answer} only the answers addressed to it, as it does each part's turn of a
 * {@link CloseRequested}. So the {@code id} is the asker's own business: a chain's run ({@code out.run()}) or a counter
 * of its own, from anything, and two parts that both count from 1 never act on each other's answers. Before this an
 * answer reached every part that heard {@code Answer}, so two parts' counters collided, and the fix had to be in how a
 * question is made (the windows witness's {@code Keeper} counted from 2^40 to stay clear of its {@code Gate}).
 *
 * <p>Name the asker by its class ({@code new Ask(id, title, message, buttons, Editor.class)}), not by a string,
 * so a rename moves it too. A question asked by a part that does not hear {@code Answer} still works, and its answer
 * reaches nobody, which is what an acknowledgement wants. One with a choice in it reaches nobody too, which is never
 * what was meant, so the framework says so once in the log, naming the asker.
 *
 * <p>A view that wants a dialog of its own shape (a field in it, buttons that send its own messages) builds a
 * {@code Modal} and hands it to {@code Dialogs.ask}, as before; this is for a question that is only words.
 *
 * @param id      what the answer carries: the asker's own, never compared with another part's
 * @param title   the dialog's title
 * @param message what it says
 * @param buttons the labels, default first and cancel last
 * @param from    the canonical name of the part asking ({@code Class.getCanonicalName()}, its qualified name), which
 *                its answer is addressed to
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Ask(long id, String title, String message, List<String> buttons, String from) {

    public static final Topic<Ask> TOPIC = Topic.edge(Ask.class);

    public Ask {
        buttons = List.copyOf(buttons);
        if (buttons.isEmpty()) {
            throw new IllegalArgumentException("a question needs a button to answer it with");
        }
        Objects.requireNonNull(from, "from: a question names the part asking, which its answer goes back to");
    }

    /** A question from {@code from}, named by its class: what a part writes. */
    public Ask(long id, String title, String message, List<String> buttons, Class<?> from) {
        this(id, title, message, buttons, from.getCanonicalName());
    }

    /**
     * The answer that pressing {@code button} sends, addressed to the asker: what the framework's dialog sends, and
     * what a test hands back to the part it tests for a question it took.
     */
    public Answer answer(int button) {
        if (button < 0 || button >= buttons.size()) {
            throw new IndexOutOfBoundsException("button " + button + " of a question with " + buttons.size());
        }
        return new Answer(id, button, from);
    }

    /** A message with one OK button: the "your export finished" dialog. */
    public static Ask info(long id, String title, String message, Class<?> from) {
        return new Ask(id, title, message, List.of("OK"), from);
    }
}

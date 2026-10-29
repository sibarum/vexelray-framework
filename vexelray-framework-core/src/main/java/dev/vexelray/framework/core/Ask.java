package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

import java.util.List;

/**
 * <b>A question for the person using the application</b>, as data: a title, a message and the buttons to answer with.
 * The framework shows it as a dialog over the main window and publishes the {@link Answer} with the same {@code id}.
 * Any part may send it, a component on the logic lane as well as a view, since it carries no widget and no code.
 *
 * <p><b>The buttons, in order.</b> The first is the default, which Enter presses. With two or more, the last is the
 * cancel, which Escape and the dialog's close box answer, so "the person did not answer" is answered too. With one, it
 * is an acknowledgement: Escape puts it away unanswered, and an {@code Answer} comes only from pressing the button
 * (a {@code Modal} has no button that is both default and cancel).
 *
 * <p><b>The {@code id} is the asker's</b>, and comes back in the answer. Every part that hears {@code Answer} hears
 * every answer, so an asker picks ids no other asker uses: a chain's run, or a counter of its own.
 *
 * <p>A view that wants a dialog of its own shape (a field in it, buttons that send its own messages) builds a
 * {@code Modal} and hands it to {@code Dialogs.ask}, as before; this is for a question that is only words.
 *
 * @param id      what the answer carries
 * @param title   the dialog's title
 * @param message what it says
 * @param buttons the labels, default first and cancel last
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Ask(long id, String title, String message, List<String> buttons) {

    public static final Topic<Ask> TOPIC = Topic.edge(Ask.class);

    public Ask {
        buttons = List.copyOf(buttons);
        if (buttons.isEmpty()) {
            throw new IllegalArgumentException("a question needs a button to answer it with");
        }
    }

    /** A message with one OK button: the "your export finished" dialog. */
    public static Ask info(long id, String title, String message) {
        return new Ask(id, title, message, List.of("OK"));
    }
}

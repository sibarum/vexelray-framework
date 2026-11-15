package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>What the person answered an {@link Ask} with</b>: the index of the button pressed, in the order the question
 * listed them. Escape and the close box press the last. Published once per question, addressed to the part that asked
 * it ({@code to}, the question's {@link Ask#from()}), and the generated wiring hands each part only its own: so an
 * {@code id} is compared only with the ids its own part issued.
 *
 * @param id     the question's {@link Ask#id()}
 * @param button the button pressed, from 0
 * @param to     the canonical name of the part that asked, which the wiring filters by; null in an answer a test hands
 *               straight to a part's inbox, which no part the wiring built hears
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Answer(long id, int button, String to) {

    /**
     * Keyed by the part that asked, so each part hears only the answers to its own questions. An answer addressed to no
     * part is refused here, naming {@code to}, rather than reaching the bus as a null key, as {@link CloseRequested}'s
     * is.
     */
    public static final Topic<Answer> TOPIC = Topic.edge(Answer.class, Answer::addressee);

    /** An answer addressed to no part: what a test hands straight to the inbox of the part it tests. */
    public Answer(long id, int button) {
        this(id, button, null);
    }

    /** {@link #to}, which an answer on the bus must have: its key. */
    private static String addressee(Answer a) {
        if (a.to == null) {
            throw new IllegalArgumentException("an Answer sent on the bus names no part (to is null): the two-argument"
                    + " form is for handing an answer straight to the inbox of the part a test tests. On the bus,"
                    + " address it to the asker: new Answer(id, button, ask.from())");
        }
        return a.to;
    }
}

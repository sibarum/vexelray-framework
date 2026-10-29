package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>What the person answered an {@link Ask} with</b>: the index of the button pressed, in the order the question
 * listed them. Escape and the close box press the last. Published once per question.
 *
 * @param id     the question's {@link Ask#id()}
 * @param button the button pressed, from 0
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Answer(long id, int button) {

    public static final Topic<Answer> TOPIC = Topic.edge(Answer.class);
}

package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>What a {@link CloseRequested} is answered with</b>: let the window close, or keep it open. Sent once per ticket,
 * from any lane; the framework hears it on the main thread, in the frame. An answer to a ticket already answered, or
 * never issued, is ignored.
 *
 * @param ticket  the request's {@link CloseRequested#ticket()}
 * @param proceed true to close the window, false to keep it
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record CloseAnswer(long ticket, boolean proceed) {

    public static final Topic<CloseAnswer> TOPIC = Topic.edge(CloseAnswer.class);
}

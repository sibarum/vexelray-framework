package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>What a {@link PickFile} was answered with</b>: the path picked, or empty when the person cancelled or the picker
 * could not be shown. Published once per request.
 *
 * @param id   the request's {@link PickFile#id()}
 * @param path the path picked, or empty
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Picked(long id, String path) {

    public static final Topic<Picked> TOPIC = Topic.edge(Picked.class);

    /** Nothing was picked: the person cancelled, or the picker could not be shown. */
    public static Picked cancelled(long id) {
        return new Picked(id, "");
    }

    /** Whether nothing was picked. */
    public boolean isCancelled() {
        return path.isEmpty();
    }
}

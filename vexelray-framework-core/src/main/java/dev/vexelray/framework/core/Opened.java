package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>What an {@link Open} came to</b>: whether the desktop's opener started for {@code target}, and if not, why, in
 * words a person can be shown after "Can't open …". Published once per request, from the job lane.
 *
 * @param target  the request's {@link Open#target()}
 * @param problem why it did not open, or empty when it did
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Opened(String target, String problem) {

    public static final Topic<Opened> TOPIC = Topic.edge(Opened.class);

    public Opened {
        java.util.Objects.requireNonNull(target, "target");
        problem = problem == null ? "" : problem;
    }

    /** {@code target} was handed to its opener. */
    public static Opened done(String target) {
        return new Opened(target, "");
    }

    /** Whether the opener started. */
    public boolean opened() {
        return problem.isEmpty();
    }
}

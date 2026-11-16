package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

import java.time.Duration;

/**
 * <b>A lane has been inside one delivery too long</b>: the watchdog's report, once per stalled delivery. A lane that
 * recovers and stalls again is a second report; still stalled is not.
 *
 * <p>Published from the watchdog's thread, which belongs to no lane, so the report goes out even while every lane is
 * wedged. A part hears it in its mailbox like any other message, which a part on the stalled lane does only once that
 * lane drains again; what is <em>done</em> about a stall is the application's {@code LivenessPolicy}, which the
 * framework runs on the watchdog's thread for that reason.
 *
 * <p><b>The main thread too</b>, under {@link #MAIN}: a frame loop that has made no progress for the threshold while
 * not parked and not holding a native dialog (see {@code Watchdog.watchMain}). It is not a lane and never will be;
 * the name is only so one message can say both, and it is not one a lane can have.
 *
 * @param lane       the lane's name: {@code "logic"}, a declared lane's, or {@link #MAIN}
 * @param stalledFor how long it had been inside the delivery, or the main thread without progress, when noticed
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Stalled(String lane, Duration stalledFor) {

    public static final Topic<Stalled> TOPIC = Topic.edge(Stalled.class);

    /**
     * What {@link #lane} says when the main thread stalled: {@code "<main>"}. Reserved by construction, as the default
     * lane's {@code "<default>"} is, since a declared lane must be a name and this is not one.
     */
    public static final String MAIN = "<main>";

    /** Whether this is the main thread's stall rather than a lane's. */
    public boolean mainThread() {
        return MAIN.equals(lane);
    }
}

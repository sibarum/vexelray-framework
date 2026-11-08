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
 * @param lane       the lane's name: {@code "logic"}, or a declared lane's
 * @param stalledFor how long it had been inside the delivery when it was noticed
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Stalled(String lane, Duration stalledFor) {

    public static final Topic<Stalled> TOPIC = Topic.edge(Stalled.class);
}

package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Subscriber;
import sibarum.atchung.Topic;

/**
 * What the generated wiring calls to put a {@code @Component} on its lane and register its mailboxes.
 *
 * <p><b>Not for application code, and that is a convention rather than a wall.</b> Java has no visibility between
 * "this package" and "everyone", and the generated wiring lives in the application's package, so the one door it
 * needs has to be public. It is this class and nothing else: {@code Shell#place} and {@code Placement#subscribe}
 * are not, because a placement an application makes by hand is decided later than the wiring, and the processor
 * cannot see it. Placement is decided on the declaration ({@code @Component(lane = ...)}) and mailboxes are
 * declared with {@code @Subscribe}; both are checked at compile time, which a call written here is not.
 * See {@code docs/components.md}, rulings 1 and 2.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Placements {

    private Placements() {
    }

    /** The placement for {@code lane}, registered with {@code shell} for start and shutdown. */
    public static Placement of(Shell shell, String lane) {
        return shell.place(lane);
    }

    /** Add a mailbox to {@code placement}: {@code topic} drained on its lane, with its bound and loss policy. */
    public static <T> void mailbox(Placement placement, Topic<T> topic, Subscriber<T> subscriber, int capacity,
                                   Backpressure policy) {
        placement.subscribe(topic, subscriber, capacity, policy);
    }
}

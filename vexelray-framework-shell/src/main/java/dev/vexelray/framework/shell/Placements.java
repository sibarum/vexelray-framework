package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Fold;
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

    /**
     * As {@link #mailbox(Placement, Topic, Subscriber, int, Backpressure)}, with a {@link Fold}: the mailbox holds
     * cells, so a write supersedes the queued write to the same cell instead of queueing beside it.
     *
     * <p>For a host whose events declare their own loss class rather than a Java annotation per method - the
     * Pontif runtime, where a sort says it is a sample and one lane's single mailbox then folds those and leaves
     * every edge alone, keeping arrival order across both. Read {@code Fold} before using it: folding is lossless
     * only where nothing can observe the consumer's state between a publish and its drain.
     */
    public static <T> void mailbox(Placement placement, Topic<T> topic, Subscriber<T> subscriber, int capacity,
                                   Backpressure policy, Fold<T> fold) {
        placement.subscribe(topic, subscriber, capacity, policy, fold);
    }
}

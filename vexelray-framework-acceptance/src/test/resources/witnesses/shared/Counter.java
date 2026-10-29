package ${packageName};

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Subscribe;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

/*
 * The counter every witness is built on, as 0.2 shapes it: a component on the logic lane owns the document, hears
 * what a person asked for, and says what the document is now. Views show it; none of them holds it.
 *
 * It replaced a Model that any thread could commit to and that called its listeners on whichever thread committed,
 * which is what a view's frame and the logic lane now keep apart.
 */

/** One more, please. */
record Bump() {
    static final Topic<Bump> TOPIC = Topic.edge(Bump.class);
}

/** Back to the beginning, please. */
record Reset() {
    static final Topic<Reset> TOPIC = Topic.edge(Reset.class);
}

/** What the document is now. State: only the newest matters, so a frame is handed the last of a burst. */
record Counted(Doc doc) {
    static final Topic<Counted> TOPIC = Topic.state(Counted.class);
}

/** Owns the document. Its field is its lane's alone: nothing else holds this component. */
@Component
final class Counter {

    private final Sender<Counted> counted;
    private Doc doc = Doc.initial();

    Counter(Sender<Counted> counted) {
        this.counted = counted;
    }

    /** Relative, so two bumps in one tick are two. */
    @Subscribe
    public void bump(Bump b) {
        doc = doc.withCount(doc.count() + 1).withNote("counted " + (doc.count() + 1));
        counted.send(new Counted(doc));
    }

    @Subscribe
    public void reset(Reset r) {
        doc = Doc.initial();
        counted.send(new Counted(doc));
    }
}

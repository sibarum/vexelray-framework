package ${packageName};

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Subscribe;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

/*
 * The witness's components: four, on the logic lane and two declared lanes, chosen so that each thing the component
 * model promises has something to be true about.
 *
 *   Worker    lane "worker"     answers a job
 *   Echo      the logic lane    answers a ping
 *   Wedger    the logic lane    never returns from a delivery until it is interrupted
 *   Isolated  lane "isolated"   the same wedge, on a declared lane
 *
 * Each says what it did as a message, and the view shows it. Nothing here holds anything another lane can see: what
 * a Bench of atomics and volatiles once did between lanes, a message does now.
 *
 * 0.2 states the cost the witness used to deny: a declared lane runs its share of a tick in the same rounds as the
 * logic lane, so a wedge on any lane holds every lane at the round's end. A declared lane is a core for heavy work,
 * not a wall around a wedge. What no wedge can stop is the window, which runs no component.
 */

record Job(int n) {
    static final Topic<Job> TOPIC = Topic.edge(Job.class);
}

record Worked(int count) {
    static final Topic<Worked> TOPIC = Topic.state(Worked.class);
}

record Ping(int n) {
    static final Topic<Ping> TOPIC = Topic.edge(Ping.class);
}

record Echoed(int count) {
    static final Topic<Echoed> TOPIC = Topic.state(Echoed.class);
}

/** Wedge the component on {@code lane}: {@code "logic"} or {@code "isolated"}. */
record Wedge(String lane) {
    static final Topic<Wedge> TOPIC = Topic.edge(Wedge.class);
}

/**
 * The wedge on {@code lane} is over: {@code freed} once interrupted. One per lane.
 *
 * <p>There is no "wedged" to go with it, and that is a finding: what a component sends during a delivery goes out
 * with the round, so a send made before it blocks is held for as long as it blocks. A component cannot say it has
 * started something long from inside the delivery that does it. The view says what it asked for instead.
 */
record Wedging(String lane, String state) {
    static final Topic<Wedging> TOPIC = Topic.state(Wedging.class, Wedging::lane);
}

@Component(lane = "worker")
final class Worker {

    private final Sender<Worked> worked;
    private int count;

    Worker(Sender<Worked> worked) {
        this.worked = worked;
    }

    @Subscribe
    public void job(Job job) {
        worked.send(new Worked(++count));
    }
}

@Component
final class Echo {

    private final Sender<Echoed> echoed;
    private int count;

    Echo(Sender<Echoed> echoed) {
        this.echoed = echoed;
    }

    @Subscribe
    public void ping(Ping ping) {
        echoed.send(new Echoed(++count));
    }
}

/** Holds its lane in one delivery until the liveness policy interrupts it, and says so once it is free. */
final class Wedges {

    static void hold(String lane, Sender<Wedging> wedging) {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException freed) {
            wedging.send(new Wedging(lane, "freed"));
        }
    }

    private Wedges() {
    }
}

@Component
final class Wedger {

    private final Sender<Wedging> wedging;

    Wedger(Sender<Wedging> wedging) {
        this.wedging = wedging;
    }

    @Subscribe
    public void wedge(Wedge wedge) {
        if (wedge.lane().equals("logic")) {
            Wedges.hold("logic", wedging);
        }
    }
}

@Component(lane = "isolated")
final class Isolated {

    private final Sender<Wedging> wedging;

    Isolated(Sender<Wedging> wedging) {
        this.wedging = wedging;
    }

    @Subscribe
    public void wedge(Wedge wedge) {
        if (wedge.lane().equals("isolated")) {
            Wedges.hold("isolated", wedging);
        }
    }
}

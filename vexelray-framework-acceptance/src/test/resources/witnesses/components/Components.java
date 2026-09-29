package ${packageName};

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Overflow;
import dev.vexelray.framework.api.Subscribe;

/*
 * The witness's components: four of them, on three lanes, chosen so that each thing the component model promises
 * has something to be true about.
 *
 *   Worker    lane "worker"     a BLOCK mailbox, published to from the handler lane, where waiting is legal
 *   Echo      the default lane  answers a ping
 *   Wedger    the default lane  never returns from a delivery; shares its thread with Echo, deliberately
 *   Isolated  lane "isolated"   the same wedge, on a lane nothing else is on
 *
 * Echo and Wedger are the point of the default lane and its cost. A component that names no lane costs no thread,
 * and one that never returns holds up every other on it: the witness asserts both halves.
 */

/** The topic names, as constants so an annotation and a publisher cannot disagree by a typo. */
final class Topics {
    static final String JOB = "witness.job";
    static final String PING = "witness.ping";
    static final String WEDGE_DEFAULT = "witness.wedge.default";
    static final String WEDGE_ISOLATED = "witness.wedge.isolated";

    private Topics() {
    }
}

record Job(int n) { }

record Ping(int n) { }

record Wedge(int n) { }

@Component(lane = "worker")
final class Worker {

    private final Bench bench;

    Worker(Bench bench) {
        this.bench = bench;
    }

    @Subscribe(topic = Topics.JOB, overflow = Overflow.BLOCK, capacity = 8)
    public void job(Job job) {
        bench.worked();
    }
}

@Component
final class Echo {

    private final Bench bench;

    Echo(Bench bench) {
        this.bench = bench;
    }

    @Subscribe(topic = Topics.PING)
    public void ping(Ping ping) {
        bench.echoed();
    }
}

@Component
final class Wedger {

    private final Bench bench;

    Wedger(Bench bench) {
        this.bench = bench;
    }

    @Subscribe(topic = Topics.WEDGE_DEFAULT, overflow = Overflow.COALESCE_LATEST)
    public void wedge(Wedge wedge) {
        bench.standard("wedged");
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException freed) {
            bench.standard("freed");
        }
    }
}

@Component(lane = "isolated")
final class Isolated {

    private final Bench bench;

    Isolated(Bench bench) {
        this.bench = bench;
    }

    @Subscribe(topic = Topics.WEDGE_ISOLATED, overflow = Overflow.COALESCE_LATEST)
    public void wedge(Wedge wedge) {
        bench.isolated("wedged");
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException freed) {
            bench.isolated("freed");
        }
    }
}

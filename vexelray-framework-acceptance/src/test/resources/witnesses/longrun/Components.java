package ${packageName};

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Job;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.Timer;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

import java.lang.management.ManagementFactory;
import java.time.Duration;

/*
 * The long-running witness's logic: a metronome that keeps a component busy a hundred times a second for as long as
 * it runs, and a job that weighs the process when asked.
 *
 * In 0.1 the metronome slept ten milliseconds inside a delivery and published the next tick to itself, and a Meter
 * of atomics, written by its lane and counted by a frame hook, carried the result. In 0.2 the beat is the framework's
 * Timer, a component counts it, and what the process weighs is a job's answer; the frames are counted by the flight
 * recorder, since no application code runs in the frame.
 */

record Start() {
    static final Topic<Start> TOPIC = Topic.edge(Start.class);
}

record Stop() {
    static final Topic<Stop> TOPIC = Topic.edge(Stop.class);
}

/** One beat. The same value every time: the timer publishes what it was given. */
record Beat() {
    static final Topic<Beat> TOPIC = Topic.edge(Beat.class);
}

/** How many beats the metronome has counted. State: a frame shows the newest. */
record Ticked(long ticks) {
    static final Topic<Ticked> TOPIC = Topic.state(Ticked.class);
}

/** Weigh the process, please; {@code n} numbers the answer so a driver waits for the one it asked for. */
record Weigh(int n) {
    static final Topic<Weigh> TOPIC = Topic.edge(Weigh.class);
}

/** Live threads and the heap in use after a collection: what a long run can quietly grow. */
record Weighed(int n, int threads, long heapMb) {
    static final Topic<Weighed> TOPIC = Topic.edge(Weighed.class);
}

/** Counts a beat every ten milliseconds while it runs, on a lane of its own. */
@Component(lane = "metronome")
final class Metronome {

    private static final Duration PERIOD = Duration.ofMillis(10);

    private final Timer timer;
    private final Sender<Ticked> ticked;
    private Timer.Scheduled beating;
    private long ticks;

    Metronome(Timer timer, Sender<Ticked> ticked) {
        this.timer = timer;
        this.ticked = ticked;
    }

    @Subscribe
    public void start(Start s) {
        if (beating == null) {
            beating = timer.every(PERIOD, Beat.TOPIC, new Beat());
        }
    }

    @Subscribe
    public void stop(Stop s) {
        if (beating != null) {
            beating.cancel();
            beating = null;
        }
    }

    /** A beat already published when Stop arrives is still heard, and not counted. */
    @Subscribe(capacity = 256)
    public void beat(Beat b) {
        if (beating != null) {
            ticked.send(new Ticked(++ticks));
        }
    }
}

/** Weighs the process on the job lane: a collection is no work for a frame or a tick. */
@Job
final class Measure {

    public Weighed run(Weigh w) {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        return new Weighed(w.n(), ManagementFactory.getThreadMXBean().getThreadCount(), heapMb);
    }
}

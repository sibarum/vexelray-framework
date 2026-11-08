package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Lanes;
import sibarum.atchung.Tick;

import java.util.List;
import java.util.concurrent.Phaser;

/**
 * The declared lanes' threads: what runs a round's lanes in parallel for the logic's {@link Tick}
 * ({@code docs/threading.md} T1.6, T5.6).
 *
 * <p>Lane 0, the logic lane's own share, runs on the thread that runs the tick; each declared lane
 * ({@code @Component(lane = "physics")}) has a platform thread of its own, {@code vexel-component-<lane>}, that waits for
 * a round, delivers its lane's share, and meets the others at the round's end. So a declared lane is a core for its
 * component's work and nothing else: it ticks on the same schedule as everything, in the same rounds, and what it sends
 * is ordered by the round and the tick's tie-break, never by a race.
 *
 * <p>A round is handed over by a {@link Phaser}, which is what makes the coordinator's writes visible to the lanes and
 * theirs to it, as {@link Tick.Lanes} requires. Nothing is allocated per round.
 */
final class Crew implements Tick.Lanes, AutoCloseable {

    private final Phaser start = new Phaser(1);
    private final Phaser done = new Phaser(1);
    private volatile Tick.LaneWork work;
    private volatile int count;
    private volatile boolean stopping;

    /** Lane 0's gauge: the logic lane's, entered around its share on the thread running the tick. */
    private final Lanes.Busy logic;

    /**
     * One thread per name in {@code declared}, lane {@code i + 1} for the {@code i}th, started here and idle until a
     * round. Each lane's share is watched by its own gauge, so a wedged lane is reported by its name, and the lanes
     * waiting for it at the round's end are not reported with it.
     */
    Crew(Lanes lanes, Lanes.Busy logic, List<String> declared) {
        this.logic = logic;
        for (int i = 0; i < declared.size(); i++) {
            start.register();
            done.register();
            int lane = i + 1;
            String name = declared.get(i);
            Lanes.Busy busy = lanes.busy(name);
            lanes.thread(name, () -> serve(lane, busy));
        }
    }

    @Override
    public void run(int count, Tick.LaneWork work) {
        this.work = work;
        this.count = count;
        start.arriveAndAwaitAdvance();
        if (count > 0) {
            logic.enter();
            try {
                work.lane(0);
            } finally {
                logic.exit();
            }
        }
        done.arriveAndAwaitAdvance();
    }

    private void serve(int lane, Lanes.Busy busy) {
        while (true) {
            if (start.arriveAndAwaitAdvance() < 0 || stopping) {
                return;
            }
            if (lane < count) {
                busy.enter();
                try {
                    work.lane(lane);
                } finally {
                    busy.exit();
                }
            }
            if (done.arriveAndAwaitAdvance() < 0) {
                return;
            }
        }
    }

    /** Let every lane's thread return. Called once no round is running, by whoever ran the last one. */
    @Override
    public void close() {
        stopping = true;
        start.forceTermination();
        done.forceTermination();
    }
}

package ${packageName};

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Subscribe;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** The topic names, as constants so an annotation and a publisher cannot disagree by a typo. */
final class Topics {
    static final String PROBE = "witness.probe";
    static final String STORM = "witness.storm";

    private Topics() {
    }
}

record Probe(int trials) { }

record Storm(int millis) { }

/**
 * The two probes that need something other than the window's own thread to run them.
 *
 * <p>Each is one long delivery on a lane of its own, which is legal at these lengths and stays under the stall
 * threshold: the latency run is about four seconds and the storm three. Both make their wake the way any
 * component does, by writing a node, so what is measured is the path a real component's result takes.
 */
@Component(lane = "prober")
final class Prober {

    private final Meter meter;
    private final Ui ui;

    Prober(Meter meter, Ui ui) {
        this.meter = meter;
        this.ui = ui;
    }

    /**
     * Wake-to-first-frame from a parked loop. Each trial waits long enough for the loop to be asleep — its idle
     * refresh is 200 ms, so at least one park has begun — at a random phase, then arms the stopwatch, writes a
     * node, and waits for the frame to stop it.
     */
    @Subscribe(topic = Topics.PROBE)
    public void probe(Probe probe) {
        long[] micros = new long[probe.trials()];
        for (int i = 0; i < micros.length; i++) {
            try {
                Thread.sleep(220 + ThreadLocalRandom.current().nextInt(60));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
            meter.arm();
            ui.tick(i);
            long waited = System.nanoTime();
            long elapsed;
            while ((elapsed = meter.elapsed()) < 0 && System.nanoTime() - waited < TimeUnit.SECONDS.toNanos(1)) {
                Thread.onSpinWait();
            }
            micros[i] = elapsed < 0 ? -1 : elapsed / 1_000;
        }
        long[] sorted = micros.clone();
        Arrays.sort(sorted);
        ui.latency("latency n=" + micros.length + " median " + sorted[sorted.length / 2] + "us p95 "
                + sorted[(int) (sorted.length * 0.95)] + "us max " + sorted[sorted.length - 1] + "us min "
                + sorted[0] + "us");
    }

    /**
     * Mutations with no pause for {@code millis}, counting the frames the loop ran meanwhile and every failure
     * a write raised. Each write ends a park with a posted wake, so this is the loop's behaviour under more wakes
     * than it can answer — and the place a full message queue would show up as an exception on this thread.
     */
    @Subscribe(topic = Topics.STORM)
    public void storm(Storm storm) {
        long framesBefore = meter.frames();
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(storm.millis());
        long writes = 0;
        long failures = 0;
        String first = "";
        while (System.nanoTime() < deadline) {
            try {
                ui.tick((int) writes);
            } catch (RuntimeException e) {
                failures++;
                if (first.isEmpty()) {
                    first = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
            writes++;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        ui.storm("storm frames " + (meter.frames() - framesBefore) + " in " + millis + "ms writes " + writes
                + " failures " + failures + (first.isEmpty() ? "" : " first " + first));
    }
}

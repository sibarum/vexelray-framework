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
    static final String IDLE = "witness.idle";

    private Topics() {
    }
}

record Probe(int trials) { }

record Storm(int millis) { }

record Idle(int millis, int trials) { }

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
    /** How many idle runs have reported, so a driver awaiting the second does not read the first. */
    private int idles;

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
     * The other half of render on demand: what the loop costs when nothing is owed, and what one wake buys.
     *
     * <p>First {@code millis} of nothing, counting the frames the loop ran and the CPU the whole process spent. A
     * focused window is held to the idle floor (five a second); an unfocused one should run none. Then
     * {@code trials} single writes, each from a parked loop, counting the frames in the 100 ms after each: one wake
     * should earn exactly one frame, and anything more is something the write started that keeps the loop awake.
     * Under the lane's ten-second stall threshold at the lengths the driver asks for.
     */
    @Subscribe(topic = Topics.IDLE)
    public void idle(Idle idle) {
        sleep(500);   // the click that asked for this, and its hover, settle first
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        java.util.Map<Long, Long> threadsBefore = new java.util.HashMap<>();
        for (long id : threads.getAllThreadIds()) {
            threadsBefore.put(id, threads.getThreadCpuTime(id));
        }
        long framesBefore = meter.frames();
        long cpuBefore = cpuNanos();
        long start = System.nanoTime();
        sleep(idle.millis());
        long wall = System.nanoTime() - start;
        long idleFrames = meter.frames() - framesBefore;
        long idleCpu = cpuNanos() - cpuBefore;
        // Where the process's CPU went, by Java thread: the rest (the JVM's own threads and the drivers') is the
        // difference between the process total and this sum.
        java.util.List<String> spent = new java.util.ArrayList<>();
        long javaSum = 0;
        for (java.lang.management.ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (info == null) {
                continue;
            }
            long now = threads.getThreadCpuTime(info.getThreadId());
            long delta = now - threadsBefore.getOrDefault(info.getThreadId(), 0L);
            if (now >= 0 && delta > 0) {
                javaSum += delta;
                spent.add(String.format("%07.2f %s", delta / 1e6, info.getThreadName()));
            }
        }
        spent.sort(java.util.Comparator.reverseOrder());
        sibarum.probe.Log.of("witness.idle").warn("IDLE-CPU process " + idleCpu / 1_000_000L + "ms java threads " + javaSum / 1_000_000L
                + "ms :: " + String.join(" | ", spent));

        int[] earned = new int[idle.trials()];
        for (int i = 0; i < earned.length; i++) {
            sleep(250 + ThreadLocalRandom.current().nextInt(100));
            long before = meter.frames();
            ui.tick(i);
            sleep(100);
            earned[i] = (int) (meter.frames() - before);
        }
        ui.idle(String.format("idle #%d frames %d in %dms (%.1f/s) cpu %.1fms (%.2f%%) wake frames %s",
                ++idles, idleFrames, wall / 1_000_000L, idleFrames * 1e9 / wall, idleCpu / 1e6,
                idleCpu < 0 ? -1.0 : 100.0 * idleCpu / wall, Arrays.toString(earned).replace(" ", "")));
    }

    private static long cpuNanos() {
        return ProcessHandle.current().info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
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

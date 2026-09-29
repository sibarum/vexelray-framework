package ${packageName};

import dev.vexelray.framework.api.BeforeFrame;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the metronome does and what the process is doing about it.
 *
 * <p>The frame counter is a frame hook, so it counts what the loop actually ran and nothing else. It is
 * <b>never drawn</b>: a readout rewritten every frame would itself owe the next frame, and a loop that wakes
 * itself cannot be measured for whether it parks. The count is copied out only when {@link #snapshot} is asked
 * for, by a button, so a sample costs a few frames and the difference between two of them is the loop's own rate.
 */
final class Meter {

    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicInteger samples = new AtomicInteger();
    private volatile boolean running;
    private volatile Runnable changed = () -> { };

    /** Counted in the frame, on the main thread. One increment and nothing allocated. */
    @BeforeFrame
    public void frame() {
        frames.incrementAndGet();
    }

    void onChange(Runnable listener) {
        this.changed = listener;
    }

    boolean running() {
        return running;
    }

    void running(boolean running) {
        this.running = running;
    }

    void tick() {
        ticks.incrementAndGet();
        changed.run();
    }

    String ticksText() {
        return "ticks " + ticks.get();
    }

    /**
     * Frames run, live threads and the heap in use after a collection — the three things a long run can quietly
     * grow. Numbered, so a driver waits for the sample it asked for rather than one that was already there.
     */
    String snapshot() {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        return "sample #" + samples.incrementAndGet() + " frames " + frames.get() + " threads " + threads
                + " heap " + heapMb;
    }
}

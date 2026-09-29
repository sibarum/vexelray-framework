package ${packageName};

import dev.vexelray.framework.api.BeforeFrame;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A frame counter and a stopwatch that the frame itself stops.
 *
 * <p>The hook runs in {@code FrameStage.APP}, once per frame, on the main thread: it counts, and if a probe has
 * armed it, stamps the first frame that starts after the arming. That is the moment the loop woke and got as far
 * as running application code, which is what "latency from a wake to a frame" is measured to. It is not the
 * moment the frame reaches the screen, which the presenter owns, and this does not claim to measure that.
 *
 * <p>One volatile write per frame and nothing allocated, so the hook costs the loop what a counter costs.
 */
final class Meter {

    private final AtomicLong frames = new AtomicLong();
    private volatile long armedAt;
    private volatile long firstFrameAfter;

    @BeforeFrame
    public void frame() {
        frames.incrementAndGet();
        long armed = armedAt;
        if (armed != 0L && firstFrameAfter == 0L) {
            long now = System.nanoTime();
            if (now - armed >= 0L) {
                firstFrameAfter = now;
            }
        }
    }

    long frames() {
        return frames.get();
    }

    /** Start the stopwatch: the next frame to begin stops it. */
    void arm() {
        firstFrameAfter = 0L;
        armedAt = System.nanoTime();
    }

    /** Nanoseconds from {@link #arm} to the first frame after it, or {@code -1} if none has started yet. */
    long elapsed() {
        long stopped = firstFrameAfter;
        return stopped == 0L ? -1L : stopped - armedAt;
    }
}

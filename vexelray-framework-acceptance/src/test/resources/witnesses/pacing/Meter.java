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

    private static final int RING = 256;

    private final AtomicLong frames = new AtomicLong();
    /** The start of each of the last {@link #RING} frames, written by the frame and read by a handler. */
    private final long[] starts = new long[RING];
    private volatile long armedAt;
    private volatile long firstFrameAfter;

    @BeforeFrame
    public void frame() {
        long now = System.nanoTime();
        long n = frames.get();
        starts[(int) (n & (RING - 1))] = now;
        frames.set(n + 1);
        long armed = armedAt;
        if (armed != 0L && firstFrameAfter == 0L && now - armed >= 0L) {
            firstFrameAfter = now;
        }
    }

    /**
     * The gaps, in microseconds, between consecutive frame starts from the first frame at or after {@code from}.
     * The measure of smoothness: a loop that is smooth has gaps that are all about the same, and a hiccup is one
     * that is not. Read from the ring, so it covers at most the last {@link #RING} frames.
     */
    long[] gapsSince(long from) {
        long n = frames.get();
        long first = Math.max(0, n - RING + 1);
        java.util.List<Long> out = new java.util.ArrayList<>();
        long previous = -1L;
        for (long i = first; i < n; i++) {
            long s = starts[(int) (i & (RING - 1))];
            if (s - from < 0L) {
                continue;
            }
            if (previous >= 0L) {
                out.add((s - previous) / 1_000L);
            }
            previous = s;
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * When, in milliseconds after {@code from}, each frame began whose gap from the one before it was over
     * {@code thresholdMicros}. Says where in an animation a hiccup falls, which is what points at its cause: the
     * first frame after a park, the last before the animation ends, or somewhere in the middle.
     */
    String lateAt(long from, long thresholdMicros) {
        long n = frames.get();
        long first = Math.max(0, n - RING + 1);
        StringBuilder out = new StringBuilder();
        long previous = -1L;
        for (long i = first; i < n; i++) {
            long s = starts[(int) (i & (RING - 1))];
            if (s - from < 0L) {
                continue;
            }
            if (previous >= 0L && (s - previous) / 1_000L > thresholdMicros) {
                out.append(out.length() == 0 ? "" : ",").append((s - from) / 1_000_000L).append("ms(gap ")
                        .append((s - previous) / 1_000_000L).append("ms)");
            }
            previous = s;
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** The start of the latest frame that began at or before {@code t}, or {@code -1} if the ring has none. */
    long frameStartAtOrBefore(long t) {
        long n = frames.get();
        for (long i = n - 1; i >= 0 && i > n - RING; i--) {
            long s = starts[(int) (i & (RING - 1))];
            if (s - t <= 0L) {
                return s;
            }
        }
        return -1L;
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

package ${packageName};

import dev.vexelray.framework.api.BeforeFrame;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Asks Windows for a 1 ms timer resolution for the life of the process when {@code pacing.timer} is {@code 1ms},
 * and does nothing otherwise. An experiment's switch and not a proposal: it is here to find out whether the
 * default resolution is what makes a 16 ms timed wait return late.
 */
final class TimerResolution {

    TimerResolution(String mode) {
        if (!mode.equals("1ms") && !mode.equals("optout")) {
            return;
        }
        try {
            java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker();
            java.lang.foreign.Arena arena = java.lang.foreign.Arena.global();
            java.lang.foreign.SymbolLookup winmm = java.lang.foreign.SymbolLookup.libraryLookup("winmm", arena);
            java.lang.invoke.MethodHandle begin = linker.downcallHandle(winmm.find("timeBeginPeriod").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                            java.lang.foreign.ValueLayout.JAVA_INT));
            int result = (int) begin.invokeExact(1);
            System.out.println("pacing: timeBeginPeriod(1) -> " + result);
            if (mode.equals("optout")) {
                // Windows 11 stops honouring a timer resolution request for a process with no visible, foreground
                // window, unless the process says it wants it honoured: ProcessPowerThrottling (class 4), with
                // IGNORE_TIMER_RESOLUTION (0x4) in the control mask and clear in the state mask.
                java.lang.foreign.SymbolLookup k32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", arena);
                java.lang.invoke.MethodHandle current = linker.downcallHandle(k32.find("GetCurrentProcess").orElseThrow(),
                        java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS));
                java.lang.invoke.MethodHandle set = linker.downcallHandle(k32.find("SetProcessInformation").orElseThrow(),
                        java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                                java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_INT,
                                java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_INT));
                java.lang.foreign.MemorySegment state = arena.allocate(12);
                state.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 1);
                state.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, 0x4);
                state.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, 0);
                java.lang.foreign.MemorySegment process = (java.lang.foreign.MemorySegment) current.invokeExact();
                int ok = (int) set.invokeExact(process, 4, state, 12);
                System.out.println("pacing: SetProcessInformation(ProcessPowerThrottling, ignore timer resolution "
                        + "throttling) -> " + ok);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("timer resolution request failed", t);
        }
    }
}

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

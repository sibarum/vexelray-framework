package dev.vexelray.framework.core;

import dev.vexelray.framework.api.FrameStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which stage held the main thread, and whether anybody is told.
 *
 * <p>A frame loop that parks when there is nothing to do makes a stopped window and an idle window look the
 * same from outside: no exception, no log line, no dropped-frame count — the application is just not there for
 * a third of a second. So the measurement is the feature, and these hold the three things that make it worth
 * having: it fires, it names the <em>stage</em> rather than the frame, and it stays quiet when nothing is
 * wrong.
 *
 * <p>Measured here and reported elsewhere, which is why this module can test it at all: {@code -core} is
 * JDK-only and has no diagnostics channel to assert against, so the seam hands out a stage and a duration and
 * a test can take them directly.
 */
final class FrameHooksOverrunTest {

    private record Overrun(FrameStage stage, long nanos) {
    }

    /** Busy-wait rather than sleep: a hook that blocks the main thread is the thing being simulated. */
    private static void hold(long millis) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    @Test
    void theStageThatOverranIsTheOneReported() {
        List<Overrun> seen = new ArrayList<>();
        FrameHooks hooks = new FrameHooks()
                .add(FrameStage.INPUT, () -> { })
                .add(FrameStage.CLOCK, () -> { })
                .add(FrameStage.APP, () -> hold(60))
                .add(FrameStage.SETTLE, () -> { });
        hooks.onOverrun(TimeUnit.MILLISECONDS.toNanos(30), (stage, nanos) -> seen.add(new Overrun(stage, nanos)));
        hooks.seal();

        hooks.run();

        // Exactly one, and the right one. A whole-frame timer could only have said "the frame took 60ms",
        // which is the difference between a number and an address: APP is the application's own queue, and
        // knowing that is most of the way to knowing what to look at.
        assertEquals(1, seen.size(), "expected one report, got " + seen);
        assertEquals(FrameStage.APP, seen.get(0).stage());
        assertTrue(seen.get(0).nanos() >= TimeUnit.MILLISECONDS.toNanos(60),
                "the duration reported was shorter than the hook actually took");
    }

    @Test
    void aFrameThatKeepsItsBudgetSaysNothing() {
        AtomicLong reports = new AtomicLong();
        FrameHooks hooks = new FrameHooks()
                .add(FrameStage.INPUT, () -> { })
                .add(FrameStage.APP, () -> { })
                .onOverrun(TimeUnit.MILLISECONDS.toNanos(30), (stage, nanos) -> reports.incrementAndGet());
        hooks.seal();

        for (int i = 0; i < 100; i++) {
            hooks.run();
        }

        // The whole value of this instrument is that it is silent until it is not. One that chirped on
        // ordinary frames would be switched off, and then the frame that mattered would go unreported with it.
        assertEquals(0, reports.get(), "an ordinary frame was reported as a stall");
    }

    @Test
    void severalHooksInOneStageAreTimedTogetherAndReportedOnce() {
        List<Overrun> seen = new ArrayList<>();
        FrameHooks hooks = new FrameHooks()
                .add(FrameStage.APP, () -> hold(25))
                .add(FrameStage.APP, () -> hold(25))
                .add(FrameStage.APP, () -> hold(25))
                .onOverrun(TimeUnit.MILLISECONDS.toNanos(50), (stage, nanos) -> seen.add(new Overrun(stage, nanos)));
        hooks.seal();

        hooks.run();

        // A stage is the unit, not a hook. Three hooks that are each fine and together are not is a real way
        // for a frame to be lost, and reporting per hook would miss it while reporting three times over.
        assertEquals(1, seen.size(), "expected the stage to be reported once, got " + seen);
        assertEquals(FrameStage.APP, seen.get(0).stage());
        assertTrue(seen.get(0).nanos() >= TimeUnit.MILLISECONDS.toNanos(75));
    }

    @Test
    void measurementIsOffWhenNoThresholdIsSetAndTheHooksStillRun() {
        AtomicLong ran = new AtomicLong();
        FrameHooks hooks = new FrameHooks().add(FrameStage.APP, ran::incrementAndGet);
        hooks.seal();

        hooks.run();
        hooks.run();

        // The default. A FrameHooks nobody wired a listener to is the one -core's own tests and any embedder
        // gets, and it has to walk exactly as it did before this existed.
        assertEquals(2, ran.get());
    }

    @Test
    void stageOrderSurvivesTheRunsThatWereAddedToTimeThem() {
        List<String> order = new ArrayList<>();
        FrameHooks hooks = new FrameHooks()
                .add(FrameStage.SETTLE, () -> order.add("settle"))
                .add(FrameStage.APP, () -> order.add("app-1"))
                .add(FrameStage.INPUT, () -> order.add("input"))
                .add(FrameStage.APP, () -> order.add("app-2"))
                .onOverrun(TimeUnit.SECONDS.toNanos(1), (stage, nanos) -> { });
        hooks.seal();

        hooks.run();

        // The precomputed stage runs are an index into the same array the walk always used, so a mistake in
        // building them would show up here as work happening in the wrong order -- which is the one failure
        // that would be invisible in a screenshot and expensive to attribute.
        assertEquals(List.of("input", "app-1", "app-2", "settle"), order);
    }
}

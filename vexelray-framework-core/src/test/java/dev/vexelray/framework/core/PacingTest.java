package dev.vexelray.framework.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The {@code min} that decides how long a render-on-demand loop is allowed to sleep. */
final class PacingTest {

    private static DeadlineSource in(long nanos) {
        return () -> nanos;
    }

    private static final DeadlineSource IDLE = in(Long.MAX_VALUE);

    @Test
    void noSourcesParksIndefinitely() {
        assertEquals(Long.MAX_VALUE, new Pacing().seal().nanosUntilNextFrame());
    }

    @Test
    void theEarliestDeadlineWins() {
        Pacing pacing = new Pacing()
                .add(in(16_000_000L))     // an animation, next frame
                .add(in(700_000_000L))    // window placement, debounced
                .seal();

        assertEquals(16_000_000L, pacing.nanosUntilNextFrame());
    }

    /** The failure this seam exists to prevent, from the other side: an idle source must not park the loop. */
    @Test
    void anIdleSourceDoesNotHideAPendingOne() {
        Pacing pacing = new Pacing().add(IDLE).add(in(700_000_000L)).add(IDLE).seal();

        assertEquals(700_000_000L, pacing.nanosUntilNextFrame());
    }

    @Test
    void allIdleParksIndefinitely() {
        assertEquals(Long.MAX_VALUE, new Pacing().add(IDLE).add(IDLE).seal().nanosUntilNextFrame());
    }

    /**
     * An overdue source reports a negative interval, and a negative timeout means "wait forever" to some of the
     * platform waits underneath this. Clamping is what stops a missed deadline becoming a hang.
     */
    @Test
    void anOverdueDeadlineClampsToNowRatherThanGoingNegative() {
        assertEquals(0L, new Pacing().add(in(-2_000_000L)).add(IDLE).seal().nanosUntilNextFrame());
    }

    @Test
    void zeroMeansNow() {
        assertEquals(0L, new Pacing().add(in(0L)).seal().nanosUntilNextFrame());
    }

    @Test
    void sourcesAreReAskedEveryTimeBecauseDeadlinesMove() {
        long[] answer = {50L};
        Pacing pacing = new Pacing().add(() -> answer[0]).seal();

        assertEquals(50L, pacing.nanosUntilNextFrame());
        answer[0] = 10L;
        assertEquals(10L, pacing.nanosUntilNextFrame());
    }

    @Test
    void addingAfterSealIsRefused() {
        Pacing pacing = new Pacing().seal();
        assertThrows(IllegalStateException.class, () -> pacing.add(IDLE));
    }
}

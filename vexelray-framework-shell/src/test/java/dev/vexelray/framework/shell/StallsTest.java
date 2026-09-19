package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.FrameStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sentence somebody reads at three in the morning when the window froze.
 *
 * <p>That is the deliverable, not the measurement — {@code FrameHooksOverrunTest} already holds that the right
 * stage is detected. What matters here is whether the report names a thing to look at. A stall reported as
 * <i>"frame overrun: 412ms"</i> is a number, and the reader's next move is to guess; the whole reason this
 * exists is that guessing is what the stack has been doing.
 *
 * <p>So these assert the content. {@code Diagnostics.recorded()} fills whether or not printing is silenced,
 * which is what makes a warning provable rather than taken on faith.
 */
final class StallsTest {

    @BeforeEach
    @AfterEach
    void silence() {
        // Warn-once is global and so is the record, so each case starts from silence and leaves it that way.
        Diagnostics.reset();
    }

    @Test
    void theReportNamesTheStageTheDurationAndWhatToDo() {
        Stalls.stalled(FrameStage.APP, TimeUnit.MILLISECONDS.toNanos(412));

        List<String> said = Diagnostics.recorded();
        assertEquals(1, said.size(), "expected exactly one report, got " + said);
        String message = said.get(0);

        // Four things, and each is load-bearing. Which stage, so the reader knows which code. How long, so
        // they know whether it is the thing they are chasing. What that stage is for, so they can see the
        // rule that was broken. And where the work should have gone, so the report ends in a fix rather than
        // in a diagnosis.
        assertTrue(message.contains("FrameStage.APP"), message);
        assertTrue(message.contains("412 ms"), message);
        assertTrue(message.contains("draining a queue"), message);
        assertTrue(message.contains("offload"), message);
    }

    @Test
    void eachStageCarriesItsOwnAdvice() {
        // On the stage rather than in a switch here, which is this repo's rule about behaviour living on the
        // type it belongs to -- and the reason it is worth holding is that a stage added later gets a useless
        // report by omission if the advice lives anywhere else.
        for (FrameStage stage : FrameStage.values()) {
            String advice = stage.whenSlow();
            assertTrue(advice != null && !advice.isBlank(), stage + " has nothing to say when it is slow");
            assertTrue(advice.contains(stage.name()) || advice.contains("timeline") || advice.contains("input"),
                    stage + " gives advice that does not identify what runs there: " + advice);
        }
    }

    @Test
    void aStageRepeatingItsStallIsSaidOnce() {
        for (int i = 0; i < 50; i++) {
            Stalls.stalled(FrameStage.CLOCK, TimeUnit.MILLISECONDS.toNanos(300));
        }

        // A stall usually repeats every frame. Fifty copies of the report would bury the first, and a channel
        // that floods is one people learn to filter -- which loses the next warning too.
        assertEquals(1, Diagnostics.recorded().size(), "warn-once did not hold for a repeating stall");
    }

    @Test
    void twoStagesStallingAreTwoReports() {
        Stalls.stalled(FrameStage.APP, TimeUnit.MILLISECONDS.toNanos(300));
        Stalls.stalled(FrameStage.SETTLE, TimeUnit.MILLISECONDS.toNanos(300));

        // Keyed by stage rather than by one key for the whole feature: two different stages blocking are two
        // different bugs, and collapsing them would report the first and hide the second forever.
        assertEquals(2, Diagnostics.recorded().size(), Diagnostics.recorded().toString());
    }

    @Test
    void theThresholdIsGenerousByDefaultAndCanBeTurnedDownForACensus() {
        // Fifteen frames. High enough that a heavy first frame does not cry wolf, because a warning that
        // fires when nothing is wrong is one that gets filtered out along with the one that mattered.
        assertEquals(TimeUnit.MILLISECONDS.toNanos(250), Stalls.thresholdNanos());

        String key = "vexelray.stall.ms";
        String previous = System.getProperty(key);
        try {
            // The setting for answering "what still blocks?" rather than "did something block?" -- which is a
            // real open question here: the handler lane cannot be bounded until somebody knows.
            System.setProperty(key, "16");
            assertEquals(TimeUnit.MILLISECONDS.toNanos(16), Stalls.thresholdNanos());

            System.setProperty(key, "0");
            assertEquals(0L, Stalls.thresholdNanos(), "zero should switch the measurement off entirely");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}

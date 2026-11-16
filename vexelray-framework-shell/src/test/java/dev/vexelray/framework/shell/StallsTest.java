package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.api.FrameStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(message.contains("@Job"), message);
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

    // ---- what the watchdog asks of a stuck main thread's stack --------------------------------------------------

    private static StackTraceElement at(String cls, String method) {
        return new StackTraceElement(cls, method, null, -1);
    }

    private static final StackTraceElement LOOP = at("dev.vexelray.gui.core.app.GuiApp", "run");
    private static final StackTraceElement MAIN = at("app.Main", "main");

    @Test
    void theOperatingSystemsModalLoopInsideThePumpIsServingNotStuck() {
        // A window's system menu, or a drag: the OS's loop runs inside the pump, with nothing of ours above it.
        assertTrue(Stalls.servingOs(new StackTraceElement[] {
                at("jdk.internal.foreign.abi.DowncallStub", "invoke"),
                at("dev.vexelray.os.windows.Win32Window", Stalls.PUMP),
                at("dev.vexelray.gui.core.app.GuiWindow", "frame"), LOOP, MAIN}));
        assertTrue(Stalls.servingOs(new StackTraceElement[] {
                at("dev.vexelray.os.windows.Win32Window", Stalls.WAIT), LOOP, MAIN}), "a park is not a wedge");
    }

    @Test
    void aNativeDialogOnTheMainThreadIsServingNotStuck() {
        assertTrue(Stalls.servingOs(new StackTraceElement[] {
                at(dev.vexelray.gui.nfd.Nfd.class.getName(), "openDialog"),
                at("dev.vexelray.gui.nfd.FileDialog", "open"),
                at("dev.vexelray.gui.core.app.GuiApp", "iterationTop"), LOOP, MAIN}));
    }

    @Test
    void aPostedTaskOrAFrameThatNeverReturnsIsStuck() {
        // A posted task: the pump has not run yet this iteration, so nothing of it is on the stack.
        assertFalse(Stalls.servingOs(new StackTraceElement[] {
                at("java.lang.Object", "wait"), at("app.Saver", "save"),
                at("dev.vexelray.gui.core.app.GuiApp", "iterationTop"), LOOP, MAIN}));
        // The frame's own work, pulled from inside a drag: the frame is innermost, so the pump below is the drag's.
        assertFalse(Stalls.servingOs(new StackTraceElement[] {
                at("app.Logic", "tick"), at(dev.vexelray.framework.core.FrameHooks.class.getName(), "run"),
                at("dev.vexelray.gui.core.app.GuiWindow", "pullLive"),
                at("dev.vexelray.os.windows.Win32Window", Stalls.PUMP), LOOP, MAIN}));
    }

    @Test
    void aStackWithNothingInItIsGivenTheBenefitOfTheDoubt() {
        // A runtime that keeps no frames cannot say, and a halt fired at a person is worse than a missed wedge.
        assertTrue(Stalls.servingOs(new StackTraceElement[0]));
    }

    /** The pump's names are strings because a stack frame's are; this holds them to the interface they come from. */
    @Test
    void thePumpsNamesAreTheWindowInterfacesOwn() throws Exception {
        dev.vexelray.os.NativeWindow.class.getMethod(Stalls.PUMP);
        dev.vexelray.os.NativeWindow.class.getMethod(Stalls.WAIT, long.class);
    }
}

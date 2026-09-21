package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.app.WindowInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this module does when a capability it asked for is not there.
 *
 * <p>Each of these seams answers absence with a degraded object rather than an exception, on grounds
 * {@link InputBackend} states for all of them — <i>"a window nobody can click is a degraded window rather than
 * a failed launch"</i>. That is the right call and it has a cost: the application starts, draws, and is missing
 * something, which is {@code Diagnostics}' own subject — <i>"nothing threw, nothing warned, and each produced a
 * plausible picture."</i>
 *
 * <p>So what is asserted here is not the degradation, which was already covered, but that <b>it is announced</b>.
 * {@code Diagnostics.recorded()} is the half that makes that possible: it fills whether or not printing is
 * silenced, so a warning can be proven to fire rather than taken on faith.
 *
 * <p>No GPU. These are the paths where a backend could not be opened at all, which is the state this module's
 * own test classpath is in — {@code tactroller-api} carries the SPI and no platform module implements it here,
 * so {@code Tactroller.open()} fails for the same reason it would on a machine with no input stack.
 */
final class DroppedCapabilitiesTest {

    @BeforeEach
    @AfterEach
    void silence() {
        // Warn-once is global and so is the record, so each case has to start from silence — and has to leave
        // it that way, or WakeSeamsTest's own InputBackend.open finds the key already used.
        Diagnostics.reset();
    }

    @Test
    void anInputBackendThatCannotOpenSaysSo() {
        InputBackend input = InputBackend.open();

        assertFalse(input.present(), "no platform module on this classpath, so there is nothing to open");
        assertEquals(1, Diagnostics.recorded().size(), "one report: " + Diagnostics.recorded());
        String message = Diagnostics.recorded().get(0);
        assertTrue(message.contains("pointer and keyboard input"), message);
        assertTrue(message.contains("nothing in it can be clicked"),
                "the report has to say what it costs, not just what failed: " + message);
    }

    @Test
    void aWindowThatEndsUpWithNoInputSaysSoRatherThanReturningNoneQuietly() {
        // The arguments are never read on this path: the factory opens its backend first, and that is what
        // fails, so nothing here reaches the window, its Gui, or the pointer lock that would have been tuned
        // from the first argument. Passing null three times is the honest way to say that.
        WindowInput windowInput = InputBackend.perWindow(null).attach(null, null);

        assertSame(WindowInput.NONE, windowInput, "a window with no backend still has to be a window");
        assertEquals(1, Diagnostics.recorded().size(), "one report: " + Diagnostics.recorded());
        assertTrue(Diagnostics.recorded().get(0).contains("takes no input"), Diagnostics.recorded().get(0));
    }

    @Test
    void aSecondWindowWithTheSameFaultDoesNotRepeatTheWarning() {
        InputBackend.perWindow(null).attach(null, null);
        InputBackend.perWindow(null).attach(null, null);
        InputBackend.perWindow(null).attach(null, null);

        // Warn-once is per call site and not per window, deliberately: this seam runs once per window an
        // application opens, and "a warning that repeats is a warning that gets filtered out". The cost is
        // that the third failing window is silent, which is the trade Diagnostics documents and takes.
        List<String> recorded = Diagnostics.recorded();
        assertEquals(1, recorded.size(), "three failures, one report: " + recorded);
    }

    @Test
    void twoDifferentDroppedCapabilitiesAreTwoReports() {
        InputBackend.open();
        InputBackend.perWindow(null).attach(null, null);

        // Distinct keys, so neither silences the other — which is the thing five hand-written printlns could
        // not have got wrong and one shared warn-once table could.
        assertEquals(2, Diagnostics.recorded().size(), "" + Diagnostics.recorded());
    }
}

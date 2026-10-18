package dev.vexelray.framework.automation;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.framework.shell.Wiring;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import dev.vexelray.gui.automation.Capability;
import dev.vexelray.gui.automation.Sockets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The socket that was asked for and not bound.
 *
 * <p>{@code Driver.open} answers a bad port by carrying on, and the reason is written beside it: <i>"an
 * application that will not start because a debugging port was busy is a worse outcome than one nobody can
 * drive."</i> True, and it leaves the launch in the state {@code Launch.FRAMEWORK_KEYS} exists to prevent —
 * {@code --automation=7654} accepted, parsed, and connected to nothing. The report is what closes that, and
 * this is the test that it happens.
 *
 * <p>No GPU and no window. {@code Integer.parseInt} runs before the {@code Automation} is constructed, so the
 * malformed-port path never reaches the windows — which is what makes this case, alone among the ways
 * a socket can fail to bind, assertable against a bare {@link Shell}.
 */
final class DriverTest {

    /** Its own settings directory name, redirected to a temp dir so no test writes into a developer's home. */
    private static final String NAME = "vexelray-framework-drivertest";

    @BeforeEach
    @AfterEach
    void silence() {
        Diagnostics.reset();
    }

    /**
     * A {@link Shell} built as far as {@code TREE}, which is as much of one as this needs and the only kind a
     * test can get: {@code Shell}'s constructor is package-private, because {@code VexelApplication} is the
     * only thing that should be building one. {@code VexelApplication.tree} is the public door, and it stops
     * before there is a window — which is exactly the state the assertions below need.
     */
    private static Shell shell(Path home, String... args) {
        System.setProperty(NAME + ".home", home.toString());
        try {
            return VexelApplication.tree(new BareWiring(), args);
        } finally {
            System.clearProperty(NAME + ".home");
        }
    }

    @Test
    void aPortThatIsNotANumberIsReportedRatherThanThrown(@TempDir Path home) {
        Shell shell = shell(home, "--automation=wednesday");
        try (Driver driver = Driver.open(shell, NONE, NONE)) {
            assertFalse(driver.bound(), "nothing was bound, so nothing can drive this application");
            assertEquals(1, Diagnostics.recorded().size(), "" + Diagnostics.recorded());
            String message = Diagnostics.recorded().get(0);
            assertTrue(message.contains("automation socket"), message);
            // The value it could not use, because "the port was bad" without saying which value sends a reader
            // looking at the wrong one of the two places this setting can come from.
            assertTrue(message.contains("wednesday"), "the report has to name what it was given: " + message);
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void anApplicationThatAskedForNoSocketReportsNothing(@TempDir Path home) {
        Shell shell = shell(home);
        try (Driver driver = Driver.open(shell, NONE, NONE)) {
            assertFalse(driver.bound(), "off by default");
            // The distinction the whole class rests on: a capability nobody asked for is not a dropped one,
            // and a diagnostic that fired on every ordinary launch would be filtered out before the real one
            // arrived.
            assertEquals(List.of(), Diagnostics.recorded(),
                    "silence is the right answer when nothing was asked for");
        } finally {
            shell.disposer().close();
        }
    }

    /**
     * The agent's shell asks for a socket with nothing on the command line. Proved through a value the parse
     * rejects, the one way to see a request reach the sockets with no window behind them, and the report names
     * where the value came from, since nothing the reader typed contains it.
     */
    @Test
    void theEnvironmentAsksWhenTheLaunchSaysNothing(@TempDir Path home) {
        Shell shell = shell(home);
        try (Driver driver = Driver.open(shell, NONE, Map.of(Driver.ENVIRONMENT, "wednesday")::get)) {
            assertFalse(driver.bound());
            assertEquals(1, Diagnostics.recorded().size(), "" + Diagnostics.recorded());
            String message = Diagnostics.recorded().get(0);
            assertTrue(message.contains("wednesday"), message);
            assertTrue(message.contains(Driver.ENVIRONMENT), "the report names the environment: " + message);
        } finally {
            shell.disposer().close();
        }
    }

    /** A launch that says anything about its sockets, even off, has said more than the shell it runs in. */
    @Test
    void aFlagOrPropertyOutranksTheEnvironment(@TempDir Path home) {
        Shell shell = shell(home, "--automation=off");
        try (Driver driver = Driver.open(shell, NONE, Map.of(Driver.ENVIRONMENT, "wednesday")::get)) {
            assertFalse(driver.bound());
            assertEquals(List.of(), Diagnostics.recorded(), "the flag said off, so the environment was never read");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void theOrderIsFlagThenPropertyThenEnvironment() {
        Function<String, String> env = Map.of(Driver.ENVIRONMENT, " 0 ")::get;
        assertEquals("0", Driver.resolver(NONE, NONE, env).apply(Driver.SETTING), "trimmed, as Sockets reads it");
        assertEquals("7000", Driver.resolver(NONE, Map.of(Driver.SETTING, "7000")::get, env).apply(Driver.SETTING));
        assertEquals("7001", Driver.resolver(Map.of(Driver.SETTING, "7001")::get, Map.of(Driver.SETTING, "7000")::get,
                env).apply(Driver.SETTING));
        assertEquals(null, Driver.resolver(NONE, NONE, env).apply(Sockets.CONTROL_SETTING),
                "the environment speaks for automation alone");
        assertEquals(null, Driver.resolver(NONE, NONE, Map.of(Driver.ENVIRONMENT, " ")::get).apply(Driver.SETTING),
                "blank is unset");
    }

    /**
     * {@code --automation} wins over the per-capability settings inside {@code Sockets}, so an environment value
     * filling it in would override a launch's own {@code --automation.control}.
     */
    @Test
    void anyPerCapabilitySettingKeepsTheEnvironmentOut() {
        Function<String, String> env = Map.of(Driver.ENVIRONMENT, "0")::get;
        Function<String, String> control = Driver.resolver(Map.of(Sockets.CONTROL_SETTING, "7000")::get, NONE, env);
        assertEquals(null, control.apply(Driver.SETTING));
        assertEquals("7000", control.apply(Sockets.CONTROL_SETTING));
        Function<String, String> diagnostics =
                Driver.resolver(NONE, Map.of(Sockets.DIAGNOSTICS_SETTING, "off")::get, env);
        assertEquals(null, diagnostics.apply(Driver.SETTING));
    }

    /** A run that offers no socket opens none, whatever the launch asks for, and says so. */
    @Test
    void aSocketTheRunDoesNotOfferIsNotOpenedAndThatIsSaid(@TempDir Path home) {
        Shell shell = shell(home, "--automation=0", "--automation.available=none");
        try (Driver driver = Driver.open(shell, NONE, NONE)) {
            assertFalse(driver.bound());
            assertEquals(Set.of(), driver.available());
            assertEquals(1, Diagnostics.recorded().size(), "" + Diagnostics.recorded());
            String message = Diagnostics.recorded().get(0);
            assertTrue(message.contains("not available in this run (automation.available=none)"), message);
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void whatARunOffersIsReadFlagThenPropertyThenEnvironment() {
        Function<String, String> env = Map.of(Driver.AVAILABLE_ENVIRONMENT, "none")::get;
        assertEquals(Capability.both(), Driver.available(NONE, NONE, NONE), "both, unless somebody says otherwise");
        assertEquals(Set.of(), Driver.available(NONE, NONE, env));
        assertEquals(Set.of(Capability.DIAGNOSTICS),
                Driver.available(NONE, Map.of(Driver.AVAILABLE, "diagnostics")::get, env));
        assertEquals(Set.of(Capability.CONTROL), Driver.available(Map.of(Driver.AVAILABLE, "control")::get,
                Map.of(Driver.AVAILABLE, "diagnostics")::get, env));
        assertEquals(List.of(), Diagnostics.recorded());
    }

    /** Whoever wrote a value that is not one of the words meant to shut something, so nothing is offered. */
    @Test
    void aValueThatIsNotOneOfTheWordsOffersNothing() {
        assertEquals(Set.of(), Driver.available(Map.of(Driver.AVAILABLE, "contrl")::get, NONE, NONE));
        assertEquals(1, Diagnostics.recorded().size(), "" + Diagnostics.recorded());
        assertTrue(Diagnostics.recorded().get(0).contains("contrl"), Diagnostics.recorded().get(0));
    }

    /** Neither a property nor an environment variable, so no test reads or writes the process's own. */
    private static final Function<String, String> NONE = key -> null;

    /** Nothing in any phase. All this test needs from an application is that it has a {@code Launch}. */
    private static final class BareWiring extends Wiring {

        @Override
        public AppInfo info() {
            return new AppInfo(NAME, "Driver Test", 400, 300);
        }
    }
}

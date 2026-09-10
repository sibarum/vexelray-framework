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
import java.util.List;

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
 * malformed-port path never reaches {@code shell.app()} — which is what makes this case, alone among the ways
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
        try (Driver driver = Driver.open(shell)) {
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
        try (Driver driver = Driver.open(shell)) {
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

    /** Nothing in any phase. All this test needs from an application is that it has a {@code Launch}. */
    private static final class BareWiring implements Wiring {

        @Override
        public AppInfo info() {
            return new AppInfo(NAME, "Driver Test", 400, 300);
        }
    }
}

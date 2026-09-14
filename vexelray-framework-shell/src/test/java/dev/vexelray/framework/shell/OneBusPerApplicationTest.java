package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * One bus for the application, and every tree the framework builds on it.
 *
 * <p><b>The state this replaces.</b> {@code new Gui()} is {@code this(Atchung.create())}, so a tree built
 * without being handed a bus arrives carrying one of its own. The framework built its {@code Gui} that way and
 * so did {@code Modals}, which meant a calculator — a one-window application by any reading — ran two buses,
 * and a subscriber on either was deaf to the other. Nothing was broken by it, because nothing yet published
 * anything either half needed to hear. That changes the moment a component does, which is why this is the
 * first move of the concurrency model rather than a tidy-up.
 *
 * <p><b>No GPU.</b> {@link VexelApplication#tree} builds as far as {@code Phase.TREE} — a {@code Gui} and a
 * clock, no device and no window — which is exactly the reach needed to ask what fabric the tree is on. The
 * dialogs cannot be asked from here, because {@code Modals.install} takes a {@code GuiApp} and a dialog is a
 * real OS window; that half is asserted upstream, in {@code vexelray-gui-harness}'s
 * {@code DialogsWearTheApplicationsLookTest}.
 */
final class OneBusPerApplicationTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    private static Shell shell() {
        return new Shell(Launch.parse(new String[0], "demo", Set.of()), DEMO);
    }

    /** A wiring that says who it is and builds nothing — the framework's own tree is the subject here. */
    private static final class Bare extends Wiring {

        @Override
        public AppInfo info() {
            return DEMO;
        }
    }

    @Test
    void theTreeTheFrameworkBuildsIsOnTheApplicationsBus(@TempDir Path home) {
        // Every application on this stack keeps its state in $HOME/.{name}; a test that used the real one
        // would write into the developer's home and read it back next time.
        System.setProperty(DEMO.name() + ".home", home.toString());

        Shell shell = VexelApplication.tree(new Bare(), new String[0]);
        try {
            assertSame(shell.bus(), shell.gui().bus(),
                    "the framework built its Gui without handing it the application's bus, so the tree is on "
                            + "a fabric of its own");
        } finally {
            shell.disposer().close();
        }
    }

    /**
     * The bus is there before any phase has run, and it is the same one afterwards.
     *
     * <p>Both halves matter. A fabric that appeared at {@code Phase.GUI} could not be depended on by a
     * component built in {@code MODEL}, which is where the component model puts most of them; and one that
     * were replaced along the way would leave earlier subscribers holding a bus nobody publishes on any more.
     */
    @Test
    void theBusIsAvailableInEveryPhaseAndNeverChanges() {
        Shell shell = shell();

        assertNotNull(shell.bus(), "no bus in CONFIG, which is before a component could ask for one");
        Object atConfig = shell.bus();
        shell.phase(Phase.MODEL);
        assertSame(atConfig, shell.bus(), "the bus was replaced between phases");
        shell.phase(Phase.ATTACH);
        assertSame(atConfig, shell.bus(), "the bus was replaced between phases");
    }

    /**
     * Two applications in one JVM are two fabrics.
     *
     * <p>Guarding the shortcut rather than the feature: a static bus would satisfy every other assertion here
     * and quietly join two applications that share a process — which is this suite, and which is also the
     * embedded case {@code mainframe-template} is heading for.
     */
    @Test
    void twoApplicationsDoNotShareAFabric() {
        assertNotSame(shell().bus(), shell().bus());
    }
}

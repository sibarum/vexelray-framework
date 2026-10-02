package dev.vexelray.framework.acceptance;

import dev.vexelray.framework.acceptance.Support.Driver;
import dev.vexelray.framework.acceptance.Support.Project;
import dev.vexelray.framework.acceptance.Support.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.vexelray.framework.acceptance.Support.build;
import static dev.vexelray.framework.acceptance.Support.fresh;
import static dev.vexelray.framework.acceptance.Support.generate;
import static dev.vexelray.framework.acceptance.Support.launch;
import static dev.vexelray.framework.acceptance.Support.tail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W2, the component-heavy witness: a generated application whose interesting part is components on real lanes,
 * built and driven through its socket.
 *
 * <p><b>Why this shape.</b> The counter every earlier acceptance run drives has one thread of its own and no
 * component, so the whole concurrency model was validated by an application that does not use it. This one is
 * generated from the same builder and then given the parts the model is for — a worker on a lane of its own, two
 * components sharing the default lane, one component on another lane — plus a button that wedges a component,
 * because <i>a wedged component never stops the window responding</i> is a claim only a wedge can test.
 * Nothing in it is a wiring: the components are annotated, and the processor writes the rest.
 *
 * <p>Two launches of the one build. In {@code recover} the application's policy reports a stalled lane on
 * screen and interrupts it; in {@code exit} it keeps the framework's default action, so the path through
 * {@code Context.exit} and the window's own close route runs for real, which no unit test can reach.
 *
 * <p>Each finding is written up in {@code docs/architecture.md}, <i>what the component witness found</i>.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComponentsWitnessTest {

    private static final String ARTIFACT = "vexel-witness-components";
    private static final List<String> OVERLAY = List.of("Landmarks", "Bench", "Components", "Ui", "Recipes");

    private static Path root;
    private static Project project;
    private static Session live;

    @BeforeAll
    static void clearTheLastRun() throws IOException {
        root = fresh("components");
    }

    @AfterAll
    static void stopTheApplication() throws IOException {
        if (live != null) {
            live.close();
        }
    }

    // --- generate, overlay, build ----------------------------------------------------------------------------

    @Test
    @Order(1)
    void theBuilderGeneratesAProjectTheOverlayTurnsIntoAComponentHeavyOne() throws Exception {
        project = generate(root, ARTIFACT, "/witnesses/components", OVERLAY);
        build(project, root.resolve("build.log"));

        // Components with @Subscribe and no Placement parameter, and no hand-written wiring anywhere.
        Path wiring = project.wiring();
        assertTrue(Files.isRegularFile(wiring), "the processor did not generate " + wiring);
        String generated = Files.readString(wiring);
        assertTrue(generated.contains("Placements.of(shell, \"worker\")"), "no placement for the worker lane\n" + generated);
        assertTrue(generated.contains("Placements.of(shell, \"<default>\")"), "no placement for the default lane\n" + generated);
        // Four components, three lanes: Echo and Wedger name none, so they share the one default placement.
        assertEquals(3, generated.split("private dev\\.vexelray\\.framework\\.shell\\.Placement ", -1).length - 1,
                "four components on three lanes should be three placements\n" + generated);
        assertTrue(generated.contains("shell.liveness("), "the liveness policy was not handed back\n" + generated);
    }

    // --- recover: a wedge is reported, contained and freed ---------------------------------------------------

    @Test
    @Order(2)
    void aWedgedComponentNeverStopsTheWindowAndItsLaneIsFreedByThePolicy() throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "recover", "-Dwitness.policy=recover")) {
            Driver d = session.driver();
            d.ok("settle");

            // Components on their own lanes do work, and a result they publish reaches the tree with no wake
            // written anywhere: the placement owes it.
            d.ok("click " + d.ref("button.work"));
            d.ok("await result worked 1");
            d.ok("click " + d.ref("button.echo"));
            d.ok("await echo echoed 1");

            // A component on a lane of its own wedges. The window still takes input, and so does every other lane.
            d.ok("click " + d.ref("button.wedge.isolated"));
            d.ok("await isolated isolated wedged");
            d.ok("click " + d.ref("button.count"));
            d.ok("await count 1");
            d.ok("click " + d.ref("button.work"));
            d.ok("await result worked 2");
            d.ok("click " + d.ref("button.echo"));
            d.ok("await echo echoed 2");
            // Named by the watchdog once the threshold passes, and freed by the policy's interrupt.
            d.ok("await stall stall isolated");
            d.ok("await isolated isolated freed");

            // The default lane is shared, so a wedge there holds up its neighbours: the cost of the default,
            // stated as a test. Everything that is not on it carries on.
            d.ok("click " + d.ref("button.wedge.default"));
            d.ok("await default default wedged");
            d.ok("click " + d.ref("button.echo"));
            Thread.sleep(500);
            String echo = d.ok("find echo");
            assertTrue(echo.contains("echoed 2"), "an echo was delivered on a lane that is wedged\n" + echo);
            d.ok("click " + d.ref("button.count"));
            d.ok("await count 2");
            d.ok("click " + d.ref("button.work"));
            d.ok("await result worked 3");
            // The default lane is named, and once freed the queued echo is delivered.
            d.ok("await stall stall <default>");
            d.ok("await default default freed");
            d.ok("await echo echoed 3");

            Path shot = root.resolve("components.png");
            d.ok("shot " + shot);
            assertTrue(Files.size(shot) > 0, "the photograph is empty");
            session.out().println("quit");
        }
        live = null;
    }

    // --- exit: the default policy ends the application ------------------------------------------------------

    @Test
    @Order(3)
    void theDefaultPolicyExitsAnApplicationWhoseLaneIsWedgedThroughItsOwnCloseRoute() throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "exit", "-Dwitness.policy=exit")) {
            Driver d = session.driver();
            d.ok("settle");
            d.ok("click " + d.ref("button.wedge.default"));
            d.ok("await default default wedged");
            // Reported after the one-second threshold, then Context.exit: a close request through the window's
            // own route, and the process is ended regardless once the grace has passed.
            assertTrue(session.app().waitFor(90, TimeUnit.SECONDS),
                    "the application was still running 90 s after a lane wedged under the default policy\n"
                            + tail(session.log()));
            assertFalse(session.app().isAlive());
        }
        live = null;
    }
}

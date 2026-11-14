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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W2, the component-heavy witness: a generated application whose interesting part is components on real lanes,
 * built and driven through its socket.
 *
 * <p><b>Why this shape.</b> It is generated from the builder and given the parts the component model is for: a
 * worker on a declared lane, three components on the logic lane, one on another declared lane, and a view that is
 * the only thing touching widgets. Each component says what it did as a message and the view shows it; nothing is
 * shared between lanes, as a {@code Bench} of atomics once was. There are buttons that wedge a component, because
 * <i>a wedged component never stops the window responding</i> is a claim only a wedge can test, and the view keeps a
 * tap count of its own, which no lane can hold up. Nothing in it is a wiring: the processor writes it.
 *
 * <p><b>What 0.2 changed in what it proves.</b> A declared lane runs its share in the same rounds as the logic lane,
 * so a wedge on <em>any</em> lane holds every lane at the round's end, and the witness states that as a test: the
 * counter, a logic-lane component, waits out a wedge on the declared lane. The window does not.
 *
 * <p>Two launches of the one build. In {@code recover} the application's policy interrupts a stalled lane, and the
 * view shows the watchdog's {@code Stalled} message; in {@code exit} it keeps the framework's default action, so the
 * path through {@code Context.exit} and the window's own close route runs for real, which no unit test can reach.
 *
 * <p>Each finding is written up in {@code docs/architecture.md}, <i>what the component witness found</i>, and in
 * the ROADMAP's phase 7.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComponentsWitnessTest {

    private static final String ARTIFACT = "vexel-witness-components";
    private static final List<String> OVERLAY = List.of("Landmarks", "Components", "Ui", "Shown", "Recipes",
            "ToolWindow");

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
        Support.assertNoUnaskedScroll(root);
    }

    // --- generate, overlay, build ----------------------------------------------------------------------------

    @Test
    @Order(1)
    void theBuilderGeneratesAProjectTheOverlayTurnsIntoAComponentHeavyOne() throws Exception {
        project = generate(root, ARTIFACT, "/witnesses/components", OVERLAY);
        build(project, root.resolve("build.log"));

        Path wiring = project.wiring();
        assertTrue(Files.isRegularFile(wiring), "the processor did not generate " + wiring);
        String generated = Files.readString(wiring);
        assertTrue(generated.contains("shell.liveness("), "the liveness policy was not handed back\n" + generated);

        // The declared graph (vexelray-inspector docs/bus-graph.md): every component on its lane, the view on the GUI
        // thread's, each @Subscribe as a way of hearing, every send, each read from a constructor's senders (fork 5),
        // and each topic's kind, read off its constant when the wiring loads.
        String p = project.packageName() + ".";
        for (String line : List.of(
                "# vexelray bus graph 2",
                "part component " + p + "Counter lane=logic",
                "part component " + p + "Echo lane=logic",
                "part component " + p + "Wedger lane=logic",
                "part component " + p + "Isolated lane=isolated",
                "part component " + p + "Worker lane=worker",
                "part view " + p + "Shown lane=gui",
                "hears " + p + "Echo#ping topic=" + p + "Ping by=mailbox",
                "hears " + p + "Worker#job topic=" + p + "Job by=mailbox",
                "hears " + p + "Shown#pressed topic=dev.vexelray.gui.widget.Button.Pressed by=mailbox",
                "hears " + p + "Shown#stalled topic=dev.vexelray.framework.core.Stalled by=mailbox",
                "sends " + p + "Shown topic=" + p + "Job",
                "sends " + p + "Shown topic=dev.vexelray.framework.core.WindowCommand",
                "sends " + p + "Wedger topic=" + p + "Wedging",
                "sends " + p + "Worker topic=" + p + "Worked",
                "topic " + p + "Job kind=")) {
            assertTrue(generated.contains("\"" + line), "the bus graph has no \"" + line + "\"\n" + generated);
        }
    }

    // --- exit: the default policy ends the application ------------------------------------------------------

    @Test
    @Order(3)
    void theDefaultPolicyExitsAnApplicationWhoseLaneIsWedgedThroughItsOwnCloseRoute() throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "exit", "-Dwitness.policy=exit")) {
            Driver d = session.driver();
            d.firstFrame("button.wedge.default");
            d.ok("click " + d.ref("button.wedge.default"));
            d.ok("await default default asked");
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

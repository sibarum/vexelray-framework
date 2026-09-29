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
import java.nio.file.Path;
import java.util.List;

import static dev.vexelray.framework.acceptance.Support.build;
import static dev.vexelray.framework.acceptance.Support.fresh;
import static dev.vexelray.framework.acceptance.Support.generate;
import static dev.vexelray.framework.acceptance.Support.launch;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measurements of the frame loop's pacing, in the one place the loop can be watched running for real: a generated
 * application on a real window. It asserts almost nothing on purpose. What it produces is numbers, printed with a
 * {@code PACING} prefix, and what they mean is written up in {@code docs/architecture.md}.
 *
 * <p>Three questions, each about the design the loop exists for — park when idle, answer a wake at once, and
 * hold an animation to the display:
 * <ol>
 *   <li><b>Does an animation run at the display's rate?</b> Frames across one 600 ms pulse, three times. At
 *       60 Hz that is about thirty-six.</li>
 *   <li><b>How long from a wake to the next frame, from a parked loop?</b> Fifteen trials of a component writing a
 *       node, stopped by the frame hook. The parked loop is asleep on a timed wait, so this is what the wake buys.</li>
 *   <li><b>Does anything throttle the loop under more wakes than it can answer?</b> Three seconds of writes with
 *       no pause: the frame rate it plateaus at is the presenter's, if the presenter blocks, and the writes'
 *       failures say whether a full message queue throws on the writing thread.</li>
 * </ol>
 * The sleeps and the trial count are fixed, so two runs are comparable and the numbers are not tuned to the
 * machine.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PacingMeasurementTest {

    private static final String ARTIFACT = "vexel-witness-pacing";
    private static final List<String> OVERLAY = List.of("Landmarks", "Meter", "Components", "Ui", "Recipes");

    private static Path root;
    private static Project project;
    private static Session live;

    @BeforeAll
    static void clearTheLastRun() throws IOException {
        root = fresh("pacing");
    }

    @AfterAll
    static void stopTheApplication() throws IOException {
        if (live != null) {
            live.close();
        }
    }

    @Test
    @Order(1)
    void theMeasurementApplicationBuilds() throws Exception {
        project = generate(root, ARTIFACT, "/witnesses/pacing", OVERLAY);
        build(project, root.resolve("build.log"));
    }

    @Test
    @Order(2)
    void theLoopIsMeasuredAnimatingWakingFromParkAndUnderAStorm() throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "run")) {
            Driver d = session.driver();
            d.ok("settle");

            for (int i = 1; i <= 3; i++) {
                d.ok("click " + d.ref("button.pulse"));
                d.ok("await pulse pulse #" + i + " ");
                System.out.println("PACING " + landmarked(d, "pulse"));
                Thread.sleep(800);
            }

            d.ok("click " + d.ref("button.probe"));
            d.ok("await latency latency n=");
            System.out.println("PACING " + landmarked(d, "latency"));

            d.ok("click " + d.ref("button.storm"));
            d.ok("await storm storm frames");
            System.out.println("PACING " + landmarked(d, "storm"));

            // Still a window after the storm.
            d.ok("settle");
            session.out().println("quit");
        }
        live = null;
    }

    private static String landmarked(Driver d, String landmark) throws IOException {
        return d.ok("find " + landmark).lines().filter(l -> l.contains(" @" + landmark)).findFirst()
                .orElse("(no node carries " + landmark + ")");
    }
}

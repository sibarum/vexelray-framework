package dev.vexelray.framework.acceptance;

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

import static dev.vexelray.framework.acceptance.Support.build;
import static dev.vexelray.framework.acceptance.Support.fresh;
import static dev.vexelray.framework.acceptance.Support.generate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3, the long-running witness: an application with nothing to settle, watched for whether its frame loop wakes
 * when there is work, parks when there is none, and stays the same size while it runs.
 *
 * <p><b>Why this shape.</b> The counter and W2 are both event-driven: they do something, then go quiet. The
 * pacing and wake machinery is only exercised for real by something that keeps producing work. A component here
 * counts a timer's beat a hundred times a second on a lane of its own and the view rewrites a readout each time,
 * so the window has to be woken by the logic continuously, and then has to stop being woken when it stops.
 *
 * <p><b>Measured from the outside.</b> No application code runs in the frame in 0.2, so the frames are counted by
 * the flight recorder from a mark, as the idle loop always was, and the process is weighed by a job on request.
 *
 * <p>The thresholds are wide on purpose. They separate <i>parked</i> (about five frames a second, the idle
 * refresh) from <i>running</i> (up to sixty) by an order of magnitude, and they are here to catch a loop that
 * never parks or never wakes, not to benchmark. Each run prints its numbers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LongRunWitnessTest {

    private static final String ARTIFACT = "vexel-witness-longrun";
    private static final List<String> OVERLAY = List.of("Landmarks", "Components", "Ui", "Shown", "Recipes");

    private static Path root;
    private static Project project;
    private static Session live;

    @BeforeAll
    static void clearTheLastRun() throws IOException {
        root = fresh("longrun");
    }

    @AfterAll
    static void stopTheApplication() throws IOException {
        if (live != null) {
            live.close();
        }
        Support.assertNoUnaskedScroll(root);
    }

    @Test
    @Order(1)
    void theBuilderGeneratesAProjectWithAMetronomeOnItsOwnLaneAndNoFrameHook() throws Exception {
        project = generate(root, ARTIFACT, "/witnesses/longrun", OVERLAY);
        build(project, root.resolve("build.log"));
        String generated = Files.readString(project.wiring());
        String p = project.packageName() + ".";
        assertTrue(generated.contains("\"part component " + p + "Metronome lane=metronome"),
                "the metronome is not on its lane\n" + generated);
        assertFalse(generated.contains("shell.hooks().add("), "an application's code is in the frame\n" + generated);
    }
}

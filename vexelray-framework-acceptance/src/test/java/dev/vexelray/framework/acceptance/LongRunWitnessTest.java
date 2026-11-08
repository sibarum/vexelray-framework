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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.vexelray.framework.acceptance.Support.build;
import static dev.vexelray.framework.acceptance.Support.fresh;
import static dev.vexelray.framework.acceptance.Support.generate;
import static dev.vexelray.framework.acceptance.Support.launch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    private static final Pattern SAMPLE = Pattern.compile("#(\\d+) threads (\\d+) heap (\\d+)");

    private static Path root;
    private static Project project;
    private static Session live;

    /** One weighing of the process: what the sample line says. */
    private record Sample(int number, int threads, long heapMb) {
    }

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

    @Test
    @Order(2)
    void aComponentThatNeverIdlesWakesTheLoopAndAParkedLoopStaysParkedAfterItStops() throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "run")) {
            Driver d = session.driver();
            d.firstFrame("button.sample");
            int next = 1;

            // Parked: with nothing running, the loop refreshes on its idle timer and no faster. Counted from a mark
            // after a settle, because a click is followed by a press fading and a readout sliding, a few dozen
            // frames that belong to the click and not to the idle loop.
            d.ok("settle");
            long idleFrames = framesOver(d, "idle-window", 3_000);
            System.out.println("W3 idle: " + idleFrames + " frames in 3 s");
            assertTrue(idleFrames <= 45, "an idle window ran " + idleFrames + " frames in three seconds, so the "
                    + "loop is not parking");

            // Running: the logic wakes the loop, a hundred times a second, with nothing else asking.
            d.ok("click " + d.ref("button.start"));
            d.ok("await ticks ticks 50");
            long runFrames = framesOver(d, "running-window", 5_000);
            System.out.println("W3 running: " + runFrames + " frames in 5 s");
            assertTrue(runFrames >= 60, "a component counting a hundred beats a second produced " + runFrames
                    + " frames in five seconds, so its wakes are not reaching the loop");

            // Recorded, not asserted: settle cannot tell "quiet" from "between two beats", and a timer's beat is not
            // an answer owed, so it answers in a gap. The recorder counts passes, and since fork 7 a beat's wake runs
            // one at once whether or not it may draw, so this is the beat rate; draws stay at the 60 Hz ceiling.
            System.out.println("W3 settle while running: " + d.send("settle"));
            System.out.println("W3 passes: " + runFrames / 5.0 + " a second, draws held to the 60 Hz ceiling");

            // And the window is still a window: input is answered while the loop is flat out.
            d.ok("click " + d.ref("button.count"));
            d.ok("await count 1");

            // A long run, the same size at the end as near the start: no thread per beat, no heap that grows.
            Sample early = sample(d, next++);
            Thread.sleep(15_000);
            Sample late = sample(d, next++);
            System.out.println("W3 sizes: " + early + " -> " + late);
            assertTrue(Math.abs(late.threads() - early.threads()) <= 2, "the thread count went from "
                    + early.threads() + " to " + late.threads() + " over a run with a steady load");
            assertTrue(late.heapMb() - early.heapMb() <= 64, "the heap in use grew from " + early.heapMb()
                    + " MB to " + late.heapMb() + " MB over a run with a steady load");

            // Stopped: the loop goes back to parking, so nothing is still waking it.
            d.ok("click " + d.ref("button.stop"));
            Thread.sleep(1_500);
            String frozen = landmarked(d, "ticks");
            long stoppedFrames = framesOver(d, "stopped-window", 3_000);
            System.out.println("W3 stopped: " + stoppedFrames + " frames in 3 s");
            assertTrue(stoppedFrames <= 45, "after the metronome stopped the loop still ran " + stoppedFrames
                    + " frames in three seconds, so something is still waking it");
            assertEquals(frozen, landmarked(d, "ticks"), "the ticks kept counting after the metronome was stopped");

            d.ok("settle");
            Path shot = root.resolve("longrun.png");
            d.ok("shot " + shot);
            assertTrue(Files.size(shot) > 0, "the photograph is empty");
            session.out().println("quit");
        }
        live = null;
    }

    /** Frames the flight recorder saw in {@code millis} from a mark called {@code mark}. */
    private static long framesOver(Driver d, String mark, long millis) throws Exception {
        d.ok("mark " + mark);
        Thread.sleep(millis);
        String reply = d.ok("frames since " + mark);
        return field(reply, "frames") + field(reply, "more");
    }

    /**
     * The line for the one node carrying {@code landmark}. {@code find} lists every node whose name contains the
     * word, ancestors included, and the card's name contains everything written inside it.
     */
    private static String landmarked(Driver d, String landmark) throws IOException {
        return d.ok("find " + landmark).lines().filter(l -> l.contains(" @" + landmark)).findFirst()
                .orElseGet(() -> fail("no node carries the landmark " + landmark));
    }

    /** The number after {@code name=} in a recorder reply such as {@code ok frames=3 order=since … more=0}. */
    private static long field(String reply, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=(\\d+)").matcher(reply);
        if (!m.find()) {
            return fail("no " + name + "= in the recorder's reply: " + reply);
        }
        return Long.parseLong(m.group(1));
    }

    /** Presses Sample, waits for that weighing, and reads it. */
    private static Sample sample(Driver d, int number) throws IOException {
        d.ok("click " + d.ref("button.sample"));
        d.ok("await sample sample #" + number + " ");
        Matcher m = SAMPLE.matcher(d.ok("find sample"));
        if (!m.find()) {
            return fail("the sample line did not parse");
        }
        return new Sample(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Long.parseLong(m.group(3)));
    }
}

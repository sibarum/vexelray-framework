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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * W3, the long-running witness: an application with nothing to settle, watched for whether its frame loop wakes
 * when there is work, parks when there is none, and stays the same size while it runs.
 *
 * <p><b>Why this shape.</b> The counter and W2 are both event-driven: they do something, then go quiet. The
 * pacing and wake machinery — parking the loop, and the two {@code onWork} calls whose omission is <i>a parked
 * loop rather than an exception</i> — is only exercised for real by something that keeps producing work. A
 * component here ticks a hundred times a second to itself and rewrites a readout each time, so the window has to
 * be woken by a component's thread continuously, and then has to stop being woken when the component stops.
 *
 * <p>Measured through samples taken by a button rather than through anything drawn each frame: a readout that
 * changed every frame would owe the next frame itself, and a loop that wakes itself cannot be measured for
 * whether it parks. So a sample copies the frame count, the thread count and the heap into one line, on request,
 * and the assertions are about the differences between two.
 *
 * <p>The thresholds are wide on purpose. They separate <i>parked</i> (about five frames a second, the idle
 * refresh) from <i>running</i> (up to sixty) by an order of magnitude, and they are here to catch a loop that
 * never parks or never wakes, not to benchmark. Each run prints its numbers, and the findings are written up in
 * {@code docs/architecture.md}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LongRunWitnessTest {

    private static final String ARTIFACT = "vexel-witness-longrun";
    private static final List<String> OVERLAY = List.of("Landmarks", "Meter", "Components", "Ui", "Recipes");
    private static final Pattern SAMPLE = Pattern.compile("#(\\d+) frames (\\d+) threads (\\d+) heap (\\d+)");

    private static Path root;
    private static Project project;
    private static Session live;

    /** One reading of the process: what the sample line says. */
    private record Sample(int number, long frames, int threads, long heapMb) {
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
    }

    @Test
    @Order(1)
    void theBuilderGeneratesAProjectWithAFrameHookAndAMetronomeOnItsOwnLane() throws Exception {
        project = generate(root, ARTIFACT, "/witnesses/longrun", OVERLAY);
        build(project, root.resolve("build.log"));
        String generated = Files.readString(project.wiring());
        assertTrue(generated.contains("Placements.of(shell, \"metronome\")"), "no placement for the metronome\n" + generated);
        assertTrue(generated.contains("shell.hooks().add(dev.vexelray.framework.api.FrameStage.APP, meter::frame)"),
                "the frame hook on the meter was not added to the frame array\n" + generated);
    }

    @Test
    @Order(2)
    void aComponentThatNeverIdlesWakesTheLoopAndAParkedLoopStaysParkedAfterItStops() throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "run")) {
            Driver d = session.driver();
            d.ok("settle");
            int next = 1;

            // Parked: with nothing running, the loop refreshes on its idle timer and no faster.
            Sample idleFrom = sample(d, next++);
            Thread.sleep(3_000);
            Sample idleTo = sample(d, next++);
            long idleFrames = idleTo.frames() - idleFrom.frames();
            System.out.println("W3 idle: " + idleFrames + " frames in 3 s");
            assertTrue(idleFrames <= 45, "an idle window ran " + idleFrames + " frames in three seconds, so the "
                    + "loop is not parking\n" + idleFrom + "\n" + idleTo);

            // Running: a component's thread wakes the loop, a hundred times a second, with nothing else asking.
            d.ok("click " + d.ref("button.start"));
            d.ok("await ticks ticks 50");
            Sample runFrom = sample(d, next++);
            Thread.sleep(5_000);
            Sample runTo = sample(d, next++);
            long runFrames = runTo.frames() - runFrom.frames();
            System.out.println("W3 running: " + runFrames + " frames in 5 s");
            assertTrue(runFrames >= 60, "a component ticking a hundred times a second produced " + runFrames
                    + " frames in five seconds, so its wakes are not reaching the loop\n" + runFrom + "\n" + runTo);

            // Recorded, not asserted, because both are findings rather than claims. Settle is documented as blind
            // to application work still in flight, and a metronome is exactly that: it answers ok in a gap
            // between ticks, so a script cannot tell "quiet" from "between two ticks". And the frame ceiling
            // (maxFrameRate, 60 Hz) is a timed park that a posted wake ends early, so wake-driven frames run at
            // the wake rate. See docs/architecture.md, what the long-running witness found.
            System.out.println("W3 settle while running: " + d.send("settle"));
            System.out.println("W3 ceiling: " + runFrames / 5.0 + " frames a second against a 60 Hz ceiling");

            // And the window is still a window: input is answered while the loop is flat out.
            d.ok("click " + d.ref("button.count"));
            d.ok("await count 1");

            // A long run, the same size at the end as near the start: no thread per tick, no heap that grows.
            Thread.sleep(15_000);
            Sample late = sample(d, next++);
            System.out.println("W3 sizes: " + runTo + " -> " + late);
            assertTrue(Math.abs(late.threads() - runTo.threads()) <= 2, "the thread count went from "
                    + runTo.threads() + " to " + late.threads() + " over a run with a steady load");
            assertTrue(late.heapMb() - runTo.heapMb() <= 64, "the heap in use grew from " + runTo.heapMb()
                    + " MB to " + late.heapMb() + " MB over a run with a steady load");

            // Stopped: the loop goes back to parking, so nothing is still waking it.
            d.ok("click " + d.ref("button.stop"));
            Thread.sleep(1_500);
            String frozen = landmarked(d, "ticks");
            Sample stopFrom = sample(d, next++);
            Thread.sleep(3_000);
            Sample stopTo = sample(d, next++);
            long stoppedFrames = stopTo.frames() - stopFrom.frames();
            System.out.println("W3 stopped: " + stoppedFrames + " frames in 3 s");
            assertTrue(stoppedFrames <= 45, "after the metronome stopped the loop still ran " + stoppedFrames
                    + " frames in three seconds, so something is still waking it\n" + stopFrom + "\n" + stopTo);
            assertEquals(frozen, landmarked(d, "ticks"), "the ticks kept counting after the metronome was stopped");

            // Quiet again, so there is something to settle.
            d.ok("settle");

            Path shot = root.resolve("longrun.png");
            d.ok("shot " + shot);
            assertTrue(Files.size(shot) > 0, "the photograph is empty");
            session.out().println("quit");
        }
        live = null;
    }

    /**
     * The line for the one node carrying {@code landmark}. {@code find} lists every node whose name contains the
     * word, ancestors included, and the card's name contains everything written inside it.
     */
    private static String landmarked(Driver d, String landmark) throws IOException {
        return d.ok("find " + landmark).lines().filter(l -> l.contains(" @" + landmark)).findFirst()
                .orElseGet(() -> fail("no node carries the landmark " + landmark));
    }

    /** Presses Sample, waits for that sample, and reads it. */
    private static Sample sample(Driver d, int number) throws IOException {
        d.ok("click " + d.ref("button.sample"));
        d.ok("await sample sample #" + number + " ");
        Matcher m = SAMPLE.matcher(d.ok("find sample"));
        if (!m.find()) {
            return fail("the sample line did not parse");
        }
        return new Sample(Integer.parseInt(m.group(1)), Long.parseLong(m.group(2)), Integer.parseInt(m.group(3)),
                Long.parseLong(m.group(4)));
    }
}

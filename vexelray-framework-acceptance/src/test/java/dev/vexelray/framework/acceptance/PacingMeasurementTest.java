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

            // The click path: twenty taps, each after the loop has had time to park, at a random phase of its
            // idle refresh. Automation publishes on the bus, which is the same door Tactroller's snapshot feeds.
            String tap = d.ref("button.tap");
            for (int i = 1; i <= 20; i++) {
                d.ok("click " + tap);
                d.ok("await tap tap n=" + i + " ");
                Thread.sleep(230 + (i * 7) % 60);
            }
            System.out.println("PACING " + landmarked(d, "tap"));

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

    /**
     * The same pulse with {@code atchung-probe}'s correlation log on, and every row that falls inside a late gap
     * printed, so what the loop was doing while it did not draw is on the page. The probe costs something, so the
     * gaps here are a picture of where time goes and not a second measurement of how long they are.
     */
    @Test
    @Order(3)
    void theLateFramesAtTheStartOfAnAnimationAreLookedAtThroughTheProbe() throws Exception {
        probed("off");
    }

    /**
     * The same run with the process asking Windows for a 1 ms timer at startup. If the late frames that follow a
     * 16 ms timed wait are the timer rounding it up, they go; if they stay, the timer was never the cause.
     */
    @Test
    @Order(4)
    void theSameRunWithAOneMillisecondTimer() throws Exception {
        probed("1ms");
    }

    /**
     * As above, and also opting out of Windows 11's timer resolution throttling, which ignores the request from
     * a process whose window is not visible and in front. A test window launched by a build usually is not.
     */
    @Test
    @Order(5)
    void theSameRunWithTheTimerRequestHonoured() throws Exception {
        probed("optout");
    }

    private void probed(String timer) throws Exception {
        assertTrue(project != null, "nothing was built");
        Path csv = root.resolve("probe-" + timer + ".csv");
        try (Session session = live = launch(project, root, ARTIFACT, "probe-" + timer,
                "-Dprobe=all", "-Dprobe.format=csv", "-Dprobe.out=" + csv, "-Dpacing.timer=" + timer,
                // Relative, because the option's own syntax uses a colon and so does a drive letter. The JVM's
                // working directory is the project's, so the log lands beside the tree.
                "-Xlog:gc,safepoint:file=gc-" + timer + ".log:uptime,tags")) {
            Driver d = session.driver();
            d.ok("settle");
            for (int i = 1; i <= 3; i++) {
                Thread.sleep(1_500);
                d.ok("click " + d.ref("button.pulse"));
                d.ok("await pulse pulse #" + i + " ");
            }
            session.out().println("quit");
        }
        live = null;
        Thread.sleep(500);

        // Every collection and every safepoint that held the JVM for 3 ms or more. A thread coming back from a
        // native wait cannot run Java until a safepoint in progress ends, so one of these inside a late gap is
        // the whole explanation for it.
        Path gc = project.dir().resolve("gc-" + timer + ".log");
        if (Files.isRegularFile(gc)) {
            for (String line : Files.readAllLines(gc, java.nio.charset.StandardCharsets.UTF_8)) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\[(\\d+\\.\\d+)s\\].*?(Pause[^\\n]*?(\\d+\\.\\d+)ms|Total: (\\d+) ns)").matcher(line);
                if (m.find()) {
                    double ms = m.group(3) != null ? Double.parseDouble(m.group(3)) : Long.parseLong(m.group(4)) / 1e6;
                    if (ms >= 3.0) {
                        System.out.println("PROBE jvm-pause " + ms + "ms at uptime " + m.group(1) + "s :: "
                                + line.replaceAll("\\s+", " "));
                    }
                }
            }
        } else {
            System.out.println("PROBE no gc.log at " + gc);
        }

        List<String[]> rows = new java.util.ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(csv, java.nio.charset.StandardCharsets.UTF_8)) {
            String[] f = line.split(",", 7);
            if (f.length == 7 && f[1].matches("\\d+")) {
                rows.add(f);
            }
        }
        rows.sort(java.util.Comparator.comparingLong(r -> Long.parseLong(r[1])));
        System.out.println("PROBE timer=" + timer + " rows " + rows.size());

        int pulse = 0;
        long windowStart = -1;
        for (int i = 0; i < rows.size(); i++) {
            String kind = rows.get(i)[5];
            if (kind.equals("pulse.click")) {
                windowStart = Long.parseLong(rows.get(i)[1]);
                pulse++;
                System.out.println("PROBE ---- timer=" + timer + " pulse " + pulse);
            } else if (kind.equals("pulse.done")) {
                windowStart = -1;
            }
            if (windowStart < 0 || !kind.equals("frame.present")) {
                continue;
            }
            // The previous frame.present in this window, and the gap to it.
            int previous = -1;
            for (int j = i - 1; j >= 0; j--) {
                if (rows.get(j)[5].equals("frame.present")) {
                    previous = j;
                    break;
                }
            }
            if (previous < 0) {
                continue;
            }
            long gapMicros = (Long.parseLong(rows.get(i)[1]) - Long.parseLong(rows.get(previous)[1])) / 1_000;
            if (gapMicros > 12_000 && Long.parseLong(rows.get(previous)[1]) >= windowStart) {
                System.out.println("PROBE timer=" + timer + " gap " + gapMicros + "us before " + rows.get(i)[6]
                        + ", " + (Long.parseLong(rows.get(previous)[1]) - windowStart) / 1_000_000L
                        + "ms into the pulse");
                for (int k = previous; k <= i; k++) {
                    String[] r = rows.get(k);
                    System.out.println("PROBE   +" + (Long.parseLong(r[1]) - Long.parseLong(rows.get(previous)[1])) / 1_000
                            + "us " + r[3] + " " + r[4] + " " + r[5] + " " + r[6]);
                }
            }
        }
    }

    private static String landmarked(Driver d, String landmark) throws IOException {
        return d.ok("find " + landmark).lines().filter(l -> l.contains(" @" + landmark)).findFirst()
                .orElse("(no node carries " + landmark + ")");
    }
}

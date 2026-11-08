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

import static dev.vexelray.framework.acceptance.Support.build;
import static dev.vexelray.framework.acceptance.Support.fresh;
import static dev.vexelray.framework.acceptance.Support.generate;
import static dev.vexelray.framework.acceptance.Support.launch;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Measurements of the frame loop's pacing, in the one place the loop can be watched running for real: a generated
 * application on a real window. It asserts almost nothing on purpose. What it produces is numbers, printed with a
 * {@code PACING} prefix, and what they mean is written up in {@code docs/architecture.md} and the ROADMAP's phase 7.
 *
 * <p><b>In 0.2's shape.</b> No application code runs in the frame, so nothing here is a frame hook: a time is a
 * value stamped into a message and read again by the view in the frame that shows it, and a frame count is the
 * flight recorder's, taken by the driver from a mark.
 *
 * <ol>
 *   <li><b>Does an animation run at the display's rate?</b> A 600 ms tween, three times: its steps, one a frame.</li>
 *   <li><b>How long does a click's result take through the logic?</b> Twenty taps, each stamped in the frame that
 *       took it, answered on the logic lane and timed in the frame that shows the answer. Fork 4's cost.</li>
 *   <li><b>How long from a wake to a frame, from a parked loop?</b> Fifteen timer beats stamped on the logic lane.</li>
 *   <li><b>What does the loop cost when nothing is owed?</b> Frames and process CPU over a stretch of nothing.</li>
 *   <li><b>What does the loop do when the logic never rests?</b> Three seconds of a component sending itself the next
 *       delivery, and the frames the loop ran meanwhile.</li>
 * </ol>
 *
 * Taken in both deployment modes: the logic on a thread of its own, and on the main thread ({@code --logic=main}),
 * which is meant for the slow machines where a frame of latency costs most.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PacingMeasurementTest {

    private static final String ARTIFACT = "vexel-witness-pacing";
    private static final List<String> OVERLAY = List.of("Landmarks", "Components", "Ui", "Shown", "Recipes");

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
        Support.assertNoUnaskedScroll(root);
    }

    @Test
    @Order(1)
    void theMeasurementApplicationBuilds() throws Exception {
        project = generate(root, ARTIFACT, "/witnesses/pacing", OVERLAY);
        build(project, root.resolve("build.log"));
    }

    @Test
    @Order(2)
    void theLoopIsMeasuredWithTheLogicOnALaneOfItsOwn() throws Exception {
        measure("lane");
    }

    @Test
    @Order(3)
    void theLoopIsMeasuredWithTheLogicOnTheMainThread() throws Exception {
        measure("main", "-Dlogic=main");
    }

    /**
     * About fifteen frames a second, where {@code SlowFrames} makes its offer and where fork 4's frame is felt most:
     * every frame drawn costs 62 ms more ({@code -Dvexelray.debug.slowFrames}). Taps and wakes only, and inside the
     * offer's ten-second warm-up, so its question never covers what is measured. A stand-in for a slow machine until
     * vexelray-mini is ported (phase 8).
     */
    @Test
    @Order(5)
    void theLoopIsMeasuredAtFifteenFramesASecondInBothModes() throws Exception {
        assertTrue(project != null, "nothing was built");
        for (String mode : List.of("lane", "main")) {
            String[] args = mode.equals("main")
                    ? new String[] {"-Dvexelray.debug.slowFrames=62", "-Dlogic=main"}
                    : new String[] {"-Dvexelray.debug.slowFrames=62"};
            try (Session session = live = launch(project, root, ARTIFACT, "slow-" + mode, args)) {
                Driver d = session.driver();
                d.firstFrame("button.tap");
                String tag = "PACING slow-" + mode + " ";
                String tap = d.ref("button.tap");
                for (int i = 1; i <= 10; i++) {
                    d.ok("click " + tap);
                    d.ok("await tap tap n=" + i + " ");
                    Thread.sleep(230 + (i * 7) % 60);
                }
                System.out.println(tag + landmarked(d, "tap"));
                d.ok("click " + d.ref("button.probe"));
                d.ok("await latency latency n=");
                System.out.println(tag + landmarked(d, "latency"));
                session.out().println("quit");
            }
            live = null;
        }
    }

    private void measure(String mode, String... jvmArgs) throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "run-" + mode, jvmArgs)) {
            Driver d = session.driver();
            d.firstFrame("button.pulse");
            String tag = "PACING " + mode + " ";

            for (int i = 1; i <= 3; i++) {
                d.ok("click " + d.ref("button.pulse"));
                d.ok("await pulse pulse #" + i + " ");
                System.out.println(tag + landmarked(d, "pulse"));
                Thread.sleep(800);
            }

            // Twenty taps, each after the loop has had time to park, at a varying phase of its idle refresh.
            String tap = d.ref("button.tap");
            for (int i = 1; i <= 20; i++) {
                d.ok("click " + tap);
                d.ok("await tap tap n=" + i + " ");
                Thread.sleep(230 + (i * 7) % 60);
            }
            System.out.println(tag + landmarked(d, "tap"));

            d.ok("click " + d.ref("button.probe"));
            d.ok("await latency latency n=");
            System.out.println(tag + landmarked(d, "latency"));

            // Nothing owed: the click that asks, then its own tail let go, then four seconds counted from a mark.
            // Twice, so a first run's class loading shows as the difference.
            for (int i = 1; i <= 2; i++) {
                d.ok("click " + d.ref("button.idle"));
                Thread.sleep(600);
                d.ok("mark idle-" + i);
                d.ok("await idle idle #" + i + " ");
                String frames = d.ok("frames since idle-" + i);
                System.out.println(tag + landmarked(d, "idle") + " frames " + (field(frames, "frames")
                        + field(frames, "more")) + " over " + span(frames));
            }

            d.ok("mark storm");
            d.ok("click " + d.ref("button.storm"));
            d.ok("await storm storm deliveries");
            String frames = d.ok("frames since storm");
            System.out.println(tag + landmarked(d, "storm") + " frames " + (field(frames, "frames")
                    + field(frames, "more")) + " over " + span(frames));

            // Still a window after the storm.
            d.ok("settle");
            Path shot = root.resolve("pacing-" + mode + ".png");
            d.ok("shot " + shot);
            session.out().println("quit");
        }
        live = null;
    }

    /** The number after {@code name=} in a recorder reply such as {@code ok frames=3 order=since … more=0}. */
    private static long field(String reply, String name) {
        Matcher m = java.util.regex.Pattern.compile("\\b" + name + "=(\\d+)").matcher(reply);
        if (!m.find()) {
            return fail("no " + name + "= in the recorder's reply: " + reply);
        }
        return Long.parseLong(m.group(1));
    }

    /** The {@code over=…} span of a recorder reply, as it says it. */
    private static String span(String reply) {
        Matcher m = java.util.regex.Pattern.compile("over=(\\S+)").matcher(reply);
        return m.find() ? m.group(1) : "?";
    }

    /**
     * The same pulse with {@code atchung-probe}'s correlation log on, and every row that falls inside a late gap
     * printed, so what the loop was doing while it did not draw is on the page. The probe costs something, so the
     * gaps here are a picture of where time goes and not a second measurement of how long they are.
     */
    @Test
    @Order(4)
    void theLateFramesAtTheStartOfAnAnimationAreLookedAtThroughTheProbe() throws Exception {
        probed("off");
    }

    /**
     * The regression the window's timer request exists to prevent. Measured before it was built, a 16 ms park at the
     * start of every pulse returned after 28 ms and the frame gap in the first 100 ms of the pulse was 28 to 31 ms.
     * With it, the same gap is about 18 ms, and the largest gap seen anywhere in that stretch was 21.6 ms (a
     * separate stall, in the GPU fence, that is not fixed). The ceiling sits between the two.
     */
    private static final long EARLY_GAP_CEILING_MICROS = 26_000;

    private void probed(String timer) throws Exception {
        assertTrue(project != null, "nothing was built");
        Path csv = root.resolve("probe-" + timer + ".csv");
        try (Session session = live = launch(project, root, ARTIFACT, "probe-" + timer,
                "-Dprobe=all", "-Dprobe.format=csv", "-Dprobe.out=" + csv,
                // Relative, because the option's own syntax uses a colon and so does a drive letter. The JVM's
                // working directory is the project's, so the log lands beside the tree.
                "-Xlog:gc,safepoint:file=gc-" + timer + ".log:uptime,tags")) {
            Driver d = session.driver();
            d.firstFrame("button.pulse");
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
        Path gc = project.gui().resolve("gc-" + timer + ".log");
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
        long earliestWorst = 0;
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
            if (Long.parseLong(rows.get(previous)[1]) >= windowStart
                    && Long.parseLong(rows.get(i)[1]) - windowStart < 100_000_000L) {
                earliestWorst = Math.max(earliestWorst, gapMicros);
            }
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
        System.out.println("PROBE timer=" + timer + " worst gap in the first 100 ms of a pulse: " + earliestWorst + "us");
        assertTrue(earliestWorst <= EARLY_GAP_CEILING_MICROS, "a frame gap of " + earliestWorst
                + "us in the first 100 ms of a pulse: a timed park is returning late again. Is the window's timer "
                + "request (TimerResolution in vexelray-os-windows) still being made?");
    }

    private static String landmarked(Driver d, String landmark) throws IOException {
        return d.ok("find " + landmark).lines().filter(l -> l.contains(" @" + landmark)).findFirst()
                .orElse("(no node carries " + landmark + ")");
    }
}

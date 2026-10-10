package dev.vexelray.framework.acceptance;

import dev.vexelray.framework.template.Answers;
import dev.vexelray.framework.template.Blueprint;
import dev.vexelray.framework.template.Catalogue;
import dev.vexelray.framework.template.Checks;
import dev.vexelray.framework.template.Scaffold;
import dev.vexelray.framework.template.Template;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.vexelray.framework.acceptance.Support.Driver;

import static dev.vexelray.framework.acceptance.Support.BUILD_MINUTES;
import static dev.vexelray.framework.acceptance.Support.connect;
import static dev.vexelray.framework.acceptance.Support.freePort;
import static dev.vexelray.framework.acceptance.Support.fresh;
import static dev.vexelray.framework.acceptance.Support.maven;
import static dev.vexelray.framework.acceptance.Support.property;
import static dev.vexelray.framework.acceptance.Support.stop;
import static dev.vexelray.framework.acceptance.Support.tail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The acceptance loop: the builder's output, built and driven.
 *
 * <p><b>Why the last step is the one that matters.</b> The builder lives in this repo and this repo is verified
 * by what the builder emits, so a mistake present in both the template and the framework is invisible to either:
 * the tree compiles, the shapes agree, and what they agree on is wrong. <i>Did it run</i> is the one question
 * neither side can answer by agreeing with itself, and the automation socket is how it is asked
 * ({@code docs/architecture.md}, <i>the witness is the project builder, not an application</i>).
 *
 * <p>Four steps, in order, each failing with what the next one would have needed:
 *
 * <ol>
 *   <li><b>Plan</b> — {@code Scaffold.of} to a {@code Blueprint}, with every check the builder runs before it
 *       writes anything, including that the stack it builds against is installed. No filesystem.</li>
 *   <li><b>Write</b> — the tree, through {@code Blueprint.Writing}, into {@code target/acceptance}.</li>
 *   <li><b>Build</b> — {@code mvn package} in the generated project, by the Maven running this build and against
 *       the same local repository, so what it compiles against is what the reactor just installed. The
 *       generated project's own tests run here, and so does the framework's annotation processor.</li>
 *   <li><b>Drive</b> — the command the template tells its user to run, {@code mvn compile exec:exec}, with the
 *       automation socket on; then count, count, reset by key, and photograph the window.</li>
 * </ol>
 *
 * <p>The application keeps its settings under a directory of its own inside {@code target}, through
 * {@code AppHome}'s {@code -D<app>.home} override, so a run leaves nothing in the user's home.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GeneratedProjectTest {

    private static final String ARTIFACT = "vexel-acceptance";
    private static final String TEMPLATE = "vexel-desktop";

    private static Path root;
    private static Template template;
    private static Answers answers;
    private static Blueprint blueprint;
    private static Path project;
    private static boolean built;
    private static Process app;

    @BeforeAll
    static void clearTheLastRun() throws IOException {
        root = fresh("generated");
    }

    @AfterAll
    static void stopTheApplication() {
        if (app != null) {
            stop(app);
        }
    }

    // --- 1. plan ---------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void theBuilderPlansAProjectAndEveryCheckPasses() {
        template = Catalogue.bundled().get(TEMPLATE);
        Map<String, String> given = new LinkedHashMap<>(Answers.presets(template).values());
        given.put("where", root.toString());
        given.put("artifactId", ARTIFACT);
        given.put("groupId", "dev.vexelray.acceptance");
        answers = Answers.of(given).filled(template);

        assertEquals(List.of(), Checks.problems(template, answers), "the answers themselves");
        assertEquals(List.of(), Checks.missingArtifacts(template, answers,
                Path.of(property("acceptance.repository"))),
                "the stack the generated project builds against has to be installed; run the siblings' "
                        + "mvn install first (CLAUDE.md, Building)");

        blueprint = Scaffold.of(template, answers, Catalogue.bundled());
        assertEquals(List.of(), Checks.malformedXml(blueprint));
        String main = "src/main/java/dev/vexelray/acceptance/vexelacceptance/";
        for (String path : List.of("pom.xml", main + "VexelAcceptance.java", main + "Recipes.java",
                main + "Ui.java", main + "Model.java")) {
            assertNotNull(blueprint.entry(path), "expected " + path + " in " + blueprint.paths());
        }
    }

    // --- 2. write --------------------------------------------------------------------------------------------

    @Test
    @Order(2)
    void theTreeIsWritten() throws IOException {
        assertNotNull(blueprint, "nothing was planned");
        project = Scaffold.folder(template, answers, root);
        assertEquals(List.of(), Checks.collisions(project, blueprint));
        Blueprint.Writing writing = new Blueprint.Writing(project);
        writing.begin();
        try {
            for (Blueprint.Entry entry : blueprint.entries()) {
                writing.write(entry);
            }
        } catch (IOException | RuntimeException e) {
            writing.undo();
            throw e;
        }
        assertEquals(blueprint.size(), writing.written().size());
    }

    // --- 3. build --------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    void theGeneratedProjectBuildsAndItsOwnTestsPass() throws Exception {
        assertNotNull(project, "nothing was written");
        Path log = root.resolve("build.log");
        Process build = maven(project, log, "package").start();
        if (!build.waitFor(BUILD_MINUTES, TimeUnit.MINUTES)) {
            stop(build);
            fail("the generated project's build took longer than " + BUILD_MINUTES + " minutes\n" + tail(log));
        }
        assertEquals(0, build.exitValue(), () -> "the generated project did not build\n" + tail(log));
        // The wiring the application runs on is the processor's, not a file the template wrote: the tree carries
        // no Wiring of its own, and this is where javac put the generated one.
        Path wiring = project.resolve(
                "target/generated-sources/annotations/dev/vexelray/acceptance/vexelacceptance/VexelAcceptanceAppWiring.java");
        assertTrue(Files.isRegularFile(wiring), "the processor did not generate the wiring at " + wiring);
        assertTrue(blueprint.paths().stream().noneMatch(p -> p.endsWith("Wiring.java")),
                "the template should not ship a hand-written wiring beside the generated one");
        // The mark: on the class path where @VexelApp(icon) names it, and registered for native-image by the processor.
        assertTrue(Files.isRegularFile(project.resolve("target/classes/" + ARTIFACT + ".ico")),
                "the pom did not put the icon on the class path");
        String metadata = Files.readString(project.resolve("target/classes/META-INF/native-image/"
                + "dev.vexelray.framework.generated/dev.vexelray.acceptance.vexelacceptance.VexelAcceptanceAppWiring/"
                + "reachability-metadata.json"));
        assertTrue(metadata.contains("\"" + ARTIFACT + ".ico\""), metadata);
        built = true;
    }

    // --- 4. drive --------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    void theRunningEditorOpensEditsSavesAndPhotographs() throws Exception {
        assertTrue(built, "nothing was built");
        int port = freePort();
        Path log = root.resolve("run.log");
        Path home = root.resolve("home");
        // A folder of the editor's own to open, named on the command line, so the run does not depend on what the
        // working directory happens to hold. The home is moved too, so no session is restored from a previous run.
        Path work = Files.createDirectories(root.resolve("work"));
        Files.writeString(work.resolve("notes.md"), "# Notes\n\nSome *emphasis* and `code`.\n");
        Files.writeString(work.resolve("todo.txt"), "first\n");
        app = maven(project, log, "compile", "exec:exec",
                "-Dautomation=" + port,
                "-Dapp.jvmArgs=-D" + ARTIFACT + ".home=" + home,
                "-Dapp.args=" + work).start();

        try (Socket socket = connect(app, port, log);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            Driver driver = new Driver(out, in, log);

            driver.ok("settle");
            // The path bar's name is the folder it shows, so this waits for the navigator to have listed it.
            driver.ok("await path " + work.getFileName());

            // Selecting a row opens it; the status line's file slot is named for the tab in front.
            driver.ok("click " + refOf(driver, "treeitem", "notes.md"));
            driver.ok("await status.file notes.md");
            driver.ok("click " + refOf(driver, "treeitem", "todo.txt"));
            driver.ok("await status.file todo.txt");

            // Selecting a file that is already open fronts its tab: the tabs slide (160 ms) and the header is ringed
            // (a 240 ms cue). Neither publishes anything a frame loop would owe for once it has started, so settle
            // has to wait out the clock rather than answer as soon as the tab has redrawn — and a photograph taken
            // after a settle that did not would show the slide half-way. `key` answers at once, unlike `click`,
            // which travels the pointer first, so the time to settle's answer is nearly all motion. The keyboard
            // is in the tree, because opening from the tree leaves it there.
            driver.ok("settle");
            driver.ok("key UP");
            long keyed = System.nanoTime();
            driver.ok("settle");
            long settleMs = (System.nanoTime() - keyed) / 1_000_000L;
            driver.ok("await status.file notes.md");
            assertTrue(settleMs >= 150, "settle answered in " + settleMs + " ms with a 240 ms cue in flight, so it"
                    + " did not wait for the clock\n" + tail(log));

            // Edit todo.txt, see it go unsaved, and save it from its tab's menu — Ctrl+S is a chord, and the
            // driver's `key` presses one key with no modifiers.
            driver.ok("click " + refOf(driver, "treeitem", "todo.txt"));
            driver.ok("await status.file todo.txt");
            // The middle of the tab area is the page in front, which is the field being edited.
            driver.ok("click " + driver.ref("tabs"));
            driver.ok("key END");
            driver.ok("type added");
            driver.ok("await status.file • todo.txt");
            driver.ok("rightclick " + refOf(driver, "tab", "todo.txt"));
            driver.ok("click " + refOf(driver, "menuitem", "Save"));
            driver.ok("await status.message Saved todo.txt");
            driver.ok("await status.file todo.txt");
            String saved = Files.readString(work.resolve("todo.txt"));
            assertTrue(saved.contains("added"), "the save did not reach the disk: " + saved);

            Path shot = root.resolve("shot.png");
            driver.ok("shot " + shot);
            assertTrue(Files.size(shot) > 0, "the photograph is empty");

            // What a photograph is OF is ottermate's to choose: the window, its size, its zoom and its density.
            // Against a real window, because a fake one cannot say whether setBounds lands on the drawable size
            // the picture is then taken at -- which is the whole claim of `resize`.
            String listing = driver.ok("windows");
            assertTrue(listing.startsWith("ok 1\n1 main "), "a one-window application lists one window: " + listing);
            assertTrue(driver.ok("size").contains("zoom=1 dpi=1"), "the view starts unscaled");

            driver.ok("resize 900x640");
            driver.ok("settle");
            assertTrue(driver.ok("size").startsWith("ok 900x640 "), "the window did not reach the size asked for");
            Path sized = root.resolve("sized.png");
            driver.ok("shot " + sized);
            assertEquals("900x640", pngSize(sized), "the photograph is not of the window that was sized");

            // Em from outside, resolved at the zoom in force: 40em at zoom 1.5 is 1.5x the pixels of 40em at 1.
            driver.ok("zoom 1.5");
            String emSized = driver.ok("resize 50em 30em");
            assertTrue(emSized.matches("ok \\d+x\\d+"), "the window would not go to the em size asked for: " + emSized);
            driver.ok("shot " + root.resolve("zoomed.png"));
            driver.ok("await status.file todo.txt");

            String clamped = driver.ok("zoom 999");
            assertTrue(clamped.contains("asked for 999"), "a zoom past the application's range must say so: " + clamped);
            driver.ok("zoom 1");
            driver.ok("dpi 2");
            assertTrue(driver.ok("size").contains("dpi=2"));
            driver.ok("dpi 1");

            // Being driven is what makes a run loud: the mode is AUTOMATION, so DEBUG on the console and TRACE in a
            // file, and the probe on beside it -- without anyone having asked for any of it. The file lives under
            // the application's home, which is where this test rig moved everything else.
            String logging = driver.ok("log");
            assertTrue(logging.contains("mode=AUTOMATION console=DEBUG file=TRACE"), logging);
            assertTrue(logging.contains("probe=on"), "an automation run turns the probe on: " + logging);
            Path logs = home.resolve("logs");
            Path logFile = logs.resolve(ARTIFACT + ".log");
            assertTrue(Files.isRegularFile(logFile), "no log file at " + logFile + "\n" + tail(log));
            String written = Files.readString(logFile);
            assertTrue(written.contains("starting " + ARTIFACT + ": mode=AUTOMATION"), "no startup banner:\n" + written);
            assertTrue(written.contains("automation socket listening on localhost:" + port), written);
            assertTrue(written.contains("phase ATTACH"), "TRACE and DEBUG reach the file in this mode:\n" + written);
            // The mark it names was found and decoded: a failure is reported, not thrown, so this is where it shows.
            assertFalse(written.contains("the application's icon") || written.contains("the framework's icon"),
                    "the window is not wearing the project's icon:\n" + written);
            assertTrue(Files.exists(logs.resolve(ARTIFACT + "-probe.csv")), "the probe's trace goes beside the log");
            // And it can be made louder, or quieter, in the middle of a run.
            assertTrue(driver.ok("log framework.shell info").contains("loggers={framework.shell=INFO}"));

            out.println("quit");
        } finally {
            stop(app);
            app = null;
        }
    }

    /**
     * The ref of the first node of {@code role} whose listing line mentions {@code text}, asked again until it
     * appears: a menu is built on a worker after the click that opened it, and a tree lists a folder off the
     * frame loop, so the first look can be early.
     */
    private static String refOf(Driver driver, String role, String text) throws IOException, InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            String listing = driver.ok(text.isEmpty() ? "find " + role : "find " + text);
            for (String line : listing.lines().skip(1).toList()) {
                String trimmed = line.strip();
                if (trimmed.matches("\\d+ " + role + "( .*)?") && trimmed.contains(text)) {
                    return trimmed.substring(0, trimmed.indexOf(' '));
                }
            }
            if (System.nanoTime() > until) {
                return fail("no " + role + " mentioning '" + text + "' in:\n" + listing);
            }
            driver.ok("settle");
        }
    }

    /** {@code width}x{@code height} of a PNG, read from its IHDR chunk. */
    private static String pngSize(Path png) throws IOException {
        byte[] head = new byte[24];
        try (java.io.InputStream in = Files.newInputStream(png)) {
            if (in.readNBytes(head, 0, 24) < 24) {
                throw new AssertionError(png + " is too short to be a PNG");
            }
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(head);
        return b.getInt(16) + "x" + b.getInt(20);
    }

}

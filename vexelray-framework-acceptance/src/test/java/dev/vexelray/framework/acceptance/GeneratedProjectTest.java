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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Generous, because the first build of a fresh project resolves every plugin it names. */
    private static final long BUILD_MINUTES = 10;
    private static final long LAUNCH_SECONDS = 180;

    private static Path root;
    private static Template template;
    private static Answers answers;
    private static Blueprint blueprint;
    private static Path project;
    private static boolean built;
    private static Process app;

    @BeforeAll
    static void clearTheLastRun() throws IOException {
        root = Path.of(property("acceptance.dir"));
        if (Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(root);
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
        Process build = maven(log, "package").start();
        if (!build.waitFor(BUILD_MINUTES, TimeUnit.MINUTES)) {
            stop(build);
            fail("the generated project's build took longer than " + BUILD_MINUTES + " minutes\n" + tail(log));
        }
        assertEquals(0, build.exitValue(), () -> "the generated project did not build\n" + tail(log));
        // The wiring the application runs on is the processor's, not a file the template wrote: the tree carries
        // no Wiring of its own, and this is where javac put the generated one.
        Path wiring = project.resolve(
                "target/generated-sources/annotations/dev/vexelray/acceptance/vexelacceptance/VexelAcceptanceWiring.java");
        assertTrue(Files.isRegularFile(wiring), "the processor did not generate the wiring at " + wiring);
        assertTrue(blueprint.paths().stream().noneMatch(p -> p.endsWith("Wiring.java")),
                "the template should not ship a hand-written wiring beside the generated one");
        built = true;
    }

    // --- 4. drive --------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    void theRunningApplicationCountsResetsAndPhotographs() throws Exception {
        assertTrue(built, "nothing was built");
        int port = freePort();
        Path log = root.resolve("run.log");
        Path home = root.resolve("home");
        app = maven(log, "compile", "exec:exec",
                "-Dautomation=" + port,
                "-Dapp.jvmArgs=-D" + ARTIFACT + ".home=" + home).start();

        try (Socket socket = connect(port, log);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            Driver driver = new Driver(out, in, log);

            driver.ok("settle");
            driver.ok("await count 0");

            String count = driver.ref("button.count");
            driver.ok("click " + count);
            driver.ok("await count 1");
            driver.ok("click " + count);
            driver.ok("await count 2");
            driver.ok("await note counted 2");

            // The reset shortcut is a GLOBAL claim, so it goes through the keyboard path rather than the button.
            driver.ok("key R");
            driver.ok("await count 0");

            driver.ok("click " + count);
            driver.ok("await count 1");
            driver.ok("click " + driver.ref("button.reset"));
            driver.ok("await count 0");

            // A press starts a 600 ms pulse on the count and publishes nothing else a frame loop would owe for,
            // so settle has to wait out the clock rather than answer as soon as the count has redrawn. It used
            // to answer within a frame or two, and a photograph taken then showed the pulse half-way through.
            //
            // Two measurements, because the pulse starts inside the click: the handler fires on the release, and
            // the click's reply arrives a couple of hundred milliseconds after that. So the whole pulse fits
            // between sending the click and settle's answer -- that is the claim -- and settle on its own still
            // took a real share of it, which is what tells this from a click that was merely slow.
            //
            // From rest: the press two commands up may still be pulsing, and a press during a pulse does not
            // restart it -- so without this, what is measured is the tail of that one.
            driver.ok("settle");
            long pressed = System.nanoTime();
            driver.ok("click " + count);
            long clicked = System.nanoTime();
            driver.ok("settle");
            long settled = System.nanoTime();
            long wholeMs = (settled - pressed) / 1_000_000L;
            long settleMs = (settled - clicked) / 1_000_000L;
            assertTrue(wholeMs >= 600, "the click and settle took " + wholeMs + " ms, less than the 600 ms pulse"
                    + " the click started, so settle answered before it finished\n" + tail(log));
            assertTrue(settleMs >= 150, "settle answered in " + settleMs + " ms with a pulse in flight, so it did"
                    + " not wait for the clock\n" + tail(log));

            Path shot = root.resolve("shot.png");
            driver.ok("shot " + shot);
            assertTrue(Files.size(shot) > 0, "the photograph is empty");

            out.println("quit");
        } finally {
            stop(app);
            app = null;
        }
    }

    /** One line protocol, one reply per command, terminated by a line holding only {@code .}. */
    private record Driver(PrintWriter out, BufferedReader in, Path log) {

        String send(String command) throws IOException {
            out.println(command);
            StringBuilder reply = new StringBuilder();
            for (String line = in.readLine(); line != null && !line.equals("."); line = in.readLine()) {
                reply.append(line).append('\n');
            }
            return reply.toString().strip();
        }

        String ok(String command) throws IOException {
            String reply = send(command);
            if (!reply.startsWith("ok")) {
                fail("'" + command + "' answered: " + reply + "\n" + tail(log));
            }
            return reply;
        }

        /** The ref of the one node carrying this landmark, from {@code find}'s listing. */
        String ref(String landmark) throws IOException {
            for (String line : ok("find " + landmark).lines().skip(1).toList()) {
                if (line.contains(" @" + landmark)) {
                    return line.substring(0, line.indexOf(' '));
                }
            }
            return fail("no node carries the landmark " + landmark);
        }
    }

    // --- processes -------------------------------------------------------------------------------------------

    /** The Maven running this build, in the generated project, against this build's local repository. */
    private static ProcessBuilder maven(Path log, String... goals) {
        String home = property("acceptance.maven");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = new ArrayList<>();
        command.add(Path.of(home, "bin", windows ? "mvn.cmd" : "mvn").toString());
        command.add("-B");
        command.add("-Dstyle.color=never");
        command.add("-Dmaven.repo.local=" + property("acceptance.repository"));
        command.addAll(List.of(goals));
        return new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
    }

    /** Keeps trying until the socket answers, and stops early with the log if the application has gone. */
    private static Socket connect(int port, Path log) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LAUNCH_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!app.isAlive()) {
                fail("the application exited with " + app.exitValue() + " before its socket opened\n" + tail(log));
            }
            try {
                return new Socket("127.0.0.1", port);
            } catch (IOException notYet) {
                Thread.sleep(250);
            }
        }
        return fail("no automation socket on " + port + " after " + LAUNCH_SECONDS + "s\n" + tail(log));
    }

    /** Maven forks the application, so the tree goes down, children first. */
    private static void stop(Process process) {
        process.descendants().sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** The last lines of a log, for a failure message somebody reads instead of opening the file. */
    private static String tail(Path log) {
        // Latin-1 because it decodes any byte: a child's console output is in the platform's encoding, and a
        // failure message that fails to decode is the one moment the log cannot be lost.
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
            return "--- last lines of " + log + " ---\n"
                    + String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
        } catch (IOException e) {
            return "(" + log + " could not be read: " + e.getMessage() + ")";
        }
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set; run this module through its pom, not an IDE");
        }
        return value;
    }
}

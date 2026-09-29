package dev.vexelray.framework.acceptance;

import dev.vexelray.framework.acceptance.Support.Driver;
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
import java.io.InputStream;
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

import static dev.vexelray.framework.acceptance.Support.BUILD_MINUTES;
import static dev.vexelray.framework.acceptance.Support.connect;
import static dev.vexelray.framework.acceptance.Support.freePort;
import static dev.vexelray.framework.acceptance.Support.fresh;
import static dev.vexelray.framework.acceptance.Support.maven;
import static dev.vexelray.framework.acceptance.Support.stop;
import static dev.vexelray.framework.acceptance.Support.tail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    private static final String TEMPLATE = "vexel-desktop";

    /** Replaced in the template-shaped overlay; these are what the builder's own files use. */
    private static final List<String> OVERLAY = List.of("Landmarks", "Bench", "Components", "Ui", "Recipes");

    private static Path root;
    private static Path project;
    private static boolean built;
    private static Process app;

    @BeforeAll
    static void clearTheLastRun() throws IOException {
        root = fresh("components");
    }

    @AfterAll
    static void stopTheApplication() {
        if (app != null) {
            stop(app);
        }
    }

    // --- generate, overlay, build ----------------------------------------------------------------------------

    @Test
    @Order(1)
    void theBuilderGeneratesAProjectTheOverlayTurnsIntoAComponentHeavyOne() throws Exception {
        Template template = Catalogue.bundled().get(TEMPLATE);
        Map<String, String> given = new LinkedHashMap<>(Answers.presets(template).values());
        given.put("where", root.toString());
        given.put("artifactId", ARTIFACT);
        given.put("groupId", "dev.vexelray.acceptance");
        Answers answers = Answers.of(given).filled(template);
        assertEquals(List.of(), Checks.problems(template, answers));

        Blueprint blueprint = Scaffold.of(template, answers, Catalogue.bundled());
        project = Scaffold.folder(template, answers, root);
        Blueprint.Writing writing = new Blueprint.Writing(project);
        writing.begin();
        for (Blueprint.Entry entry : blueprint.entries()) {
            writing.write(entry);
        }

        // The builder's tree, with the parts this witness is about laid over it. The template's own Doc, Model,
        // Look and Type stay: the point is a real generated application, changed only where it is being tested.
        String recipes = blueprint.paths().stream().filter(p -> p.endsWith("/Recipes.java")).findFirst()
                .orElseThrow(() -> new AssertionError("no Recipes.java in " + blueprint.paths()));
        String main = recipes.substring(0, recipes.lastIndexOf('/'));
        String packageName = main.substring("src/main/java/".length()).replace('/', '.');
        String className = blueprint.paths().stream()
                .filter(p -> p.startsWith(main + "/") && blueprint.text(p).contains("@VexelApp"))
                .map(p -> p.substring(p.lastIndexOf('/') + 1, p.length() - ".java".length()))
                .findFirst().orElseThrow(() -> new AssertionError("no @VexelApp class in " + blueprint.paths()));
        for (String name : OVERLAY) {
            String source = resource("/witnesses/components/" + name + ".java")
                    .replace("${packageName}", packageName)
                    .replace("${className}", className);
            Files.writeString(project.resolve(main).resolve(name + ".java"), source, StandardCharsets.UTF_8);
        }

        Path log = root.resolve("build.log");
        Process build = maven(project, log, "package").start();
        if (!build.waitFor(BUILD_MINUTES, TimeUnit.MINUTES)) {
            stop(build);
            fail("the witness's build took longer than " + BUILD_MINUTES + " minutes\n" + tail(log));
        }
        assertEquals(0, build.exitValue(), () -> "the witness did not build\n" + tail(log));
        // Components with @Subscribe and no Placement parameter, and no hand-written wiring anywhere.
        Path wiring = project.resolve("target/generated-sources/annotations/" + main.substring("src/main/java/".length())
                + "/" + className + "Wiring.java");
        assertTrue(Files.isRegularFile(wiring), "the processor did not generate " + wiring);
        String generated = Files.readString(wiring);
        assertTrue(generated.contains("shell.place(\"worker\")"), "no placement for the worker lane\n" + generated);
        assertTrue(generated.contains("shell.place(\"<default>\")"), "no placement for the default lane\n" + generated);
        // Four components, three lanes: Echo and Wedger name none, so they share the one default placement.
        assertEquals(3, generated.split("private dev\\.vexelray\\.framework\\.shell\\.Placement ", -1).length - 1,
                "four components on three lanes should be three placements\n" + generated);
        assertTrue(generated.contains("shell.liveness("), "the liveness policy was not handed back\n" + generated);
        built = true;
    }

    // --- recover: a wedge is reported, contained and freed ---------------------------------------------------

    @Test
    @Order(2)
    void aWedgedComponentNeverStopsTheWindowAndItsLaneIsFreedByThePolicy() throws Exception {
        assertTrue(built, "nothing was built");
        try (Session session = launch("recover", "recover")) {
            Driver d = session.driver;
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
            session.out.println("quit");
        }
    }

    // --- exit: the default policy ends the application ------------------------------------------------------

    @Test
    @Order(3)
    void theDefaultPolicyExitsAnApplicationWhoseLaneIsWedgedThroughItsOwnCloseRoute() throws Exception {
        assertTrue(built, "nothing was built");
        try (Session session = launch("exit", "exit")) {
            Driver d = session.driver;
            d.ok("settle");
            d.ok("click " + d.ref("button.wedge.default"));
            d.ok("await default default wedged");
            // Reported after the one-second threshold, then Context.exit: a close request through the window's
            // own route, and the process is ended regardless once the grace has passed.
            assertTrue(app.waitFor(90, TimeUnit.SECONDS),
                    "the application was still running 90 s after a lane wedged under the default policy\n"
                            + tail(session.log));
            assertFalse(app.isAlive());
        }
        app = null;
    }

    // --- one launch ------------------------------------------------------------------------------------------

    private record Session(Socket socket, PrintWriter out, Driver driver, Path log) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            socket.close();
            if (app != null && app.isAlive()) {
                stop(app);
            }
            app = null;
        }
    }

    private static Session launch(String mode, String name) throws Exception {
        int port = freePort();
        Path log = root.resolve("run-" + name + ".log");
        Path home = root.resolve("home-" + name);
        app = maven(project, log, "compile", "exec:exec",
                "-Dautomation=" + port,
                "-Dapp.jvmArgs=-D" + ARTIFACT + ".home=" + home + " -Dwitness.policy=" + mode).start();
        Socket socket = connect(app, port, log);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        return new Session(socket, out, new Driver(out, in, log), log);
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = ComponentsWitnessTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

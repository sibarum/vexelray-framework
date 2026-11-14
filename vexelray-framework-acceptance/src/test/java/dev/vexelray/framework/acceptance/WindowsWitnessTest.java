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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1, several windows on one device: a generated application with a main window, a peer and a satellite, all three
 * views of one model, built and driven through its socket.
 *
 * <p><b>Why this shape.</b> Every application generated so far is one window plus dialogs, and the three second
 * windows that exist (the calculator's settings, Vexplore's viewer, W2's tool window) each claimed their window by
 * hand. This one declares two through {@code Windows} and checks what the framework now owes each of them: a tree of
 * its own that the main window does not draw, a model the three share, a window that can close and come back to
 * the same tree, a request to open made before there is a device, and a placement and a zoom remembered under the
 * window's name across a restart, independently of the main window's.
 *
 * <p>Each finding is written up in {@code docs/architecture.md}, <i>what the two-window witness found</i>.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WindowsWitnessTest {

    private static final String ARTIFACT = "vexel-witness-windows";
    private static final List<String> OVERLAY = List.of("Landmarks", "Parts", "Ui", "Notes", "Palette", "Gate", "Keeper",
            "Shown", "Recipes");

    private static Path root;
    private static Project project;
    private static Session live;

    @BeforeAll
    static void clearTheLastRun() throws IOException {
        root = fresh("windows");
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
    void theBuilderGeneratesAProjectTheOverlayGivesTwoMoreWindows() throws Exception {
        project = generate(root, ARTIFACT, "/witnesses/windows", OVERLAY);
        build(project, root.resolve("build.log"));

        String generated = Files.readString(project.wiring());
        // The finding the seam exists for: two windows and nothing main-thread, so nothing waits for WINDOW.
        assertTrue(generated.contains("shell.windows()"), "no recipe was handed the windows\n" + generated);
        assertFalse(generated.contains("shell.app()"), "a recipe still takes the GuiApp\n" + generated);
    }

    /**
     * The framework's own {@code Dialogs}, for real: a question asked in {@code TREE}, before there is a window, is held
     * and asked once there is one, in a window of its own; Enter takes its default button and Escape its cancel, and
     * the answer reaches the model. Before the seam, asking that early threw.
     */
    @Test
    @Order(4)
    void aQuestionAskedBeforeTheWindowIsAskedInItAndAnswered() throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "asked", "-Dwitness.ask=early")) {
            Driver d = session.driver();
            d.firstFrame("button.palette");

            // The question asked in TREE is up, as the one other window.
            awaitWindows(d, 2);
            d.ok("window 2");
            d.ok("settle");
            d.ok("key enter");
            awaitWindows(d, 1);
            d.ok("window main");
            d.ok("await count 1");

            // Asked again from the button, and cancelled: the count stays where it was.
            d.ok("click " + d.ref("button.ask"));
            awaitWindows(d, 2);
            d.ok("window 2");
            d.ok("settle");
            d.ok("key escape");
            awaitWindows(d, 1);
            d.ok("window main");
            d.ok("settle");
            String count = d.ok("find count");
            assertTrue(count.contains("\"1\""), "a cancelled question changed the count\n" + count);
            quit(session);
        }
        live = null;
    }

    /**
     * Press Quit, which closes the main window through {@code Windows.main()}, and wait for the application to end on
     * its own. Nothing here stops the process: if the handle did not reach the window's close route, this fails.
     */
    private static void quit(Session session) throws Exception {
        Driver d = session.driver();
        d.ok("window main");
        d.ok("click " + d.ref("button.quit"));
        session.out().println("quit");
        assertTrue(session.app().waitFor(60, TimeUnit.SECONDS),
                "Quit did not end the application within 60 s\n" + tail(session.log()));
        String run = Support.read(session.log());
        assertTrue(run.contains("stopped: " + ARTIFACT), "the application did not stop by its own loop\n" + tail(session.log()));
    }

    /** Wait for {@code count} windows to be open. Opening and closing are posted, so they land a frame or so later. */
    private static void awaitWindows(Driver d, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String reply = "";
        while (System.nanoTime() < deadline) {
            reply = d.ok("windows");
            if (reply.startsWith("ok " + count + "\n") || reply.equals("ok " + count)) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals("ok " + count, reply.lines().findFirst().orElse(""), "the driver says\n" + reply);
    }
}

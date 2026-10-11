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
import java.nio.file.StandardCopyOption;
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
    private static final List<String> OVERLAY = List.of("Landmarks", "Parts", "Ui", "Notes", "Palette", "Recipes");

    /** What the notes window is resized and zoomed to, and so what the second launch must find it at. */
    private static final String NOTES_SIZE = "520x380";
    private static final String NOTES_ZOOM = "1.5";

    private static Path root;
    private static Path home;
    private static Project project;
    private static Session live;

    @BeforeAll
    static void clearTheLastRun() throws IOException {
        root = fresh("windows");
        home = root.resolve("home");
    }

    @AfterAll
    static void stopTheApplication() throws IOException {
        if (live != null) {
            live.close();
        }
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

    @Test
    @Order(2)
    void threeWindowsAreThreeTreesOnOneModel() throws Exception {
        assertTrue(project != null, "nothing was built");
        try (Session session = live = launch(project, root, ARTIFACT, "first", home, "-Dwitness.open=notes")) {
            Driver d = session.driver();
            d.firstFrame("button.palette");

            // Asked for in TREE, before there was a device: held, and carried out when the window was claimed.
            awaitWindows(d, 2);
            d.ok("click " + d.ref("button.palette"));
            awaitWindows(d, 3);

            // Each window draws its own tree. The main window once drew the calculator's settings tree, because both
            // were on one bus (calculator FN-2), so the check is that a window's driver cannot see the others' nodes.
            d.ok("window palette");
            // Its own landmark first, so the check below is against a tree that has been drawn: settle can answer
            // before a just-opened window's first frame, and an empty tree holds nobody's button.
            d.ok("await palette.count 0");
            assertFalse(d.ok("find button.count").contains("@button.count"),
                    "the palette's tree holds the main window's button");
            d.ok("click " + d.ref("palette.bump"));
            d.ok("await palette.count 1");
            d.ok("window notes");
            d.ok("await notes.count 1");
            d.ok("await notes.opened opened 1");
            assertFalse(d.ok("find palette.bump").contains("@palette.bump"), "the notes tree holds the palette's");
            d.ok("window main");
            d.ok("await count 1");

            // Each window has its own zoom and size, and the notes window's are what the next launch must find.
            d.ok("window notes");
            String zoomed = d.ok("zoom " + NOTES_ZOOM);
            assertTrue(zoomed.contains(NOTES_ZOOM), "the notes window would not zoom to " + NOTES_ZOOM + ": " + zoomed);
            d.ok("resize " + NOTES_SIZE);
            d.ok("settle");
            String notes = d.ok("size");
            assertTrue(notes.startsWith("ok " + NOTES_SIZE + " "), "the notes window did not take its size: " + notes);
            d.ok("window main");
            String main = d.ok("size");
            assertTrue(main.contains(" zoom=1 ") || main.contains(" zoom=1.0 "),
                    "zooming the notes window zoomed the main one: " + main);

            // The palette's close gate asks first, in a dialog of its own: the fourth window. Escape is Cancel, and the
            // palette stays; the second time Enter is Close, and it goes.
            d.ok("window palette");
            d.ok("click " + d.ref("palette.close"));
            awaitWindows(d, 4);
            d.ok("window 4");
            d.ok("settle");
            d.ok("key escape");
            awaitWindows(d, 3);
            d.ok("window palette");
            d.ok("await palette.count 1");
            d.ok("click " + d.ref("palette.close"));
            awaitWindows(d, 4);
            d.ok("window 4");
            d.ok("settle");
            d.ok("key enter");
            // Then it closes and comes back to the tree it left: same count, no second window made.
            awaitWindows(d, 2);
            d.ok("window main");
            // No settle first, on purpose: a click straight after the close was the first sign of the lost wake
            // (architecture.md, what the two-window witness found), so this is where it would show again.
            d.ok("click " + d.ref("button.count"));
            d.ok("await count 2");
            d.ok("click " + d.ref("button.palette"));
            awaitWindows(d, 3);
            d.ok("click " + d.ref("button.palette"));
            Thread.sleep(300);
            awaitWindows(d, 3);
            d.ok("window palette");
            d.ok("await palette.count 2");

            for (String window : List.of("main", "notes", "palette")) {
                d.ok("window " + window);
                d.ok("settle");
                Path shot = root.resolve(window + ".png");
                d.ok("shot " + shot);
                assertTrue(Files.size(shot) > 0, "the photograph of " + window + " is empty");
            }

            String run = Files.readString(session.log());
            assertFalse(run.contains("a frame hook threw") || run.contains("uncaught exception"),
                    "the windows cost a frame hook\n" + tail(session.log()));

            // Quit through the main window's handle, by the route its close button takes, with two windows still
            // open. The placement is written on the way out, so the next launch reads what this one left.
            quit(session);
        }
        live = null;
    }

    @Test
    @Order(3)
    void aNamedWindowReopensWhereItWasLeftAtTheZoomItWasLeftAt() throws Exception {
        assertTrue(project != null, "nothing was built");
        // The home is shared so the placement carries over, and so is its log folder: the first launch's trace is
        // copied aside, or this launch would write over the record of the one that failed. Copied and not moved,
        // and best effort, because a stopped launch can still be holding its files for a moment.
        Path logs = home.resolve("logs");
        if (Files.isDirectory(logs)) {
            Path kept = Files.createDirectories(root.resolve("logs-first"));
            try (var files = Files.list(logs)) {
                for (Path f : files.toList()) {
                    try {
                        Files.copy(f, kept.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException held) {
                        // A diagnostic, not an assertion: the run goes on without it.
                    }
                }
            }
        }
        try (Session session = live = launch(project, root, ARTIFACT, "again", home)) {
            Driver d = session.driver();
            d.firstFrame("button.palette");
            awaitWindows(d, 1);
            d.ok("click " + d.ref("button.notes"));
            awaitWindows(d, 2);
            d.ok("window notes");
            // Its listener ran for this process's first open, so the count is this launch's and not the last one's.
            // Awaited first, as the window's own landmark, rather than a settle that can beat its first frame.
            d.ok("await notes.opened opened 1");
            String notes = d.ok("size");
            assertTrue(notes.startsWith("ok " + NOTES_SIZE + " "), "the notes window forgot its size: " + notes);
            assertTrue(notes.contains(" zoom=" + NOTES_ZOOM + " "), "the notes window forgot its zoom: " + notes);
            quit(session);
        }
        live = null;
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
        String run = Files.readString(session.log());
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

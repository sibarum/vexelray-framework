package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.probe.Mode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offer after a run that did not close: asked as a part asks, a place picked, the zip written off the frame. */
class BugReportPromptTest {

    private final Atchung bus = Atchung.create();
    private final List<Ask> asked = new ArrayList<>();
    private final List<PickFile> picks = new ArrayList<>();
    /** What was handed to the job: the job itself is {@link BugReportPrompt#write}, run here by hand. */
    private final List<BugReportPrompt.Write> writes = new ArrayList<>();
    private Path logs;

    @BeforeEach
    void listen(@TempDir Path dir) throws Exception {
        bus.subscribe(Ask.TOPIC, asked::add);
        bus.subscribe(PickFile.TOPIC, picks::add);
        bus.subscribe(BugReportPrompt.Write.TOPIC, writes::add);
        logs = dir;
        Files.writeString(dir.resolve("demo.log"), "the run that crashed\n");
    }

    private BugReportPrompt.Contents contents() {
        return new BugReportPrompt.Contents("demo", "Demo", "Demo (g:a:1)\n", "", "PACKAGED", logs.resolve("demo.log"),
                List.of(new UncleanExits.Ended(42, Instant.parse("2026-11-10T09:00:00Z"))));
    }

    private BugReportPrompt prompt() {
        return new BugReportPrompt(new OwnAsks(bus), bus, contents());
    }

    @Test
    void itAsksWithTheFrameworksOwnIdAndSavingFirst() {
        prompt().offer();
        Ask a = asked.getFirst();
        assertTrue(a.id() < 0);
        assertEquals("Demo closed unexpectedly", a.title());
        assertEquals(List.of("Save bug report…", "Not now"), a.buttons());
        assertTrue(a.message().contains("nothing is sent anywhere"), a.message());
    }

    @Test
    void savingAsksWhereAndWritesTheZipOffTheFrame(@TempDir Path to) throws Exception {
        BugReportPrompt p = prompt();
        p.offer();
        p.answered(new Answer(asked.getFirst().id(), 0));
        PickFile pick = picks.getFirst();
        assertEquals(PickFile.Kind.SAVE, pick.kind());
        assertTrue(pick.name().startsWith("demo-bug-report-") && pick.name().endsWith(".zip"), pick.name());
        assertTrue(pick.id() < 0 && pick.id() != asked.getFirst().id());

        p.picked(new Picked(pick.id(), to.resolve("report").toString()));   // a name typed without its extension
        assertEquals(List.of(new BugReportPrompt.Write(to.resolve("report.zip").toString())), writes,
                "handed to the job as a message, not written in the frame");
        assertFalse(Files.exists(to.resolve("report.zip")));
        assertEquals(1, asked.size(), "nothing said before the job answers");

        BugReportPrompt.Written w = BugReportPrompt.write(contents(), writes.getFirst());
        assertTrue(w.saved(), w.failure());
        assertTrue(Files.isRegularFile(to.resolve("report.zip")));
        p.written(w);
        assertEquals("Bug report saved", asked.getLast().title());
    }

    /**
     * The whole round in a running tree: asked, a place picked, the zip written by the job lane, and how it went heard in
     * the main window's frame and said. The dialogs answer as a person would, in the frame, on this thread.
     */
    @Test
    void theReportIsWrittenOnTheJobLaneAndSaidInAFrame(@TempDir Path home, @TempDir Path to) {
        AppInfo demo = new AppInfo("demo", "Demo", 800, 600);
        System.setProperty(demo.name() + ".home", home.toString());
        List<String> shown = new ArrayList<>();
        Dialogs person = new Dialogs() {
            @Override
            public void ask(dev.vexelray.gui.widget.Modal modal) {
                shown.add(modal.title());
                bus().publish(Answer.TOPIC, (Answer) modal.buttons().getFirst().message());
            }

            @Override
            public void pick(PickFile request, sibarum.atchung.Sender<Picked> picked) {
                picked.send(new Picked(request.id(), to.resolve("report.zip").toString()));
            }
        };
        Shell shell = VexelApplication.tree(new Wiring() {
            @Override
            public AppInfo info() {
                return demo;
            }

            @Override
            public void config(Shell shell) {
                shell.dialogs(person);
            }
        }, new String[0]);
        running = shell;
        try {
            Frames frames = VexelApplication.frames(shell);
            new BugReportPrompt(shell, contents()).offer();
            frames.until("the report to be said saved", () -> shown.contains("Bug report saved"));
            assertEquals(List.of("Demo closed unexpectedly", "Bug report saved"), shown);
            assertTrue(Files.isRegularFile(to.resolve("report.zip")));
        } finally {
            shell.disposer().close();
        }
    }

    /** The tree the round test runs, for its dialogs to answer on. */
    private Shell running;

    private Atchung bus() {
        return running.bus();
    }

    /** A place that cannot be written is the job's answer, not its throw, which would end the application. */
    @Test
    void aReportThatCannotBeWrittenIsSaidSo(@TempDir Path to) throws Exception {
        Path blocked = Files.writeString(to.resolve("file"), "a file, so nothing can go inside it");
        BugReportPrompt.Written w = BugReportPrompt.write(contents(),
                new BugReportPrompt.Write(blocked.resolve("report.zip").toString()));
        assertFalse(w.saved());
        BugReportPrompt p = prompt();
        p.written(w);
        assertEquals("The bug report was not saved", asked.getLast().title());
        assertTrue(asked.getLast().message().contains(w.failure()), asked.getLast().message());
    }

    @Test
    void notNowAndCancelEndIt() {
        BugReportPrompt p = prompt();
        p.offer();
        p.answered(new Answer(asked.getFirst().id(), 1));
        assertTrue(picks.isEmpty());

        BugReportPrompt q = prompt();
        q.offer();
        q.answered(new Answer(asked.getLast().id(), 0));
        q.picked(Picked.cancelled(picks.getFirst().id()));
        assertTrue(writes.isEmpty());
    }

    @Test
    void anotherQuestionsAnswerIsNotTakenForIt() {
        BugReportPrompt p = prompt();
        p.offer();
        p.answered(new Answer(-asked.getFirst().id(), 0));
        p.answered(new Answer(asked.getFirst().id() - 1, 0));
        assertTrue(picks.isEmpty());
    }

    @Test
    void itAsksOnlyWhenSomebodyIsThere() {
        assertTrue(BugReportPrompt.asks(Mode.PACKAGED, null, false));
        assertTrue(BugReportPrompt.asks(Mode.DEV, null, false));
        assertFalse(BugReportPrompt.asks(Mode.AUTOMATION, null, false), "a modal would stand in front of the driver");
        assertFalse(BugReportPrompt.asks(Mode.TEST, null, false));
        assertFalse(BugReportPrompt.asks(Mode.PACKAGED, null, true), "an agent's background run");
        assertTrue(BugReportPrompt.asks(Mode.AUTOMATION, "ask", true), "how the prompt itself is driven");
        assertFalse(BugReportPrompt.asks(Mode.PACKAGED, "log", false));
    }
}

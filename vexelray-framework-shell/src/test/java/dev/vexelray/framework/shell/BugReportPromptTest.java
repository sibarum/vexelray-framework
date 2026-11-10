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
    private final List<Runnable> written = new ArrayList<>();
    private Path logs;

    @BeforeEach
    void listen(@TempDir Path dir) throws Exception {
        bus.subscribe(Ask.TOPIC, asked::add);
        bus.subscribe(PickFile.TOPIC, picks::add);
        logs = dir;
        Files.writeString(dir.resolve("demo.log"), "the run that crashed\n");
    }

    private BugReportPrompt prompt() {
        return new BugReportPrompt(new OwnAsks(bus), new BugReportPrompt.Contents("demo", "Demo", "Demo (g:a:1)\n",
                "", "PACKAGED", logs.resolve("demo.log"),
                List.of(new UncleanExits.Ended(42, Instant.parse("2026-11-10T09:00:00Z")))), written::add);
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
        assertEquals(1, written.size(), "handed to the writer, not written on the frame");
        assertFalse(Files.exists(to.resolve("report.zip")));
        written.getFirst().run();
        assertTrue(Files.isRegularFile(to.resolve("report.zip")));
        assertEquals("Bug report saved", asked.getLast().title());
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
        assertTrue(written.isEmpty());
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

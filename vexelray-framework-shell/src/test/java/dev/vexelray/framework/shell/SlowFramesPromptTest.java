package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.SlowFrames;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.spi.WidgetSeams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Sender;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The slow-frames question: what it says, that each way out of it is an answer, and that it is asked as a part asks. */
class SlowFramesPromptTest {

    private static final AppInfo DEMO = new AppInfo("slowframestest", "Slow", 800, 600);

    private final Atchung bus = Atchung.create();
    private final Gui gui = new Gui(bus);
    private final List<Ask> asked = new ArrayList<>();
    private final List<SlowFrames.Answer> answers = new ArrayList<>();
    private Shell shell;

    @BeforeEach
    void ownHome(@TempDir Path home) {
        System.setProperty(DEMO.name() + ".home", home.toString());
        bus.subscribe(Ask.TOPIC, asked::add);
    }

    @AfterEach
    void close() {
        gui.close();
        if (shell != null) {
            shell.disposer().close();
        }
    }

    @Test
    void itAsksAnAskWithTheFrameworksOwnId() {
        new SlowFramesPrompt(new OwnAsks(bus), gui).ask(gui, 400_000_000L, answers::add);
        assertEquals(1, asked.size());
        Ask a = asked.getFirst();
        assertTrue(a.id() < 0, "an application's ids are zero or more: " + a.id());
        assertEquals("This is drawing slowly", a.title());
        assertEquals(List.of("Switch to simplified graphics", "Don't ask again", "Not now"), a.buttons(),
                "switching is the default, and not now the cancel that Escape and the close box press");
    }

    @Test
    void eachButtonIsItsAnswerAndOnlyOnce() {
        SlowFramesPrompt prompt = new SlowFramesPrompt(new OwnAsks(bus), gui);
        for (int button = 0; button < 3; button++) {
            prompt.ask(gui, 400_000_000L, answers::add);
            prompt.answered(new Answer(asked.getLast().id(), button));
            prompt.answered(new Answer(asked.getLast().id(), button));
        }
        assertEquals(List.of(SlowFrames.Answer.SWITCH, SlowFrames.Answer.NEVER, SlowFrames.Answer.NOT_NOW), answers);
    }

    @Test
    void anApplicationsAnswerIsNotTakenForIt() {
        SlowFramesPrompt prompt = new SlowFramesPrompt(new OwnAsks(bus), gui);
        prompt.ask(gui, 400_000_000L, answers::add);
        prompt.answered(new Answer(-asked.getFirst().id(), 0));
        assertTrue(answers.isEmpty());
    }

    @Test
    void itSaysHowSlowInFramesASecond() {
        assertTrue(SlowFramesPrompt.message(400_000_000L).contains("about 3 frames a second"));
        assertTrue(SlowFramesPrompt.message(1_500_000_000L).contains("about 1 frame a second"));
    }

    /** End to end, headless: the application's own dialogs are asked, and the press comes back to the frame loop. */
    @Test
    void itAsksThroughTheApplicationsDialogsAndHearsTheirAnswer() {
        List<String> shown = new ArrayList<>();
        Dialogs dialogs = new Dialogs() {
            @Override
            public void ask(Modal modal) {
                shown.add(modal.title());
                for (Modal.Button b : modal.buttons()) {
                    if (b.label().equals("Don't ask again")) {
                        WidgetSeams.get().press(b);
                    }
                }
            }

            @Override
            public void pick(PickFile request, Sender<Picked> answer) {
            }
        };
        shell = VexelApplication.tree(new Wiring() {
            @Override
            public AppInfo info() {
                return DEMO;
            }

            @Override
            public void config(Shell shell) {
                shell.dialogs(dialogs);
            }

            @Override
            public void tree(Shell shell) {
            }
        }, new String[0]);
        new SlowFramesPrompt(shell.ownAsks(), shell.gui()).ask(shell.gui(), 300_000_000L, answers::add);
        for (int i = 0; i < 3 && answers.isEmpty(); i++) {
            VexelApplication.frame(shell);
        }
        assertEquals(List.of("This is drawing slowly"), shown);
        assertEquals(List.of(SlowFrames.Answer.NEVER), answers);
    }
}

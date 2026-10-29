package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.spi.WidgetSeams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Sender;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialogs: questions as messages, heard in the main window's frame and handed to whichever dialogs answer, held
 * until something does, and answerable by a test.
 *
 * <p><b>No GPU.</b> The point of the seam is that none of this needs a window: a headless tree that asks a question
 * used to throw, because the dialogs were a static installed with the window.
 */
final class DialogsTest {

    private static final AppInfo DEMO = new AppInfo("dialogstest", "Dialogs", 800, 600);

    private Shell shell;

    @BeforeEach
    void ownHome(@TempDir Path home) {
        System.setProperty(DEMO.name() + ".home", home.toString());
    }

    @AfterEach
    void close() {
        if (shell != null) {
            shell.disposer().close();
        }
    }

    /**
     * Dialogs that write down every question, press the button labelled {@link #press} by sending what it declares,
     * and answer a picker with {@link #answer}, or cancel when it is null. As a test's or a kiosk's would.
     */
    private static class Recording implements Dialogs {

        final List<String> asked = new ArrayList<>();
        String press = "Yes";
        Path answer;

        @Override
        public void ask(Modal modal) {
            asked.add("ask " + modal.title());
            for (Modal.Button b : modal.buttons()) {
                if (b.label().equals(press)) {
                    WidgetSeams.get().press(b);
                }
            }
        }

        @Override
        public void pick(PickFile request, Sender<Picked> picked) {
            asked.add(request.kind() + " " + request.name());
            picked.send(answer == null ? Picked.cancelled(request.id()) : new Picked(request.id(), answer.toString()));
        }
    }

    private static Wiring wiring(Recording recording, Consumer<Shell> tree) {
        return new Wiring() {
            @Override
            public AppInfo info() {
                return DEMO;
            }

            @Override
            public void config(Shell shell) {
                shell.dialogs(recording);
            }

            @Override
            public void tree(Shell shell) {
                tree.accept(shell);
            }
        };
    }

    @Test
    void anAskIsADialogWhoseButtonsSendItsAnswer() {
        Recording recording = new Recording();
        recording.press = "Discard";
        shell = VexelApplication.tree(wiring(recording, s -> { }), new String[0]);
        List<Answer> answers = new ArrayList<>();
        shell.bus().subscribe(Answer.TOPIC, answers::add);

        shell.bus().publish(Ask.TOPIC, new Ask(7, "Quit?", "Unsaved.", List.of("Save", "Discard", "Cancel")));
        assertTrue(recording.asked.isEmpty(), "a question was asked outside the frame");
        VexelApplication.frame(shell);
        assertEquals(List.of("ask Quit?"), recording.asked);
        assertEquals(List.of(new Answer(7, 1)), answers);
    }

    @Test
    void theButtonsAreDefaultFirstAndCancelLast() {
        Recording recording = new Recording();
        List<Modal> shown = new ArrayList<>();
        shell = VexelApplication.tree(wiring(new Recording() {
            @Override
            public void ask(Modal modal) {
                shown.add(modal);
            }
        }, s -> { }), new String[0]);
        shell.bus().publish(Ask.TOPIC, new Ask(1, "Q", "?", List.of("Save", "Discard", "Cancel")));
        VexelApplication.frame(shell);
        Modal m = shown.getFirst();
        assertEquals("Save", m.defaultButton().label());
        assertEquals("Cancel", m.cancelButton().label());
        assertEquals(List.of("Save", "Discard", "Cancel"), m.buttons().stream().map(Modal.Button::label).toList());
        assertTrue(recording.asked.isEmpty());
    }

    @Test
    void aPickFileIsAnsweredWithPicked() {
        Recording recording = new Recording();
        recording.answer = Path.of("notes.txt");
        shell = VexelApplication.tree(wiring(recording, s -> { }), new String[0]);
        List<Picked> picked = new ArrayList<>();
        shell.bus().subscribe(Picked.TOPIC, picked::add);

        shell.bus().publish(PickFile.TOPIC, PickFile.save(3, "", "notes.txt", List.of()));
        VexelApplication.frame(shell);
        recording.answer = null;
        shell.bus().publish(PickFile.TOPIC, PickFile.folder(4, ""));
        VexelApplication.frame(shell);

        assertEquals(List.of("SAVE notes.txt", "FOLDER "), recording.asked);
        assertEquals(List.of(new Picked(3, "notes.txt"), Picked.cancelled(4)), picked);
        assertTrue(picked.get(1).isCancelled());
    }

    @Test
    void aQuestionAskedBeforeAnythingAnswersIsHeldAndAskedInOrder() {
        List<Answer> answers = new ArrayList<>();
        Recording recording = new Recording();
        shell =VexelApplication.tree(new Wiring() {
            @Override
            public AppInfo info() {
                return DEMO;
            }

            @Override
            public void model(Shell shell) {
                // Before the GUI phase: no frame yet, so held in a list until there is one.
                shell.dialogs().ask(Modal.of("first", "?"));
            }

            @Override
            public void tree(Shell shell) {
                shell.bus().publish(Ask.TOPIC, Ask.info(2, "second", "!"));
                shell.dialogs().ask(Modal.of("third", "?"));
            }
        }, new String[0]);
        shell.bus().subscribe(Answer.TOPIC, answers::add);

        // Nothing answers: a headless tree before ATTACH, with no dialogs of the application's.
        VexelApplication.frame(shell);
        assertTrue(recording.asked.isEmpty());

        recording.press = "OK";
        shell.dialogs(recording);
        assertEquals(List.of("ask first", "ask second", "ask third"), recording.asked);
        assertEquals(List.of(new Answer(2, 0)), answers);
    }

    @Test
    void theDialogsAPartHoldsAreTheSameValueInEveryPhase() {
        Shell bare = new Shell(Launch.parse(new String[0], DEMO.name(), Set.of()), DEMO);
        Dialogs atConfig = bare.dialogs();
        bare.phase(Phase.TREE);
        assertSame(atConfig, bare.dialogs(), "a part built in CONFIG would be holding dialogs nobody answers");
    }

    @Test
    void theApplicationsDialogsAreTakenBackByWindowAndOnce() {
        Shell bare = new Shell(Launch.parse(new String[0], DEMO.name(), Set.of()), DEMO);
        bare.dialogs(new Recording());
        IllegalStateException twice = assertThrows(IllegalStateException.class, () -> bare.dialogs(new Recording()));
        assertTrue(twice.getMessage().contains("already"), twice.getMessage());

        Shell late = new Shell(Launch.parse(new String[0], DEMO.name(), Set.of()), DEMO);
        late.phase(Phase.ATTACH);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> late.dialogs(new Recording()));
        assertTrue(e.getMessage().contains("WINDOW"), e.getMessage());
    }
}

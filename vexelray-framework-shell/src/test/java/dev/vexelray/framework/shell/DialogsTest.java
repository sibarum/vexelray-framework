package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * The dialogs as a value a part is handed: held until something answers, and answerable by a test.
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

    /** Dialogs that write down every question and answer a picker with {@code answer}, or cancel when it is null. */
    private static final class Recording implements Dialogs {

        final List<String> asked = new ArrayList<>();
        Path answer;

        @Override
        public void ask(Modal modal) {
            asked.add("ask " + modal.title());
            modal.defaultButton().action().run();
        }

        @Override
        public void openFile(Path start, List<FileDialog.Filter> filters, Consumer<Path> picked, Runnable cancelled) {
            asked.add("open");
            reply(picked, cancelled);
        }

        @Override
        public void openFolder(Path start, Consumer<Path> picked, Runnable cancelled) {
            asked.add("folder");
            reply(picked, cancelled);
        }

        @Override
        public void saveFile(Path start, String name, List<FileDialog.Filter> filters, Consumer<Path> picked,
                             Runnable cancelled) {
            asked.add("save " + name);
            reply(picked, cancelled);
        }

        private void reply(Consumer<Path> picked, Runnable cancelled) {
            if (answer == null) {
                cancelled.run();
            } else {
                picked.accept(answer);
            }
        }
    }

    private static Modal question(String title, List<String> answered) {
        return Modal.of(title, "?").defaultButton("Yes", () -> answered.add(title));
    }

    @Test
    void aQuestionAskedBeforeAnythingAnswersIsHeldAndAskedInOrder() {
        HeldDialogs held = new HeldDialogs();
        List<String> answered = new ArrayList<>();
        held.ask(question("first", answered));
        held.openFolder(null, p -> answered.add("folder " + p), () -> answered.add("folder cancelled"));
        held.ask(question("second", answered));
        assertTrue(answered.isEmpty(), "a question was answered with nothing to answer it");

        Recording recording = new Recording();
        held.target(recording);
        held.ask(question("third", answered));

        assertEquals(List.of("ask first", "folder", "ask second", "ask third"), recording.asked);
        assertEquals(List.of("first", "folder cancelled", "second", "third"), answered);
    }

    @Test
    void aHeadlessTreeAsksTheApplicationsOwnDialogs() {
        Recording recording = new Recording();
        recording.answer = Path.of("picked.txt");
        List<String> answered = new ArrayList<>();
        shell = VexelApplication.tree(new Wiring() {
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
                // What a part built in TREE does with the Dialogs it was handed: before this seam, a throw.
                shell.dialogs().ask(question("Delete?", answered));
                shell.dialogs().saveFile(null, "notes.txt", List.of(), p -> answered.add("saved " + p),
                        () -> answered.add("save cancelled"));
            }
        }, new String[0]);

        assertEquals(List.of("ask Delete?", "save notes.txt"), recording.asked);
        assertEquals(List.of("Delete?", "saved picked.txt"), answered);
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

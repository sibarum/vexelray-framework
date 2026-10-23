package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.app.SlowFrames;
import dev.vexelray.gui.widget.Modal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The slow-frames question: what it says, and that each way out of it is an answer. */
class SlowFramesPromptTest {

    @Test
    void eachButtonAnswersAndEscapeIsNotNow() {
        List<SlowFrames.Answer> answers = new ArrayList<>();
        Modal modal = SlowFramesPrompt.modal(400_000_000L, answers::add);

        modal.defaultButton().action().run();
        modal.cancelButton().action().run();   // Escape and the close box press this one
        modal.buttons().stream().filter(b -> b.label().equals("Don't ask again")).findFirst().orElseThrow()
                .action().run();

        assertEquals(List.of(SlowFrames.Answer.SWITCH, SlowFrames.Answer.NOT_NOW, SlowFrames.Answer.NEVER), answers);
        assertEquals(Modal.Severity.QUESTION, modal.severity(), "a choice to make, not a failure");
    }

    @Test
    void itSaysHowSlowInFramesASecond() {
        assertTrue(SlowFramesPrompt.modal(400_000_000L, a -> { }).message().contains("about 3 frames a second"));
        assertTrue(SlowFramesPrompt.modal(1_500_000_000L, a -> { }).message().contains("about 1 frame a second"));
    }

    @Test
    void itAsksThroughTheApplicationsDialogs() {
        List<Modal> asked = new ArrayList<>();
        Dialogs dialogs = new Dialogs() {
            @Override
            public void ask(Modal modal) {
                asked.add(modal);
            }

            @Override
            public void openFile(java.nio.file.Path start, List<dev.vexelray.gui.nfd.FileDialog.Filter> filters,
                                 java.util.function.Consumer<java.nio.file.Path> picked, Runnable cancelled) {
            }

            @Override
            public void openFolder(java.nio.file.Path start, java.util.function.Consumer<java.nio.file.Path> picked,
                                   Runnable cancelled) {
            }

            @Override
            public void saveFile(java.nio.file.Path start, String name,
                                 List<dev.vexelray.gui.nfd.FileDialog.Filter> filters,
                                 java.util.function.Consumer<java.nio.file.Path> picked, Runnable cancelled) {
            }
        };
        new SlowFramesPrompt(dialogs).ask(null, 300_000_000L, a -> { });
        assertEquals(1, asked.size());
        assertEquals("This is drawing slowly", asked.getFirst().title());
    }
}

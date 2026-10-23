package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.SlowFrames;
import dev.vexelray.gui.widget.Modal;

import java.util.function.Consumer;

/**
 * The framework's answer to {@link SlowFrames.Offer}: when an application's frames are repeatedly too slow to draw five
 * a second, ask the user, through the application's {@link Dialogs}, whether to switch to simplified graphics.
 *
 * <p>Through {@code Dialogs} and not straight to the modals, so an application that provides its own dialogs (a test
 * that answers, a kiosk that answers nothing) is the one asked here too. A question, not an error: nothing has failed,
 * and the user has a real choice, so it says what it costs and what it does not.
 *
 * <p>The switch is live: nothing the user has open is lost, and if it does not help, it is taken back by itself
 * ({@link SlowFrames}). Kept, it is saved for every VexelRay application on this machine.
 */
final class SlowFramesPrompt implements SlowFrames.Offer {

    private final Dialogs dialogs;

    SlowFramesPrompt(Dialogs dialogs) {
        this.dialogs = dialogs;
    }

    @Override
    public void ask(Gui gui, long typicalFrameNanos, Consumer<SlowFrames.Answer> answer) {
        dialogs.ask(modal(typicalFrameNanos, answer));
    }

    /** The question, with its three answers; Escape and the close box are "Not now". */
    static Modal modal(long typicalFrameNanos, Consumer<SlowFrames.Answer> answer) {
        long perSecond = Math.max(1L, Math.round(1e9 / Math.max(1L, typicalFrameNanos)));
        return Modal.of("This is drawing slowly",
                        "This machine is managing about " + perSecond + (perSecond == 1 ? " frame" : " frames")
                                + " a second. Simplified graphics draws without shading effects or animation,"
                                + " which is often much faster.\n\nSwitch now? Nothing you have open is lost. If it"
                                + " helps, every VexelRay application will start with it; if it does not, it is"
                                + " turned off again.")
                .severity(Modal.Severity.QUESTION)
                .defaultButton("Switch to simplified graphics", () -> answer.accept(SlowFrames.Answer.SWITCH))
                .button("Don't ask again", () -> answer.accept(SlowFrames.Answer.NEVER))
                .cancelButton("Not now", () -> answer.accept(SlowFrames.Answer.NOT_NOW));
    }
}

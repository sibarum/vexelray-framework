package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Answer;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.SlowFrames;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The framework's answer to {@link SlowFrames.Offer}: when an application's frames are repeatedly too slow to draw
 * fifteen a second, ask the user whether to switch to simplified graphics.
 *
 * <p>As any part asks: an {@link dev.vexelray.framework.core.Ask} ({@link OwnAsks}), so an application that provides
 * its own dialogs (a test that answers, a kiosk that answers nothing) is the one asked here too, and its
 * {@link Answer}, heard in the main window's frame. The frame loop's {@code answer} stays here under the question's id
 * and is told on the main thread, so it never crosses a lane and no button runs code. A question, not an error:
 * nothing has failed, and the user has a real choice, so it says what it costs and what it does not.
 *
 * <p>The switch is live: nothing the user has open is lost. If it helps, it is saved for every VexelRay application on
 * this machine; if it does not, it stays on for the session and is not saved ({@link SlowFrames}).
 */
final class SlowFramesPrompt implements SlowFrames.Offer {

    static final String TITLE = "This is drawing slowly";

    /** The buttons, in the order {@link SlowFrames.Answer} is read from them: default first, cancel last. */
    static final List<String> BUTTONS = List.of("Switch to simplified graphics", "Don't ask again", "Not now");

    private static final SlowFrames.Answer[] BY_BUTTON =
            {SlowFrames.Answer.SWITCH, SlowFrames.Answer.NEVER, SlowFrames.Answer.NOT_NOW};

    /** Asked once a session; a few in case a frame loop asks again before it hears. */
    private static final int CAPACITY = 8;

    private final OwnAsks asks;
    /** Main thread: asked from the frame loop, answered in the main window's frame. */
    private final Map<Long, Consumer<SlowFrames.Answer>> waiting = new HashMap<>();

    /** Hear answers in {@code main}'s frame, the main window's. Main thread. */
    SlowFramesPrompt(OwnAsks asks, Gui main) {
        this.asks = asks;
        main.mailbox(Answer.TOPIC, this::answered, CAPACITY);
    }

    /** Main thread: the frame loop asks from its own iteration. */
    @Override
    public void ask(Gui gui, long typicalFrameNanos, Consumer<SlowFrames.Answer> answer) {
        waiting.put(asks.ask(TITLE, message(typicalFrameNanos), BUTTONS), answer);
    }

    /** Escape and the close box press the last button, so closing without choosing is "Not now". */
    void answered(Answer a) {
        if (!OwnAsks.own(a.id())) {
            return;
        }
        Consumer<SlowFrames.Answer> answer = waiting.remove(a.id());
        if (answer != null && a.button() >= 0 && a.button() < BY_BUTTON.length) {
            answer.accept(BY_BUTTON[a.button()]);
        }
    }

    static String message(long typicalFrameNanos) {
        long perSecond = Math.max(1L, Math.round(1e9 / Math.max(1L, typicalFrameNanos)));
        return "This machine is managing about " + perSecond + (perSecond == 1 ? " frame" : " frames")
                + " a second. Simplified graphics draws without shading effects or animation,"
                + " which is much less work for the machine.\n\nSwitch now? Nothing you have open is"
                + " lost. If it helps, every VexelRay application will start with it.";
    }
}

package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Ask;
import sibarum.atchung.Atchung;

import java.util.List;

/**
 * <b>The framework's own questions</b>, sent as any part sends one: an {@link Ask} on the application's bus, answered
 * by an {@link dev.vexelray.framework.core.Answer} that whoever asked hears in a frame. So the framework's prompts (the
 * slow-frames offer, undo's failures) go to whichever {@link Dialogs} answer the application, a test's or a kiosk's as
 * well as the framework's, and run nothing of their own at the press.
 *
 * <p><b>Negative ids.</b> Every part that hears {@code Answer} hears every answer, so the framework takes the ids an
 * application never does, counting down from -1; an application counts up from its own.
 *
 * <p>Main thread: every framework prompt is decided there, in a frame or in the frame loop.
 */
final class OwnAsks {

    private final Atchung bus;
    private long last;

    OwnAsks(Atchung bus) {
        this.bus = bus;
    }

    /** Ask, and return the id its answer will carry. */
    long ask(String title, String message, List<String> buttons) {
        long id = --last;
        bus.publish(Ask.TOPIC, new Ask(id, title, message, buttons));
        return id;
    }

    /** Whether {@code id} is one of the framework's: one an application never picks. */
    static boolean own(long id) {
        return id < 0L;
    }
}

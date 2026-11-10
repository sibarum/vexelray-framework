package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.PickFile;
import sibarum.atchung.Atchung;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>The framework's own questions</b>, sent as any part sends one: an {@link Ask} on the application's bus, answered
 * by an {@link dev.vexelray.framework.core.Answer} that whoever asked hears in a frame. So the framework's prompts (the
 * slow-frames offer, undo's failures, the bug report) go to whichever {@link Dialogs} answer the application, a test's
 * or a kiosk's as well as the framework's, and run nothing of their own at the press. A place to save is asked the same
 * way, as a {@link PickFile} answered by a {@link dev.vexelray.framework.core.Picked}.
 *
 * <p><b>Negative ids.</b> Every part that hears {@code Answer} hears every answer, so the framework takes the ids an
 * application never does, counting down from -1; an application counts up from its own. Questions and pickers share
 * the count, so no two of the framework's are ever confused.
 *
 * <p>The prompts are decided on the main thread, in a frame or in the frame loop. Safe from any thread all the same,
 * because what a prompt starts can finish elsewhere: the bug report says it was saved from the thread that wrote it.
 */
final class OwnAsks {

    private final Atchung bus;
    private final AtomicLong last = new AtomicLong();

    OwnAsks(Atchung bus) {
        this.bus = bus;
    }

    /** Ask, and return the id its answer will carry. */
    long ask(String title, String message, List<String> buttons) {
        long id = last.decrementAndGet();
        bus.publish(Ask.TOPIC, new Ask(id, title, message, buttons));
        return id;
    }

    /** Ask where to save, with {@code name} filled in, starting where the OS chooses; return the id it will carry. */
    long save(String name, List<PickFile.Filter> filters) {
        long id = last.decrementAndGet();
        bus.publish(PickFile.TOPIC, PickFile.save(id, "", name, filters));
        return id;
    }

    /** Whether {@code id} is one of the framework's: one an application never picks. */
    static boolean own(long id) {
        return id < 0L;
    }
}

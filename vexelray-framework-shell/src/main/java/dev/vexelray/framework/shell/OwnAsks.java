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
 * <p><b>Addressed from {@link #FROM}, and counted down from -1.</b> Every answer goes back to whoever asked, and the
 * prompts hear only the answers addressed here, so an application's ids, negative ones included, never reach them.
 * Among themselves the prompts tell their questions apart by id, which is why they share one count. A place to save is
 * answered by a {@code Picked}, which is not addressed, so a picker's id is still kept clear of an application's by
 * being negative.
 *
 * <p>The prompts are decided on the main thread, in a frame or in the frame loop. Safe from any thread all the same,
 * because what a prompt starts can finish elsewhere: the bug report says it was saved from the thread that wrote it.
 */
final class OwnAsks {

    /**
     * Who the framework's questions are from, and so the address the prompts hear their answers at: one no part of an
     * application has, since none is this class.
     */
    static final String FROM = OwnAsks.class.getCanonicalName();

    private final Atchung bus;
    private final AtomicLong last = new AtomicLong();

    OwnAsks(Atchung bus) {
        this.bus = bus;
    }

    /** Ask, and return the id its answer will carry. */
    long ask(String title, String message, List<String> buttons) {
        long id = last.decrementAndGet();
        bus.publish(Ask.TOPIC, new Ask(id, title, message, buttons, FROM));
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

package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.PickFile;
import sibarum.atchung.Atchung;

import java.util.List;

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
 * <p><b>The main thread's, and only its</b>, so the count is a plain field. Every call path, read 2026-11-15: the
 * {@link Shell} builds it, before any lane exists; {@link SlowFramesPrompt#ask} is called by the frame loop;
 * {@link UndoFeedback} asks from a tree's undo sink, told in that tree's frame; {@link BugReportPrompt} asks at
 * {@code ATTACH} ({@code offer}) and in the main window's frame ({@code answered}, {@code written}, the last being its
 * job's answer, which comes back as a message rather than asking from the job lane). Every window's frame runs on the
 * main thread, and in a headless run on the thread that steps the frames, which is the one that built the tree. A
 * prompt whose work finishes elsewhere hears how it went in a frame and asks from there; it never asks from another
 * thread, and a new caller off the main thread is a defect here (T7.3), not a reason to make the count atomic again.
 */
final class OwnAsks {

    /**
     * Who the framework's questions are from, and so the address the prompts hear their answers at: one no part of an
     * application has, since none is this class.
     */
    static final String FROM = OwnAsks.class.getCanonicalName();

    private final Atchung bus;
    /** The last id handed out, counting down from 0. Main thread (see the class's javadoc). */
    private long last;

    OwnAsks(Atchung bus) {
        this.bus = bus;
    }

    /** Ask, and return the id its answer will carry. */
    long ask(String title, String message, List<String> buttons) {
        long id = --last;
        bus.publish(Ask.TOPIC, new Ask(id, title, message, buttons, FROM));
        return id;
    }

    /** Ask where to save, with {@code name} filled in, starting where the OS chooses; return the id it will carry. */
    long save(String name, List<PickFile.Filter> filters) {
        long id = --last;
        bus.publish(PickFile.TOPIC, PickFile.save(id, "", name, filters));
        return id;
    }

    /** Whether {@code id} is one of the framework's: one an application never picks. */
    static boolean own(long id) {
        return id < 0L;
    }
}

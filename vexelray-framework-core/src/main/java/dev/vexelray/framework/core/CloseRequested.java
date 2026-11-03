package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>A window has been asked to close</b>, by its close button, by the application, or for the main window by the OS:
 * the question an application with unsaved work hears, and answers with a {@link CloseAnswer} carrying the same
 * {@code ticket}. For the main window, closing is quitting, so this is also how an application refuses to exit.
 *
 * <p><b>Asked only when something hears it.</b> The generated wiring knows whether any part does, and the default is
 * that closing closes: most applications have nothing to lose. When one does, every window's close waits for its
 * answer, and the window stays open and fully live meanwhile, which is what lets the answer come from a dialog. A part
 * that hears this and guards only some windows answers the others at once, by {@code window}.
 *
 * <p><b>Every part that hears it is asked, in turn</b> (regions.md, ruling 3): one at a time, in the order of their
 * qualified names, which means nothing but is the same on every run. The next is asked only when the last let the
 * window close, and the first that keeps it open ends the round, so one dialog is up at a time and nothing acts on a
 * close another part then refuses. Each is asked by a request of its own, addressed to it ({@code to}) and with a
 * ticket of its own; the wiring hands each part only the requests addressed to it, so a part's code is the same whether
 * it is the only one asked or one of several. The processor refuses a part that hears this and sends no
 * {@code CloseAnswer}, which would hold every window open for good.
 *
 * <p>This is the 0.1 close gate's two callbacks, {@code proceed} and {@code cancel}, turned into a message. A callback
 * cannot be sent to another lane, since it would carry whatever it closes over; a ticket can, and the framework keeps
 * the callbacks under it on the main thread. A second request for a window whose question is still unanswered is
 * refused there rather than asked again, so a person pressing the close button twice sees one dialog.
 *
 * @param window the window's key ({@code Window.key()}): {@code "main"} for the main window
 * @param ticket what the answer names; never reused
 * @param to     the qualified name of the part asked, which the wiring filters by; null in a request a test hands
 *               straight to a part's inbox, which no part the wiring built hears
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record CloseRequested(String window, long ticket, String to) {

    /** Keyed by the part asked, so each part hears only its own turn. */
    public static final Topic<CloseRequested> TOPIC = Topic.edge(CloseRequested.class, CloseRequested::to);

    /** The main window's key. Its close is the application's quit. */
    public static final String MAIN = "main";

    /** A request addressed to no part: what a test hands straight to the inbox of the part it tests. */
    public CloseRequested(String window, long ticket) {
        this(window, ticket, null);
    }

    /** Whether this is the main window's close, and so a quit. */
    public boolean main() {
        return MAIN.equals(window);
    }
}

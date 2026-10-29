package dev.vexelray.framework.shell;

import sibarum.atchung.Inbox;

/**
 * What the generated wiring calls to hand a component its inbox on the logic lane ({@link Logic}). An application
 * does not call it: a component asks for its inbox by taking an {@link Inbox} in its constructor, and the processor
 * writes the call, with the component's name.
 */
public final class Inboxes {

    private Inboxes() {
    }

    /** The inbox named {@code name} on {@code shell}'s logic lane, made the first time it is asked for. */
    public static Inbox of(Shell shell, String name) {
        return shell.inbox(name);
    }
}

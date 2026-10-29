package dev.vexelray.framework.shell;

import sibarum.atchung.Inbox;

import java.util.List;

/**
 * What the generated wiring calls to hand a component its inbox on the logic lane ({@link Logic}). An application
 * does not call it: a component asks for its inbox by taking an {@link Inbox} in its constructor, and the processor
 * writes the call, with the component's name and the topics its {@code @Hears} declares.
 */
public final class Inboxes {

    private Inboxes() {
    }

    /**
     * The inbox named {@code name} on {@code shell}'s logic lane, made the first time it is asked for. {@code hears} is
     * what the component declared it hears; when the lane starts, the inbox must be subscribed to exactly those, as
     * well as its ledgers' own topics, or the application does not start.
     */
    public static Inbox of(Shell shell, String name, String... hears) {
        return shell.inbox(name, List.of(hears));
    }
}

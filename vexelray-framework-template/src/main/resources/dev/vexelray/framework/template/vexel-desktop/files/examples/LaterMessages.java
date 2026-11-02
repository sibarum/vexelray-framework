package ${packageName}.core.examples.later;

import sibarum.atchung.Topic;

/**
 * What the <i>later</i> example's parts say to each other: the view in {@code -gui}'s {@code examples.later}, and
 * {@link Later} here. They are kept in the example's own package rather than in the editor's {@code Messages}, so that
 * deleting the example's folders takes its messages with it.
 */
public final class LaterMessages {

    private LaterMessages() {
    }

    /** A keystroke in the search field: its whole text after it. The view tells {@link Later}. */
    public static final Topic<Typed> TYPED_TOPIC = Topic.edge(Typed.class);

    public record Typed(String text) {
    }

    /**
     * The typing stopped for {@link Later#QUIET}: this is what to search for, and how many keystrokes the wait saved
     * a search for. {@link Later} tells the view.
     */
    public static final Topic<Settled> SETTLED_TOPIC = Topic.edge(Settled.class);

    public record Settled(String text, int keystrokes) {
    }

    /** The person asked to be reminded in {@link Later#REMINDER}. The view tells {@link Later}. */
    public static final Topic<RemindMe> REMIND_ME_TOPIC = Topic.edge(RemindMe.class);

    public record RemindMe() {
    }

    /** The person called off every reminder still waiting. The view tells {@link Later}. */
    public static final Topic<NeverMind> NEVER_MIND_TOPIC = Topic.edge(NeverMind.class);

    public record NeverMind() {
    }

    /** How many reminders are waiting now, and what just happened to one. {@link Later} tells the view. */
    public static final Topic<Reminders> REMINDERS_TOPIC = Topic.edge(Reminders.class);

    public record Reminders(int waiting, String news) {
    }
}

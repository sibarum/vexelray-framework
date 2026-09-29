package dev.vexelray.framework.api;

/**
 * What a publisher meets when a mailbox is full — the loss class of a channel, declared where the channel is.
 *
 * <p>Mirrors {@code atchung}'s {@code Backpressure} constant for constant, and the names are the contract: the
 * generated wiring maps each to it by name, and a test in {@code vexelray-framework-shell} fails if either side
 * gains or renames one. It is a type of its own because this module is JDK-only.
 *
 * <p>An <i>edge</i> — a keystroke, a command, a tree mutation — is {@link #FAIL}, or {@link #BLOCK} where the
 * publisher can safely wait. A <i>sample</i> — a pointer position, a window size — is
 * {@link #COALESCE_LATEST}, because the next one supersedes it. A channel carrying both cannot be given a
 * correct policy, and the processor rejects one subscribed with both (T4.1).
 */
@Stability(Stability.Level.EXPERIMENTAL)
public enum Overflow {

    /** Report the overflow and stop. The default: it needs no justification, and losing a message does. */
    FAIL(false, true),

    /** Drop the oldest queued message to make room. */
    DROP_OLDEST(false, false),

    /** Drop the message that does not fit. */
    DROP_NEWEST(false, false),

    /** Keep only the latest: a mailbox of one whose message is replaced. */
    COALESCE_LATEST(false, false),

    /**
     * Make the publisher wait for room. Legal only where the publisher can safely wait, which the main thread
     * never can: a blocking send from it to a wedged lane's full mailbox is how a window freezes (T4.7).
     */
    BLOCK(true, true);

    private final boolean blocks;
    private final boolean edge;

    Overflow(boolean blocks, boolean edge) {
        this.blocks = blocks;
        this.edge = edge;
    }

    /** Whether a publisher can wait on this policy. */
    public boolean blocks() {
        return blocks;
    }

    /** Whether this is an edge policy: one that never quietly loses a message. */
    public boolean edge() {
        return edge;
    }
}

package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says which channels this code sends on — the other end of {@link Subscribe}, and what makes a rule about
 * senders checkable at all.
 *
 * <p>The processor reads declarations, not method bodies, so a {@code bus.publish(...)} call is invisible to it.
 * This is how a sender is made visible: name the topics, on the type or on the method that sends. It is an
 * honest declaration and not a proof — a send that is not declared is not checked — which is the same trade
 * {@code docs/threading.md} T4.5 makes for the whole message graph: <i>an emergent graph cannot be checked; a
 * declared one can.</i>
 *
 * <p><b>What it buys today (T4.7).</b> Code on the main thread — a {@link MainThread} type, a
 * {@link MainThread} provider, a {@link BeforeFrame} hook — may not declare a send on a topic that any
 * {@link Subscribe} gives a {@link Overflow#BLOCK} mailbox, because a blocking send from the main thread to a
 * wedged lane's full mailbox is how a window freezes. That is a compile error naming both sides.
 *
 * <p>Matched by topic name across every {@code @Subscribe} in the compilation. A subscriber in another module
 * is not seen, so this cannot vouch for it.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Publishes {

    /** The names of the topics sent on. */
    String[] value();
}

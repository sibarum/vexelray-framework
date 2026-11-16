package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says which channels this code sends on — the other end of {@link Subscribe}, and what makes a rule about
 * senders checkable at all.
 *
 * <p>The message graph is built from declarations, so a send in a body has to be declared to be in it. This is how
 * such a sender is made visible: name the payload types it sends, on the type or on the method that sends. A payload
 * names its channel (T4.1), so these are class literals javac checks, never topic names. {@code docs/threading.md}
 * T4.5: <i>an emergent graph cannot be checked; a declared one can.</i>
 *
 * <p><b>Since 0.2, for a ledger's steps only.</b> A part sends by taking a {@code Sender<T>} in its constructor
 * (ROADMAP fork 5), which the processor reads without this. What a constructor cannot show is what a ledger's steps
 * send through their {@code Out}, so a component built on a ledger lists those topics here, and the graph the
 * Inspector draws ({@code BUS_GRAPH}) carries them. The processor reads the steps' bodies and holds them to the list:
 * a step's {@code out.send} on a payload not listed here is a compile error, and a payload listed that no step sends
 * is a warning when the steps are certainly the component's only way to send.
 *
 * <p>0.1 used it for T4.7, a blocking send from the main thread. Under the tick no send blocks, and no application
 * code runs on the main thread, so that check is gone.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Publishes {

    /** The payload types sent, each with its one declared topic. */
    Class<?>[] value();
}

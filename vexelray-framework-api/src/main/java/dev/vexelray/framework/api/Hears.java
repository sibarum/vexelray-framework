package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says which topics a component hears through its inbox: the other end of {@link Publishes}, for a component on the
 * logic lane.
 *
 * <p>A component that takes an atchung {@code Inbox} hears by subscribing it, typically through a ledger's
 * {@code Builder.on(topic, step)}, which is a call in a constructor and invisible to a processor that reads
 * declarations. This is the declaration, so the channel checks see it: a job's reply that nothing hears is still a
 * compile error, and the bus graph still shows who hears what. The wiring checks at startup that the inbox subscribed
 * to nothing this does not list, so the two cannot drift apart unnoticed.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Hears {

    /** The names of the topics heard. */
    String[] value();
}

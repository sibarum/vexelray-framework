package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says which topics a component hears through its inbox, by their payload types: the other end of {@link Publishes},
 * for a component that takes its {@code Inbox}. A payload names its channel (T4.1), so a class literal is a reference
 * javac checks, where a topic's name would be a string nobody could.
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

    /** The payload types of the topics heard, each with its one declared topic. */
    Class<?>[] value();
}

package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A mailbox on a {@link Component}, declared: this method receives {@link #topic}, on the component's lane, with
 * this loss policy.
 *
 * <p><b>The channel is on the declaration, because a processor reads declarations.</b> Until this existed a
 * mailbox was a {@code Placement.subscribe(...)} call in a method body, so the processor could not see which
 * channels existed, what each did when full, or who was on the far end. Everything in {@code docs/threading.md}
 * §4 that needs the message graph — one loss class per channel, no blocking send from the main thread, no
 * blocking cycle — was unreachable for that reason and nothing else.
 *
 * <p>The payload type is the method's parameter, so a topic is {@code (topic, type)} and cannot disagree with its
 * handler. The generated wiring registers the mailbox where it constructs the component, and the component's
 * placement starts it once every publisher exists; closing the placement closes the mailbox, so there is no
 * teardown to write.
 *
 * <p><b>Shape.</b> On a {@code @Component}; one parameter that is a class or interface with no type arguments;
 * {@code void}; visible from the application's package; and declaring no checked exception, since a delivery has
 * nowhere to send one. A component may have any number of these, and each is a mailbox of its own — which is how
 * T4.3 (a mailbox per loss class) is structural rather than remembered.
 *
 * <p><b>Defaults.</b> {@link Overflow#FAIL} and a capacity of 64: losing a message is a decision, and a decision
 * has to be written down. A {@code COALESCE_LATEST} mailbox holds one and ignores {@link #capacity}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Subscribe {

    /** The channel's name. The payload type comes from the method's parameter. */
    String topic();

    /** What a publisher meets when this mailbox is full. */
    Overflow overflow() default Overflow.FAIL;

    /** The mailbox bound. Ignored by {@link Overflow#COALESCE_LATEST}. */
    int capacity() default 64;
}

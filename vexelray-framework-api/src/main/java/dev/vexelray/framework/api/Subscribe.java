package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A mailbox on a {@link Component} or a {@link View}, declared: this method receives {@link #topic}, on the
 * component's lane (the logic lane, unless it declares one of its own) or in a view's frame.
 *
 * <p><b>The channel is on the declaration, because a processor reads declarations.</b> Until this existed a
 * mailbox was a {@code Placement.subscribe(...)} call in a method body, so the processor could not see which
 * channels existed or who was on the far end. Everything in {@code docs/threading.md} §4 that needs the message
 * graph was unreachable for that reason and nothing else.
 *
 * <p>The payload type is the method's parameter, so a topic is {@code (topic, type)} and cannot disagree with its
 * handler. The topic itself is the payload's declared constant, a {@code static final Topic} of the payload in the
 * payload's own type or the type enclosing it, as {@code Messages} and the widgets declare theirs; its name must be
 * {@link #topic}, and it says whether the channel is state or edge (T4.1). The generated wiring subscribes the mailbox
 * where it constructs the component, and the framework starts it once every publisher exists; there is no teardown to
 * write.
 *
 * <p><b>Shape.</b> One parameter that is a class or interface with no type arguments; {@code void}; visible from the
 * application's package; and declaring no checked exception, since a delivery has nowhere to send one. A component
 * may have any number of these, and each is a mailbox of its own (T4.3).
 *
 * <p><b>No loss policy</b>, since 0.2: the topic's kind is the policy (T4.2). An edge is every message, in order, and
 * one more than {@link #capacity} is a fault naming the topic; a state topic holds the last write per key and cannot
 * overflow. 0.1's {@code overflow} is gone with the choices it offered, {@code BLOCK} (T4.4) and the ones that lose an
 * edge quietly.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Subscribe {

    /** The channel's name. The payload type comes from the method's parameter. */
    String topic();

    /** How many edges may wait in this mailbox at once. A state topic holds one message per key and ignores it. */
    int capacity() default 64;

    /**
     * The keys of a state topic this mailbox is handed, and the only ones (T4.8: the author's "subscribe to any desired
     * event streams with a filter"). Empty for every key. A key filter is a declaration, never a predicate, so the
     * processor can see it, and the wiring does the filtering, so a component is never handed a message it would
     * discard. Keys are compared as strings, which is what a widget's address is ({@code "main.path"}); only a state
     * topic has keys, so a filter on an edge does not start.
     */
    String[] key() default {};
}

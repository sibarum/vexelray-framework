package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An actor: a class with state of its own and a mailbox, constructed once, before the loop runs, by its one
 * constructor, and reached only by messages ({@code docs/threading.md} §0).
 *
 * <p><b>Not a container-managed singleton</b> — that is {@link Provides}. A component owns its state, hears what it
 * subscribes to, and sends what it declares; nothing holds it. Everything that has one is constructed before the loop
 * runs, which is what makes placement static, and static placement is the simplification the whole concurrency model
 * rests on ({@code docs/architecture.md}, <i>the vocabulary, decided before the processor emits anything</i>). A
 * worker that allocates resources and takes no mailbox is not a component, and neither is anything built per window.
 *
 * <p><b>Every component is on the logic lane</b> (T1.6). The framework drains every component's mailboxes in one
 * <i>tick</i>, in rounds, in one order fixed by the wiring, on one thread that is not the main thread, so no two
 * components ever run at once and none needs to think about memory visibility. A component that declares a lane of
 * its own ({@code lane = "physics"}) has its share of each round delivered on a thread of its own,
 * {@code vexel-component-<lane>} in a thread dump, in the same rounds as the rest: a core for heavy work, and never a
 * way to block. Blocking work is a {@link Job}. Where the ticks run, their own thread or the main thread
 * ({@code --logic=main}), is a launch setting no component can see (T1.7).
 *
 * <p><b>A component never holds another</b> (T2.3), on one lane or on two: two components talk by messages whether or
 * not they share a thread, so moving one to a lane of its own costs capacity and never behaviour. A constructor
 * parameter naming a component is a compile error.
 *
 * <p><b>What it hears and what it sends are on its declaration.</b> It hears through its {@link Subscribe} methods,
 * or, for a component built on a ledger, through the {@code Inbox} it takes and the topics it lists in {@link Hears}.
 * It sends through the {@code Sender<T>} it takes for each payload it sends (ROADMAP fork 5), which the wiring builds
 * on {@code T}'s declared topic; it is never handed the bus.
 *
 * <p><b>State that asks for no synchronisation</b> (T2.7). A field may not be {@code volatile}, an {@code Atomic*},
 * anything from {@code java.util.concurrent} or a functional interface: each is evidence that something other than the
 * component's own deliveries touches it. A provided value it takes is deeply immutable or a framework service (T2.6),
 * and never a main-thread value (T2.2); publish to the main thread's parts instead.
 *
 * <p><b>One constructor, and no annotation on it.</b> A class with two constructors is ambiguous to a reader as
 * well as to a processor, and the {@code @Inject} of other containers exists only to break a tie that is better
 * not created. A component with more than one non-private constructor is rejected rather than resolved by picking
 * one.
 *
 * <p><b>Its phase is inferred, never declared.</b> A component taking a {@code Gui} cannot exist before the tree
 * does; one taking only a settings record can be built first. That is a fact about its parameters, so it is read off
 * them: a component's phase is the latest phase of anything it depends on. There is no {@code @InPhase} to get wrong,
 * and no ordering to maintain by hand as the dependencies change underneath it. See {@code Phase} in
 * {@code vexelray-framework-core}, whose own note is worth reading beside this one.
 *
 * <p>If the component holds a resource, implement {@link AutoCloseable}: it is closed in reverse construction
 * order at shutdown, after the last tick has delivered what was queued.
 *
 * <p><b>Checked and generated.</b> {@code vexelray-framework-processor} holds every rule above that a declaration
 * can decide, and the generated wiring constructs each component in its inferred phase, subscribes its mailboxes on
 * its inbox, and builds its senders; the framework starts the tick once everything is built. See
 * {@link dev.vexelray.framework.api} for which half is built.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Component {

    /**
     * The lane a component that names none runs on: the logic lane, shared by every such component.
     *
     * <p>Reserved by construction rather than by a list of forbidden words. An explicit lane must be a name —
     * a letter, then letters, digits, {@code .}, {@code _} or {@code -} — and this value is not one, so no
     * application string can ever be it by accident.
     */
    String DEFAULT_LANE = "<default>";

    /**
     * The lane this component's share of each tick is delivered on: a thread of its own, named
     * {@code vexel-component-<lane>}, that meets the logic lane at every round's end. Components on one declared lane
     * share its thread.
     *
     * <p>Optional, and usually left out: leaving it out is {@link #DEFAULT_LANE}, the logic lane. Naming one is for a
     * component whose work is heavy enough to want a core of its own (T1.6).
     */
    String lane() default DEFAULT_LANE;
}

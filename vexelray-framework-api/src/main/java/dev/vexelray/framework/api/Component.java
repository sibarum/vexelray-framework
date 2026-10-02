package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An actor: a class with a thread and a mailbox, constructed once, before the loop runs, by its one constructor.
 *
 * <p><b>Not a container-managed singleton</b> — that is {@link Provides}. A component owns a platform thread, has
 * an inbox, and is reached by publishing rather than by holding. Everything that has one is constructed before
 * the loop runs, which is what makes placement static, and static placement is the simplification the whole
 * concurrency model rests on ({@code docs/architecture.md}, <i>the vocabulary, decided before the processor emits
 * anything</i>). A worker that allocates resources and takes no mailbox is not a component, and neither is
 * anything built per window.
 *
 * <p><b>Its lane is declared, because a processor reads declarations.</b> a hand-written {@code shell.place("compose")} decided a
 * placement once and never changes it afterwards, but it is a call in a method body, and the colour rule cannot be
 * checked against a body. So the lane is on the declaration, and it is the thread the component runs on —
 * {@code vexel-component-<lane>} in a thread dump. <b>Components sharing a lane are the only ones that may hold
 * each other</b>; a constructor parameter naming a component on another lane is a compile error naming both
 * lanes (T2.3). The lane is optional: a component that names none is on {@link #DEFAULT_LANE}, one shared thread
 * that is not the main thread. That is a ruled default and therefore a contract — moving it later would change
 * timing and blocking with no compile error — and it is what keeps a plain service from costing a thread. What it
 * costs is that one slow component holds up the others on it, so isolating one is {@code lane = "..."}.
 *
 * <p>A lane is a string, and that is safe here in a way it would not be for the main thread. The only thing two
 * spellings agreeing permits is a direct reference, so a misspelled lane can deny that and never grant it — the
 * error it produces is the T2.3 one, naming both spellings side by side. It is also why the main thread is not
 * {@code lane = "main"}: there, a misspelling would decide what may touch Vulkan.
 *
 * <p><b>Never {@link MainThread}.</b> A component owns a thread of its own, and a value has exactly one colour, so
 * both on one type is a compile error (T2.1). For the same reason a component's constructor may not take a
 * main-thread value (T2.2): publish to it instead of holding it.
 *
 * <p><b>One constructor, and no annotation on it.</b> A class with two constructors is ambiguous to a reader as
 * well as to a processor, and the {@code @Inject} of other containers exists only to break a tie that is better
 * not created. A component with more than one non-private constructor is rejected rather than resolved by picking
 * one.
 *
 * <p><b>Its phase is inferred, never declared.</b> A component taking a {@code GuiApp} cannot exist before the
 * window does; one taking only a settings record can be built first. That is a fact about its parameters, so it
 * will be read off them — a component's phase is the latest phase of anything it depends on. There is no
 * {@code @InPhase} to get wrong, and no ordering to maintain by hand as the dependencies change underneath it.
 * See {@code Phase} in {@code vexelray-framework-core}, whose own note is worth reading beside this one.
 *
 * <p>If the component holds a resource, implement {@link AutoCloseable}: it is closed in reverse construction
 * order at shutdown, after its thread has drained and stopped.
 *
 * <p><b>Checked and generated.</b> {@code vexelray-framework-processor} holds every rule above that a
 * declaration can decide, and the generated wiring constructs each component in its inferred phase.
 * A component does not take a {@code Placement} (that is a compile error): its mailboxes are its
 * {@link Subscribe} methods, the generated wiring places it on its lane and registers them, and the framework
 * starts it once everything is built. See {@link dev.vexelray.framework.api} for which half is built.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Component {

    /**
     * The lane a component that names none runs on: one thread, shared by all of them, and not the main thread.
     *
     * <p>Reserved by construction rather than by a list of forbidden words. An explicit lane must be a name —
     * a letter, then letters, digits, {@code .}, {@code _} or {@code -} — and this value is not one, so no
     * application string can ever be it by accident.
     */
    String DEFAULT_LANE = "<default>";

    /**
     * The lane this component runs on — its thread, named {@code vexel-component-<lane>}. Components on the same
     * lane share a thread and may hold each other; on different lanes, they reach each other by publishing.
     *
     * <p>Optional: leaving it out is {@link #DEFAULT_LANE}, and every component that does shares that one thread.
     * Naming a lane is how one component is isolated from the rest.
     */
    String lane() default DEFAULT_LANE;
}

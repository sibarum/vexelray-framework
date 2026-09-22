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
 * <p><b>Its lane is declared, because a processor reads declarations.</b> {@code shell.place("compose")} decides a
 * placement once and never changes it afterwards, but it is a call in a method body, and the colour rule cannot be
 * checked against a body. So the lane is on the declaration, and it is the thread the component runs on —
 * {@code vexel-component-<lane>} in a thread dump. <b>Components sharing a lane are the only ones that may hold
 * each other</b>; a constructor parameter naming a component on another lane is a compile error naming both
 * lanes (T2.3). The lane is required rather than defaulted: which thread a component runs on is a decision, and
 * <i>"a default is not a choice anyone can read."</i>
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
 * <p><b>Checked, not yet generated.</b> {@code vexelray-framework-processor} holds every rule above that a
 * declaration can decide; nothing constructs a component from this annotation yet, and {@code Shell.place} is
 * still how a wiring puts one on its thread. See {@link dev.vexelray.framework.api} for which half is built.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Component {

    /**
     * The lane this component runs on — its thread, named {@code vexel-component-<lane>}. Components on the same
     * lane share a thread and may hold each other; on different lanes, they reach each other by publishing.
     */
    String lane();
}

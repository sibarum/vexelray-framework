package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A container-managed singleton, constructed once, by its one constructor, with its parameters supplied.
 *
 * <p><b>One constructor, and no annotation on it.</b> A class with two constructors is ambiguous to a reader as
 * well as to a processor, and the {@code @Inject} of other containers exists only to break a tie that is better
 * not created. The processor rejects a component with more than one non-private constructor rather than picking
 * one.
 *
 * <p><b>Its phase is inferred, never declared.</b> A component taking a {@code GuiApp} cannot exist before the
 * window does; one taking only a settings record can be built first. That is a fact about its parameters, so
 * the processor reads it off them — a component's phase is the latest phase of anything it depends on. There is
 * no {@code @InPhase} to get wrong, and no ordering to maintain by hand as the dependencies change underneath
 * it. See {@code Phase} in {@code vexelray-framework-core}.
 *
 * <p>If the component holds a resource, implement {@link AutoCloseable}: it is closed in reverse construction
 * order at shutdown, which is what the nested try-with-resources at a hand-written application edge was for.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Component {
}

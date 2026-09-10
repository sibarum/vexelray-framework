package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Contribute this only if every named type is on the compile classpath. The whole of auto-configuration, moved
 * to build time.
 *
 * <p>This is Spring Boot's {@code @ConditionalOnClass} with the mechanism replaced. Spring Boot evaluates it by
 * asking {@code Class.forName} at startup, which is a runtime classpath query — the single most native-image
 * hostile thing a framework can do, because the classpath is exactly what does not exist any more inside a
 * native binary, and because the query costs startup time on every launch to answer a question that was already
 * settled when the application was built.
 *
 * <p>Here the processor will answer it with {@code Elements.getTypeElement(name) != null} while compiling, and
 * then emit the branch or not. The consequences are worth stating plainly:
 *
 * <ul>
 *   <li><b>Nothing is evaluated at runtime.</b> A guard that failed produces no code at all — not a
 *       {@code false} branch, not a dead class, not a metadata entry.</li>
 *   <li><b>Types are named as strings on purpose.</b> A guard that imported the type it is guarding would
 *       require the type to be present in order to ask whether the type is present.</li>
 *   <li><b>The answer is auditable.</b> Which guards passed is in the generated source, on disk, next to the
 *       wiring — rather than in a startup report an application has to be run to produce.</li>
 * </ul>
 *
 * <p>The cost is the honest one: the decision is frozen at the application's build, so adding a jar to the
 * runtime classpath contributes nothing until the application is recompiled. For a framework whose output is a
 * single native binary, that is not a cost at all.
 *
 * <p><b>Inert.</b> Nothing reads this annotation yet; the above is its specification. See
 * {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface ConditionalOnType {

    /**
     * Fully-qualified type names that must all be present, e.g.
     * {@code "sibarum.tactroller.clipboard.Clipboard"}.
     */
    String[] value();
}

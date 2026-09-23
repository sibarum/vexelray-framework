package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Provides} method as the framework's answer <em>unless the application has one of its own</em>.
 *
 * <p>This is the whole of auto-configuration's back-off rule, and it is stated from the framework's side rather
 * than the application's. Spring Boot writes the same rule as {@code @ConditionalOnMissingBean} on the
 * auto-configuration — a condition evaluated against a bean registry as it is being populated, which is why
 * ordering between auto-configurations is something Spring Boot users end up having to know about. A processor
 * has every provider for a type in front of it at once: exactly one non-default wins, and if there is none,
 * exactly one default does. There is no order for anything to depend on.
 *
 * <p>An application overriding a default writes no annotation and reads no documentation about precedence — it
 * writes a {@code @Provides} method returning that type, and the framework's stops being generated. Two
 * non-default providers for the same type will be a compile error naming both, because at that point the
 * application is disagreeing with itself and the framework has no business picking a winner.
 *
 * <p>The rule is asked once per {@link RunMode}, because {@link OnMode} makes it a different question in each: a
 * non-default that exists only while windowed backs a default off only while windowed.
 *
 * <p><b>Checked and generated.</b> {@code vexelray-framework-processor} holds the back-off as a conflict check —
 * two winners for one type in one mode is a compile error naming both — and a {@code @Default} on anything but a
 * provider is one too. The generated wiring calls the winner, under an {@code if} on the run mode where the winner
 * differs by mode. See {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Default {
}

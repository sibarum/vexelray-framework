package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Holds {@link Provides} methods: the way a type the application does not own gets into the container.
 *
 * <p>{@code Tactroller}, {@code Clipboard} and {@code Settings} cannot carry a {@link Component} annotation —
 * they live in other repos and should not learn about this one. A configuration class is where the recipe for
 * one of them lives.
 *
 * <p><b>No proxying, and so no {@code proxyBeanMethods} to reason about.</b> Spring has to intercept calls
 * between {@code @Bean} methods because a second call would build a second instance; here the generated wiring
 * calls each method exactly once and passes the result on by reference, which is what a reader naively expects
 * and what a hand-written {@code main} already did. A {@code @Provides} method calling another one directly
 * will be a compile error rather than a silent second instance — ask for it as a parameter instead.
 *
 * <p>This is also the unit of auto-configuration. A starter is a configuration class, guarded by
 * {@link ConditionalOnType} where it touches something optional, that an application names in
 * {@link VexelApp#starters()}. <b>Named, not found</b> — see that method for why a framework that discovers its
 * starters has to walk the classpath to do it, and what the class literal buys instead.
 *
 * <p><b>Checked and generated.</b> {@code vexelray-framework-processor} collects the {@link Provides} methods here
 * and on every starter, checks them, and the generated wiring constructs this class once — so it needs a
 * non-private constructor taking nothing, and a starter's is public — and calls each winning provider once. One
 * promise above is not kept: a {@code @Provides} calling another directly is a method body, and the processor
 * reads declarations. See {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Configuration {
}

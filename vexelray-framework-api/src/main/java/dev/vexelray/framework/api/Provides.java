package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A factory method for one container-managed value, inside a {@link Configuration} class.
 *
 * <p>The method's return type is what it provides; its parameters are what it needs, resolved like any
 * component's. It is called once.
 *
 * <p><b>Returning {@code null} is a supported answer, which is why an absent backend is not an error.</b> The
 * stack this wires is full of optional ones: {@code Tactroller.open()} throws where there is no input backend
 * and {@code Clipboard.open()} where there is no clipboard, and the hand-written application edge answers both
 * by catching and returning {@code null}, on the stated grounds that "a window nobody can click is a degraded
 * window rather than a failed launch". A {@code null} here means <em>absent</em>: the value is never closed,
 * dependents that tolerate absence still build, and the framework reports it once rather than each provider
 * printing its own apology.
 *
 * <p>A provider for a type the framework also supplies wins over it silently, provided the framework's is
 * marked {@link Default}. Two non-default providers for one type will be a compile error.
 *
 * <p><b>It returns an interface, when the type is this build's own.</b> Generated code can swap what it constructs
 * without touching a call site only if the call sites were written against something that can have a second
 * implementation. A type that arrives as a class file from another jar — {@code Tactroller}, {@code Clipboard},
 * {@code Settings}, all {@code final} — cannot be given one by the application, and those are exactly the types
 * {@link Configuration} exists for, so the rule is kept where it can be. A record, a primitive or an array is a
 * value, and a value is not the container's business.
 *
 * <p>A provider never takes a {@link Component}: a value holding one could be injected anywhere, carrying the
 * component across lanes without a check seeing it (T3.7).
 *
 * <p><b>Package-private classes of the application's own are exempt</b> from the interface rule, for the same
 * reason: nothing outside the package can hold a call site against one, so swapping it is always the
 * application's own edit. A record is refused whatever its visibility — that objection is that it is a value.
 *
 * <p><b>Checked and generated.</b> {@code vexelray-framework-processor} holds every rule above that a declaration
 * can decide, and the generated wiring calls each winning provider once, in the phase its parameters put it in. A
 * parameter nothing supplies — not a framework value, a {@link Setting}, or another provider's type — is a compile
 * error naming it. See {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Provides {
}

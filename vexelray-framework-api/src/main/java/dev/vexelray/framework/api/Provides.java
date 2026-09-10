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
 * <p><b>Inert.</b> Nothing reads this annotation yet; the above is its specification. See
 * {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Provides {
}

package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This value may only be touched on the main thread.
 *
 * <p>The constraint is not the framework's invention. {@code vexelray-gui/CLAUDE.md} states it as a fact about
 * the stack — <i>"Vulkan, the window and present stay on the main thread"</i> — and lists it under constraints
 * that are <i>not visible in the code</i>. That is the problem this annotation exists to fix: it was true,
 * load-bearing, and enforced by nothing but the reader's memory.
 *
 * <p>What the processor will check is one rule, in one direction: <b>a main-thread value may not be injected
 * into anything that is not itself main-thread.</b> A worker-safe component asking for a {@code GuiApp} becomes
 * a compile error naming both types, rather than a Vulkan call from a worker thread that happens to survive
 * testing on one driver. Nothing is checked at runtime, so the annotation costs the binary nothing — and
 * nothing is checked at compile time either until the processor is written, so today the rule is exactly what
 * it was before this annotation existed: true, load-bearing, and enforced by the reader.
 *
 * <p>The inverse is deliberately allowed: a main-thread component may depend on worker-safe values freely.
 * Handing the render thread an immutable model or a settings record is not a violation of anything, and
 * requiring an annotation for it would put {@code @MainThread} on most of an application.
 *
 * <p>Applied to a type, it describes every instance. Applied to a {@link Provides} method, it describes that
 * one value — which is how a type from another repo, with no annotation of its own, gets the same protection.
 *
 * <p><b>Inert.</b> Nothing reads this annotation yet; the above is its specification. See
 * {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface MainThread {
}

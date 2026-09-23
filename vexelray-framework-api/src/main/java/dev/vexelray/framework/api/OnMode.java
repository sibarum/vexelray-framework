package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Build this component, or call this {@link Provides} method, only in the listed {@link RunMode}s.
 *
 * <p>Unlike {@link ConditionalOnType}, which is settled while compiling and compiled away, this is a runtime
 * branch: the mode comes from {@code args}, so the generated wiring contains an {@code if}. That is a branch
 * taken once at startup, not reflection, and it keeps the useful property — a value guarded to
 * {@link RunMode#WINDOWED} is never constructed during a capture, so a headless machine never opens the backend
 * it does not have.
 *
 * <p>A component whose dependency is absent in the current mode is itself absent in that mode; the processor
 * will work that out and say so at the one place it can be read — the build — rather than leaving a null to be
 * discovered a phase later.
 *
 * <p><b>Checked and generated.</b> {@code vexelray-framework-processor} asks the conflict question once per mode,
 * narrows each part to the modes everything it takes exists in — saying so, as a note at the build — and makes a
 * part that exists in no mode at all a compile error. The generated wiring carries the {@code if}. See
 * {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface OnMode {

    /** The modes this exists in. Empty means every mode, which is what leaving the annotation off means too. */
    RunMode[] value();
}

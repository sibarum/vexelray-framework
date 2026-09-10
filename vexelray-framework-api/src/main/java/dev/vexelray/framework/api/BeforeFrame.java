package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Calls this no-argument method once per frame, in the given {@link FrameStage}, before the tree is reconciled.
 *
 * <p>Generated as a direct call from an array walked in stage order — no listener list, no iterator, no
 * megamorphic dispatch, and nothing allocated per frame. This is the difference that matters between a frame
 * hook and a Spring application event: an event published sixty times a second through a reflective multicaster
 * would be the most expensive thing in the loop.
 *
 * <p><b>The method must not throw.</b> A hook that throws takes the frame loop down with it, and the loop is
 * the application. Where the stack's own operations can fail transiently the hand-written edge already answers
 * this way — a failed input poll <i>"drops this frame's input rather than tears down the loop"</i> — and a
 * per-frame hook is the last place a stack trace does anyone any good, because it will arrive sixty times
 * before it is read.
 *
 * <p><b>Inert.</b> Nothing reads this annotation yet; the above is its specification. See
 * {@link dev.vexelray.framework.api} for which half of this package is built. {@code FrameHooks} is the part
 * that does exist — the array this would generate a call into, filled today by {@code Shell.hooks().add}.
 *
 * @see FrameStage for why the stage is a named position rather than an integer priority
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface BeforeFrame {

    /** Which stage of the frame this runs in. {@link FrameStage#APP} unless there is a reason otherwise. */
    FrameStage value() default FrameStage.APP;
}

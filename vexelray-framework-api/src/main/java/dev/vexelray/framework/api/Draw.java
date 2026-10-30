package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link View}'s asked half (T7.6, vexelray-gui's {@code docs/plans/frame-contract.md}): the function the GUI thread
 * calls on every frame the window draws, to choose what the view's nodes show at the frame's present time.
 *
 * <pre>{@code
 * @View
 * public final class Timeline {
 *     public Strip state = Strip.AT_REST;                  // the view's one state, replaced by its @Subscribe methods
 *     ...
 *     @Draw
 *     public static long draw(Strip s, Frame frame, Drawn out) {
 *         out.offset(s.rows(), 0f, s.offsetAt(frame.presentNanos()));
 *         return s.settled(frame.presentNanos()) ? Frame.NEVER : frame.presentNanos();
 *     }
 * }
 * }</pre>
 *
 * <p><b>Told builds, asked chooses.</b> The view's {@code @Subscribe} methods are its told half: they build what is
 * costly (the nodes, a picture) and replace its state with a new value. This method is the asked half: it reads that
 * state, the {@code Frame} and nothing else, sets numbers on the nodes the told half made through the {@code Drawn},
 * and answers when it next needs asking, {@code Frame.NEVER} once it is at rest. A view that does not move has none.
 *
 * <p><b>The shape, checked by the processor:</b> one per view; {@code public static long}, taking the state,
 * vexelray-gui's {@code dev.vexelray.gui.core.Frame} and {@code dev.vexelray.gui.core.Drawn}, in that order. The state
 * is a deeply immutable type (a record, as a payload is), and the view holds it in exactly one instance field of that
 * type, which the generated wiring reads on every frame; the field is the view's to replace, never to change in place.
 *
 * <p><b>The body, checked by the processor</b> as a ledger's step is and more, since it runs in every frame: it
 * captures nothing, writes no static, takes no lock, makes no effect, and allocates nothing: no {@code new}, no array,
 * no string built, no varargs call, no logging, no {@code Thread}, nothing of the bus. Like the step check, it refuses
 * only what is certainly wrong, and lets through a call it cannot read.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Draw {
}

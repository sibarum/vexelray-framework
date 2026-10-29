package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A view component: an application's own GUI element, which runs on the GUI thread ({@code docs/threading.md}, T7.4).
 *
 * <p>Something has to turn "the editor opened a file" into "add a tab holding a text field", and that touches widgets.
 * A view is where that happens. It is built like a {@link Component}, from its one constructor, and may take the
 * {@code Gui}, its widgets and anything shareable. Its {@link Subscribe} methods are not mailboxes on a lane of its
 * own: they are drained on the GUI thread in each frame's widget tick, after input is dispatched and before layout,
 * so a view hears what a widget did in the frame the input arrived, and what it writes is drawn in that frame.
 *
 * <p><b>Presentation only.</b> A view shows what the logic decided and tells the logic what the person did, by
 * messages. It decides nothing that belongs to the application's logic, takes no {@link Job}, no inbox and no
 * component on another lane, and nothing that is not a view may take it: the processor refuses each. Its time is
 * inside the frame budget, so a view that blocks is a frozen window, which the main thread's watchdog reports.
 *
 * <p>A widget's own messages heard by a view are not crossings (the widget publishes them on the same thread), so
 * T2.4 does not check their types; anything else a view hears, a controller's message say, is checked as any
 * payload is.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface View {

    /** The lane name a view's {@code @Subscribe} methods are drained on: the GUI thread's widget tick. Reserved. */
    String LANE = "<gui>";
}

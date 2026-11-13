package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>The look the application is in</b>, said at start and each time the person chooses the other one in the app
 * menu (Theme, then Light or Dark), for an application that declared both ({@code Appearance.themes}).
 *
 * <p><b>Most applications never hear this.</b> Everything that names its colour (a node's {@code Role}, a span's, a
 * picture mark's, a cue's, an icon's tint) is drawn in whichever look is in force and fades to the new one with nothing
 * run. This is the control for an application that wants to do more when the look shifts: something it worked out
 * from the look and keeps (a chart rendered off screen, a colour handed to a library that takes only colours), or a
 * shift of its own made to go with it (an image swapped for its light version).
 *
 * <p>State: a part hears the last word when it subscribes, so one that starts late still knows which look is in force.
 * The theme itself is not carried, because a message here is heard by an application's {@code -core}, which builds
 * against the JDK and the bus alone; a view asks its tree for the theme ({@code gui.theme()}) when it hears this.
 *
 * {@snippet :
 * // a view's mailbox: draw the chart again in the look now in force
 * @Subscribe
 * void shifted(ThemeShifted look) {
 *     chart.picture(render(gui.theme()));
 * }
 * }
 *
 * @param light the light look is in force; false for the dark one
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record ThemeShifted(boolean light) {

    public static final Topic<ThemeShifted> TOPIC = Topic.state(ThemeShifted.class);
}

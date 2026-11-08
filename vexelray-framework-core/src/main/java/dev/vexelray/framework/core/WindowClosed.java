package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>A window was destroyed</b>; its tree is still there, for a later {@link WindowCommand#show}. Each time it closes.
 * The main window's once, when the frame loop has ended, before anything is closed: a part that hears it on a lane
 * hears it only if that lane is still delivering, so it is a notice and not a place to save work, which belongs to the
 * close gate ({@link CloseRequested}).
 *
 * @param window the window's key
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record WindowClosed(String window) {

    public static final Topic<WindowClosed> TOPIC = Topic.edge(WindowClosed.class);
}

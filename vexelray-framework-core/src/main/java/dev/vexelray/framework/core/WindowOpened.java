package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>A window was created</b>, placed where it was left with its zoom restored: for a part that works only while its
 * window is up, such as a viewer reading full-size pictures. Each time it opens; the main window's once, when it is
 * claimed at {@code ATTACH}. Its counterpart is {@link WindowClosed}.
 *
 * @param window the window's key
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record WindowOpened(String window) {

    public static final Topic<WindowOpened> TOPIC = Topic.edge(WindowOpened.class);
}

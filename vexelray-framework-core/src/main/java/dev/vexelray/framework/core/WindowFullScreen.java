package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>Whether a window is full screen</b>, as a {@link WindowCommand} left it: said each time one of the full-screen
 * commands has been carried out, so a part that asks for full screen can tell what it got, and a key that means
 * "leave full screen, or else close" can tell which. State: the last word per window wins.
 *
 * @param window the window's key
 * @param on     whether it is full screen now
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record WindowFullScreen(String window, boolean on) {

    public static final Topic<WindowFullScreen> TOPIC = Topic.state(WindowFullScreen.class);

    /** Whether this is the main window. */
    public boolean main() {
        return CloseRequested.MAIN.equals(window);
    }
}

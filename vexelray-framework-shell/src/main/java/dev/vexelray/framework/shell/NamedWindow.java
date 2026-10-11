package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.gui.core.app.Standing;
import dev.vexelray.gui.core.style.Theme;

/**
 * A window this application opens besides its main one, described before it exists: what it is called, what its
 * caption says, how large it opens the first time, where it stands, and the look it wears. Handed to
 * {@link Windows#named(NamedWindow)}, which builds its tree at once and its window later.
 *
 * <p><b>All of it at the declaration, and the theme is why.</b> The framework builds the window's {@code Gui} and its
 * title bar the moment the window is declared, so the tree can be built beside the main one, headlessly; and a role
 * resolves when a widget writes a prop, so a theme given after the bar was built would reach everything except the
 * bar. The rest could arrive later and does not, so there is one place a window is described rather than two.
 *
 * @param key      the name the window is known by: in the settings file, where its placement and zoom are
 *                 remembered, and on the automation socket, where {@code window <key>} chooses it. Not
 *                 {@code "main"}, which is the main window's
 * @param title    what its caption says
 * @param width    how wide it opens when nothing is remembered, in pixels
 * @param height   how tall, on the same terms
 * @param standing where it stands relative to the main window. {@link Standing#PEER}, its own place in the stack and
 *                 the taskbar, unless this says otherwise; {@link Standing#SATELLITE} for a window that belongs to
 *                 the main one and is minimized with it
 * @param theme    its look, or {@code null} for the application's. A window with a look of its own is a real case
 *                 (the text editor's file drawer is a different hue on purpose); see {@link Appearance#applyTo}
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record NamedWindow(String key, String title, int width, int height, Standing standing, Theme theme) {

    public NamedWindow {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a window needs a name");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window '" + key + "' is " + width + "x" + height
                    + "; a window has to have some size to open at");
        }
        if (title == null) {
            title = "";
        }
        if (standing == null) {
            standing = Standing.PEER;
        }
    }

    /** A window beside the main one, in the application's look. */
    public static NamedWindow of(String key, String title, int width, int height) {
        return new NamedWindow(key, title, width, height, null, null);
    }

    /** The same, standing as this says. */
    public NamedWindow standing(Standing standing) {
        return new NamedWindow(key, title, width, height, standing, theme);
    }

    /** The same, belonging to the main window: above it, without a taskbar button, minimized with it. */
    public NamedWindow satellite() {
        return standing(Standing.SATELLITE);
    }

    /** The same, in a look of its own rather than the application's. */
    public NamedWindow theme(Theme theme) {
        return new NamedWindow(key, title, width, height, standing, theme);
    }
}

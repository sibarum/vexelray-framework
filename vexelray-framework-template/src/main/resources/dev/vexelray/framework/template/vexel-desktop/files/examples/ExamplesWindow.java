package ${packageName}.examples;

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;

/**
 * The Examples window, as an example sees it: the window its part is built into. {@link ExamplesPage} lays the parts
 * out, taking every {@link Example} there is; an example never adds itself, so nothing here names one.
 *
 * <p>An interface because a recipe in a package of its own returns one: the wiring is generated in the application's
 * top package, and the processor holds a recipe that returns a concrete class from elsewhere to be a call site the
 * wiring could not swap. ({@code Recipes} returns its own classes as they are only because they are package-private,
 * which nothing outside the package can reach.)
 */
public interface ExamplesWindow {

    /** The window's name: in the settings file, where its place is remembered, and to {@code ottermate} ({@code examples/...}). */
    String KEY = "examples";

    /** What a script may refer to in this window, as {@code examples/<landmark>}. */
    String LIST = "examples.list";
    String QUESTION = "examples.question";
    String ABOUT = "examples.about";

    /** The window, declared by name in {@link Examples}. */
    Window window();

    /** This window's own {@code Gui}, which an example builds its part into. */
    default Gui gui() {
        return window().gui();
    }
}

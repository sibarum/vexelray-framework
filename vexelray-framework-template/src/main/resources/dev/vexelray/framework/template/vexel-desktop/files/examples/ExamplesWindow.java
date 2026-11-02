package ${packageName}.examples;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.widget.ListView;

/**
 * The Examples window, as the rest of the application sees it: a {@code Gui} an example builds its part into, and a
 * list it {@link #add adds} that part to. {@link ExamplesPage} is the window itself.
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

    /** This window's own {@code Gui}, which an example builds its part into. */
    Gui gui();

    /**
     * Put an example in the list, sorted by its question, with its part hidden until it is chosen. The first one added
     * is shown, so the window never opens on an empty page.
     */
    void add(Example example);

    /** The list's selection moved: show the example chosen, when the selection is this window's list's and one. */
    void chosen(ListView.Selected selection);
}

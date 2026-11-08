package ${packageName}.examples;

import dev.vexelray.gui.widget.ListView;

/**
 * The Examples window's page, as its view sees it: the list of examples, which shows the one chosen. {@link ExamplesPage}
 * is the page itself. An interface for the reason {@link ExamplesWindow} gives.
 */
public interface ExamplesList {

    /** The list's selection moved: show the example chosen, when the selection is this page's list's and one. */
    void chosen(ListView.Selected selection);
}

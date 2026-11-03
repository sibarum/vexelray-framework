package ${packageName}.examples.later;

import ${packageName}.core.examples.later.LaterMessages.Reminders;
import ${packageName}.core.examples.later.LaterMessages.Settled;
import ${packageName}.examples.Example;
import dev.vexelray.gui.widget.TextField;

/**
 * The <i>later</i> example's part of the Examples window, as its view sees it: which widget a message is about, and
 * what to show. {@link LaterNodes} is the part itself. An interface for the reason {@code ExamplesWindow} gives.
 */
public interface LaterPanel {

    /** What a script may refer to, as {@code examples/<landmark>}. */
    String SEARCH = "later.search";
    String SEARCHED = "later.searched";
    String REMIND = "later.remind";
    String NEVER_MIND = "later.never-mind";
    String REMINDERS = "later.reminders";

    /** This example as the Examples window lists and shows it. */
    Example example();

    /** The search field, which a test beside the wiring types into as a person would. */
    TextField searchField();

    /** The address each widget's messages carry. */
    String searchAddress();

    String remindAddress();

    String neverMindAddress();

    /** The typing stopped, and this was searched for. */
    void searched(Settled settled);

    /** What the reminders are doing now. */
    void reminders(Reminders reminders);
}

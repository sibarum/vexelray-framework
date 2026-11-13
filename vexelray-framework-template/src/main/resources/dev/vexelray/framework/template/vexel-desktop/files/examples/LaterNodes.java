package ${packageName}.examples.later;

import ${packageName}.core.examples.later.Later;
import ${packageName}.core.examples.later.LaterMessages.Reminders;
import ${packageName}.core.examples.later.LaterMessages.Settled;
import ${packageName}.examples.Example;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.TextField;

/**
 * The <i>later</i> example's part of the Examples window: a search field that searches once the typing stops, and a
 * reminder that can be called off, behind {@link LaterPanel}. It holds no state: every label says what {@link Later}
 * last told the view.
 */
final class LaterNodes implements LaterPanel {

    static final String QUESTION = "Do something later, or once the typing stops";
    static final String ABOUT = "A step never sleeps or starts a timer: it ends, and its chain waits, for a message or "
            + "for quiet. The field waits until no keystroke has come for " + Later.QUIET.toMillis() + " ms, and the "
            + "reminder until no Never mind has come for " + Later.REMINDER.toSeconds() + " seconds. The chains are in "
            + "-core's examples/later/Later.java, this panel in -gui's examples/later/; LaterTest tests them with a clock "
            + "of its own, and checks/later.txt drives this window.";

    private final TextField search;
    private final Node searched;
    private final Button remind;
    private final Button neverMind;
    private final Node reminders;
    private final Node node;

    LaterNodes(Gui gui) {
        search = new TextField(gui, "").name(SEARCH);
        search.node().width(Length.FILL).textSize(Length.rem(0.875f));
        gui.landmark(SEARCH, search.node());
        searched = note(gui, "Type above: it searches once you stop");
        gui.landmark(SEARCHED, searched);

        remind = new Button(gui, "Remind me in " + Later.REMINDER.toSeconds() + " seconds").name(REMIND);
        gui.landmark(REMIND, remind.node());
        neverMind = new Button(gui, "Never mind").name(NEVER_MIND).enabled(false);
        gui.landmark(NEVER_MIND, neverMind.node());
        reminders = note(gui, "No reminder waiting");
        gui.landmark(REMINDERS, reminders);

        node = gui.column().width(Length.FILL).gap(Length.rem(1.5f)).alignItems(AlignItems.STRETCH).children(
                section(gui, "Once the typing stops", search.node(), searched),
                section(gui, "Later", gui.row().gap(Length.rem(0.6f)).alignItems(AlignItems.CENTER)
                        .children(remind.node(), neverMind.node()), reminders));
    }

    private static Node section(Gui gui, String heading, Node... body) {
        Node column = gui.column().width(Length.FILL).gap(Length.rem(0.6f)).alignItems(AlignItems.STRETCH);
        column.append(gui.text(heading).fontWeight(600).textColor(Role.INK));
        return column.append(body);
    }

    private static Node note(Gui gui, String text) {
        return gui.text(text).textSize(Length.rem(0.8125f)).textColor(Role.DIM).width(Length.FILL);
    }

    @Override
    public Example example() {
        return Example.of("later", QUESTION, ABOUT, node);
    }

    @Override
    public TextField searchField() {
        return search;
    }

    @Override
    public String searchAddress() {
        return search.address();
    }

    @Override
    public String remindAddress() {
        return remind.address();
    }

    @Override
    public String neverMindAddress() {
        return neverMind.address();
    }

    @Override
    public void searched(Settled s) {
        searched.text(s.text().isBlank() ? "Nothing to search for"
                : "Searched for \"" + s.text() + "\", once, after " + s.keystrokes()
                        + (s.keystrokes() == 1 ? " keystroke" : " keystrokes"));
    }

    @Override
    public void reminders(Reminders r) {
        reminders.text(r.news());
        neverMind.enabled(r.waiting() > 0);
    }
}

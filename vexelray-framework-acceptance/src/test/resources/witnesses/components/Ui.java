package ${packageName};

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The witness's tree: the counter, a readout for each thing a component can do, and a button for each thing to ask.
 *
 * <p>It holds no state and runs nothing at a press: a button publishes {@code Button.PRESSED} with its address, and
 * {@link Shown}, the view, decides what that means. Each button's name is its landmark, so its address is the
 * landmark in this tree.
 */
final class Ui {

    private final Gui gui;
    final Node count;
    final Node note;
    final Node taps;
    final Node result;
    final Node echo;
    final Node isolated;
    final Node standard;
    final Node stall;

    Ui(Gui gui, TitleBar titleBar) {
        this.gui = gui;

        Node heading = gui.text(${className}.TITLE).font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        count = readout(Landmarks.COUNT, String.valueOf(Doc.initial().count()), Type.FIGURE);
        note = readout(Landmarks.NOTE, Doc.initial().note(), Type.SMALL);
        taps = readout(Landmarks.TAPS, "taps 0", Type.SMALL);
        result = readout(Landmarks.RESULT, "worked 0", Type.SMALL);
        echo = readout(Landmarks.ECHO, "echoed 0", Type.SMALL);
        isolated = readout(Landmarks.ISOLATED, "isolated idle", Type.SMALL);
        standard = readout(Landmarks.DEFAULT, "default idle", Type.SMALL);
        stall = readout(Landmarks.STALL, "stall none", Type.SMALL);

        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.COUNT_BUTTON, "Count"),
                button(Landmarks.RESET_BUTTON, "Reset"),
                button(Landmarks.TAP_BUTTON, "Tap"),
                button(Landmarks.WORK_BUTTON, "Work"),
                button(Landmarks.ECHO_BUTTON, "Echo"));
        Node wedges = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.WEDGE_DEFAULT_BUTTON, "Wedge default"),
                button(Landmarks.WEDGE_ISOLATED_BUTTON, "Wedge isolated"),
                button(Landmarks.TOOL_BUTTON, "Tool"));

        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(heading, count, note, taps, result, echo, isolated, standard, stall, buttons, wedges);
        gui.landmark(Landmarks.CARD, card);

        Node body = gui.row()
                .width(Length.FILL).height(Length.grow(1f))
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.PAGE))
                .children(card);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body);
    }

    private Node readout(String landmark, String text, Length size) {
        Node node = gui.text(text).font(Type.UI).textSize(size).textColor(gui.theme().color(Role.INK));
        gui.landmark(landmark, node);
        return node;
    }

    private Node button(String landmark, String label) {
        Button button = new Button(gui, label).name(landmark);
        gui.landmark(landmark, button.node());
        return button.node();
    }

    /** The address a button called {@code landmark} in this tree presses with. */
    String address(String landmark) {
        return gui.address(landmark);
    }
}

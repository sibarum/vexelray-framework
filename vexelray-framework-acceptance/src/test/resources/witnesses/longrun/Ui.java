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
 * The tree for the long-running witness: the counter, a start and a stop for the metronome, its tick count, and a
 * button that weighs the process. It holds no state and runs nothing at a press; {@link Shown} decides what a press
 * means. Each button's name is its landmark.
 */
final class Ui {

    private final Gui gui;
    final Node count;
    final Node note;
    final Node ticks;
    final Node sample;

    Ui(Gui gui, TitleBar titleBar) {
        this.gui = gui;

        Node heading = gui.text(${className}.TITLE).font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        count = readout(Landmarks.COUNT, String.valueOf(Doc.initial().count()), Type.FIGURE);
        note = readout(Landmarks.NOTE, Doc.initial().note(), Type.SMALL);
        ticks = readout(Landmarks.TICKS, "ticks 0", Type.SMALL);
        sample = readout(Landmarks.SAMPLE, "sample none", Type.SMALL);

        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.COUNT_BUTTON, "Count"),
                button(Landmarks.RESET_BUTTON, "Reset"),
                button(Landmarks.START_BUTTON, "Start"),
                button(Landmarks.STOP_BUTTON, "Stop"),
                button(Landmarks.SAMPLE_BUTTON, "Sample"));

        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(heading, count, note, ticks, sample, buttons);
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

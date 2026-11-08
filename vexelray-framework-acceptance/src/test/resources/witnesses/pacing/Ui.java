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
 * The tree for the pacing measurements: a button and a readout for each probe, and the figure the pulse moves. It
 * holds no state and runs nothing at a press; {@link Shown} measures. Each button's name is its landmark.
 */
final class Ui {

    private final Gui gui;
    final Node figure;
    final Node pulse;
    final Node latency;
    final Node storm;
    final Node idle;
    final Node tick;
    final Node tap;

    Ui(Gui gui, TitleBar titleBar) {
        this.gui = gui;

        Node heading = gui.text(${className}.TITLE).font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        figure = readout(Landmarks.COUNT, "0", Type.FIGURE);
        pulse = readout(Landmarks.PULSE, "pulse none", Type.SMALL);
        latency = readout(Landmarks.LATENCY, "latency none", Type.SMALL);
        storm = readout(Landmarks.STORM, "storm none", Type.SMALL);
        idle = readout(Landmarks.IDLE, "idle none", Type.SMALL);
        tick = readout(Landmarks.TICK, "tick -", Type.SMALL);
        tap = readout(Landmarks.TAP, "tap none", Type.SMALL);

        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.TAP_BUTTON, "Tap"),
                button(Landmarks.PULSE_BUTTON, "Pulse"),
                button(Landmarks.PROBE_BUTTON, "Probe"),
                button(Landmarks.STORM_BUTTON, "Storm"),
                button(Landmarks.IDLE_BUTTON, "Idle"));

        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(heading, figure, pulse, latency, storm, idle, tick, tap, buttons);
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

    String address(String landmark) {
        return gui.address(landmark);
    }

    Gui gui() {
        return gui;
    }
}

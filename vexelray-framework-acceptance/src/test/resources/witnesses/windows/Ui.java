package ${packageName};

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;

/** The main window's tree: the count, and a button for each window, the question, and quitting. */
final class Ui {

    final Node count;

    Ui(Gui gui, TitleBar titleBar) {
        Node heading = gui.text(${className}.TITLE).font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        count = Parts.readout(gui, Landmarks.COUNT, "0", Type.FIGURE);
        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                Parts.button(gui, Landmarks.COUNT_BUTTON, "Count"),
                Parts.button(gui, Landmarks.NOTES_BUTTON, "Notes"),
                Parts.button(gui, Landmarks.PALETTE_BUTTON, "Palette"),
                Parts.button(gui, Landmarks.ASK_BUTTON, "Ask"),
                Parts.button(gui, Landmarks.QUIT_BUTTON, "Quit"));
        Parts.lay(gui, titleBar.node(), heading, count, buttons);
    }
}

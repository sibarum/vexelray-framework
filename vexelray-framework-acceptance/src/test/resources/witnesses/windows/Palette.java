package ${packageName};

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.style.Role;

/** A satellite of the main window: the count, a button that counts from here, and one that closes it. */
final class Palette {

    static final String KEY = "palette";
    static final int W = 320;
    static final int H = 220;

    final Node count;

    Palette(Window window) {
        Gui gui = window.gui();
        Node heading = gui.text("Palette").font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        count = Parts.readout(gui, Landmarks.PALETTE_COUNT, "0", Type.FIGURE);
        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                Parts.button(gui, Landmarks.PALETTE_BUMP, "Count"),
                Parts.button(gui, Landmarks.PALETTE_CLOSE, "Close"));
        Parts.lay(gui, window.titleBar().node(), heading, count, buttons);
    }
}

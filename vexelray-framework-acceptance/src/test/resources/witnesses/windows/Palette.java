package ${packageName};

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.style.Role;

/**
 * A satellite: above the main window and minimized with it. It counts, so a change made in one window has to reach
 * the other two through the model, and it closes itself, so its tree has to outlive its window.
 */
final class Palette {

    static final String KEY = "palette";
    static final int W = 320;
    static final int H = 220;

    private final Node count;

    Palette(Window window, Model model) {
        Gui gui = window.gui();
        Node heading = gui.text("Palette").font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        count = Parts.readout(gui, Landmarks.PALETTE_COUNT, "0", Type.FIGURE);
        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                Parts.button(gui, Landmarks.PALETTE_BUMP, "Count", model::bump),
                Parts.button(gui, Landmarks.PALETTE_CLOSE, "Close", window::close));
        Parts.lay(gui, window.titleBar().node(), heading, count, buttons);
    }

    void show(Doc doc) {
        count.text(String.valueOf(doc.count()));
    }
}

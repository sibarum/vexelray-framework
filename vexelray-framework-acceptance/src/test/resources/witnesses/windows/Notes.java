package ${packageName};

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.style.Role;

/**
 * A peer of the main window: the count as this window shows it, and how many times this process has opened it, which
 * the view counts from {@code WindowOpened}.
 */
final class Notes {

    static final String KEY = "notes";
    static final int W = 420;
    static final int H = 300;

    final Node count;
    final Node opened;

    Notes(Window window) {
        Gui gui = window.gui();
        Node heading = gui.text("Notes").font(Type.UI).textSize(Type.HEADING).textColor(gui.theme().color(Role.INK));
        count = Parts.readout(gui, Landmarks.NOTES_COUNT, "0", Type.FIGURE);
        opened = Parts.readout(gui, Landmarks.NOTES_OPENED, "opened 0", Type.SMALL);
        Parts.lay(gui, window.titleBar().node(), heading, count, opened);
    }
}

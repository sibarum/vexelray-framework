package ${packageName};

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.style.Role;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A peer window: its own place in the stack and the taskbar, showing the count from a tree of its own. Its
 * placement and zoom are what the witness checks survive a restart, and its open count is written by the window's
 * listener, so it is only right if the framework called it.
 */
final class Notes {

    static final String KEY = "notes";
    static final int W = 420;
    static final int H = 300;

    private final Node count;

    Notes(Window window) {
        Gui gui = window.gui();
        Node heading = gui.text("Notes").font(Type.UI).textSize(Type.HEADING).textColor(gui.theme().color(Role.INK));
        count = Parts.readout(gui, Landmarks.NOTES_COUNT, "0", Type.FIGURE);
        Node opened = Parts.readout(gui, Landmarks.NOTES_OPENED, "opened 0", Type.SMALL);
        AtomicInteger opens = new AtomicInteger();
        window.onOpened(() -> opened.text("opened " + opens.incrementAndGet()));
        Parts.lay(gui, window.titleBar().node(), heading, count, opened);
    }

    void show(Doc doc) {
        count.text(String.valueOf(doc.count()));
    }
}

package ${packageName};

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

/**
 * A second window that closes while the application keeps running: the calculator's settings window, reduced to
 * what made it crash. Its placement is remembered, so a frame hook holds it; closing it is what that hook must
 * survive. Its close button closes the window by the route the title bar's X takes, because the X carries no
 * landmark for a driver to find.
 *
 * <p>The window and its tree are the framework's: this class builds into them and does nothing else.
 */
final class ToolWindow {

    static final String KEY = "tool";
    static final int W = 360;
    static final int H = 240;

    ToolWindow(Window window) {
        Gui gui = window.gui();
        Node close = gui.text("Close").font(Type.UI).textSize(Type.LABEL)
                .textColor(gui.theme().color(Role.INK))
                .padding(Type.TIGHT, Type.WIDE)
                .border(Type.RULE, gui.theme().color(Role.LINE))
                .role("button");
        gui.cursor(close, CursorShape.POINTER);
        gui.onClick(close, window::close);
        gui.landmark(Landmarks.TOOL_CLOSE_BUTTON, close);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), gui.column().width(Length.FILL).height(Length.grow(1f))
                        .padding(Type.WIDE, Type.WIDE).children(close));
    }
}

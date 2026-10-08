package ${packageName};

import dev.vexelray.framework.core.Lanes;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Standing;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import sibarum.atchung.Atchung;

/**
 * A second window that closes while the application keeps running: the calculator's settings window, reduced to
 * what made it crash. Its placement is remembered, so a frame hook holds it; closing it is what that hook must
 * survive. Its close button calls the same {@link WindowControls#close} the title bar's X does, because the X
 * carries no landmark for a driver to find.
 */
final class ToolWindow implements AutoCloseable {

    static final String KEY = "tool";
    private static final int W = 360;
    private static final int H = 240;

    private final Gui gui;
    private final TitleBar bar;
    private volatile WindowControls controls = WindowControls.NONE;

    ToolWindow(Appearance look, Lanes lanes) {
        // A bus of its own: two trees on one bus each receive the other's mutations. See Gui(Atchung).
        this.gui = new Gui(Atchung.create(), lanes.handlers(), lanes.offload());
        look.applyTo(gui);
        this.bar = new TitleBar(gui, WindowControls.NONE, "Tool");

        Node close = gui.text("Close").font(Type.UI).textSize(Type.LABEL)
                .textColor(gui.theme().color(Role.INK))
                .padding(Type.TIGHT, Type.WIDE)
                .border(Type.RULE, gui.theme().color(Role.LINE))
                .role("button");
        gui.cursor(close, CursorShape.POINTER);
        gui.onClick(close, () -> controls.close());
        gui.landmark(Landmarks.TOOL_CLOSE_BUTTON, close);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(bar.node(), gui.column().width(Length.FILL).height(Length.grow(1f))
                        .padding(Type.WIDE, Type.WIDE).children(close));
    }

    /** Claim the window, remembered under its name. Main thread, once. */
    AppWindow claim(GuiApp app, WindowMemory memory) {
        return app.window(KEY, () -> memory.remember(KEY,
                bar.commands(WindowSpec.of(memory.config(KEY, "Tool", W, H).decorations(Decorations.CLIENT), gui))
                        .standing(Standing.SATELLITE)
                        .onControls(c -> controls = c)
                        .onClosed(() -> controls = WindowControls.NONE),
                W, H));
    }

    @Override
    public void close() {
        gui.close();
    }
}

package ${packageName};

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;

/** What the three windows' trees are built from: a readout, a button that is only a name, and the card they sit in. */
final class Parts {

    private Parts() {
    }

    static Node readout(Gui gui, String landmark, String text, Length size) {
        Node node = gui.text(text).font(Type.UI).textSize(size).textColor(gui.theme().color(Role.INK));
        gui.landmark(landmark, node);
        return node;
    }

    /** A button named {@code landmark}, so it presses with {@code <tree>.<landmark>}; what that means is the view's. */
    static Node button(Gui gui, String landmark, String label) {
        Button button = new Button(gui, label).name(landmark);
        gui.landmark(landmark, button.node());
        return button.node();
    }

    /** {@code bar} above a centred card holding {@code children}: the shape of every window here. */
    static void lay(Gui gui, Node bar, Node... children) {
        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(children);
        Node body = gui.row()
                .width(Length.FILL).height(Length.grow(1f))
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.PAGE))
                .children(card);
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(bar, body);
    }
}

package ${packageName};

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

/** The few things every window here is built from, made in whichever tree they are asked of. */
final class Parts {

    private Parts() {
    }

    static Node readout(Gui gui, String landmark, String text, Length size) {
        Node node = gui.text(text).font(Type.UI).textSize(size).textColor(gui.theme().color(Role.INK));
        gui.landmark(landmark, node);
        return node;
    }

    static Node button(Gui gui, String landmark, String label, Runnable action) {
        Node button = gui.text(label)
                .font(Type.UI)
                .textSize(Type.LABEL)
                .textColor(gui.theme().color(Role.INK))
                .padding(Type.TIGHT, Type.WIDE)
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Role.LINE))
                .role("button");
        gui.focusable(button, true);
        gui.cursor(button, CursorShape.POINTER);
        gui.onClick(button, action);
        gui.onState(button, state -> button.background(state == InteractionState.HOVER
                ? gui.theme().color(Role.ACCENT, InteractionState.HOVER)
                : gui.theme().color(Role.NONE)));
        gui.landmark(landmark, button);
        return button;
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

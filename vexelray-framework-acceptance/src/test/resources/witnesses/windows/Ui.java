package ${packageName};

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;

/** The main window: the counter, and a button for each of the other two windows. */
final class Ui {

    private final Node count;
    private volatile Runnable notes = () -> { };
    private volatile Runnable palette = () -> { };

    /**
     * @param quit what the Quit button does: close the main window, which is quitting
     * @param ask  what the Ask button does: ask a question whose answer counts
     */
    Ui(Gui gui, Model model, TitleBar titleBar, Runnable quit, Runnable ask) {
        Node heading = gui.text(${className}.TITLE).font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        count = Parts.readout(gui, Landmarks.COUNT, "0", Type.FIGURE);
        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                Parts.button(gui, Landmarks.COUNT_BUTTON, "Count", model::bump),
                Parts.button(gui, Landmarks.NOTES_BUTTON, "Notes", () -> notes.run()),
                Parts.button(gui, Landmarks.PALETTE_BUTTON, "Palette", () -> palette.run()),
                Parts.button(gui, Landmarks.ASK_BUTTON, "Ask", ask),
                Parts.button(gui, Landmarks.QUIT_BUTTON, "Quit", quit));
        Parts.lay(gui, titleBar.node(), heading, count, buttons);
    }

    /** What the notes button does: handed in, since the window is declared after this tree is built. */
    void onNotes(Runnable action) {
        notes = action;
    }

    /** What the palette button does, on the same terms. */
    void onPalette(Runnable action) {
        palette = action;
    }

    void show(Doc doc) {
        count.text(String.valueOf(doc.count()));
    }
}

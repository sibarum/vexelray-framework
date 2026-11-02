package ${packageName}.examples;

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.SplitPane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Examples window: the worked examples down the left, by the question each answers, and the chosen one live on the
 * right under its question and a few lines on how. It is itself an example, of a second window: declared by name in
 * {@link Examples}, opened by a chord ({@link ExamplesView}), its place and size remembered under its name, and its
 * widgets heard by a view as the main window's are, at {@code "examples.<name>"}.
 *
 * <p>Each example adds itself ({@link #add}) from its own recipe, so this class names none of them, and deleting an
 * example's folders takes it out of the list.
 *
 * <p>Built in {@code TREE}, as the main window's tree is, so a test builds it headless; written to after that on the
 * GUI thread only, by {@link ExamplesView}.
 */
final class ExamplesPage implements ExamplesWindow {

    /** Its size on a first run, in the engine's logical coordinates. */
    static final int W = 980;
    static final int H = 640;

    /** The list's width on a first run: a question on one line, at the type's size. */
    private static final Length LIST_WIDTH = Length.rem(23);

    private final Gui gui;
    private final ListView<Example> list;
    private final Node question;
    private final Node about;
    private final Node stage;
    private final List<Example> examples = new ArrayList<>();
    /** The example on the right, or null before the first is added. */
    private Example shown;

    ExamplesPage(Window window) {
        gui = window.gui();
        list = new ListView<Example>(gui, 2.25f, (nodes, e) -> nodes.text(e.question())
                .textSize(Length.rem(0.8125f))
                .padding(Length.ZERO, Length.rem(0.75f)))
                .name("list");
        list.node().width(Length.FILL).height(Length.FILL).background(gui.theme().color(Role.PANEL));
        gui.landmark(LIST, list.node());

        question = gui.text("").textSize(Length.rem(1.25f)).fontWeight(600).textColor(gui.theme().color(Role.INK))
                .width(Length.FILL);
        gui.landmark(QUESTION, question);
        // As wide as the pane and wrapped inside it: it has no width of its own, so a long line never scrolls sideways.
        about = gui.text("").textSize(Length.rem(0.8125f)).textColor(gui.theme().color(Role.DIM)).width(Length.FILL);
        gui.landmark(ABOUT, about);
        stage = gui.column().width(Length.FILL).height(Length.grow(1f));

        Node right = gui.column().width(Length.FILL).height(Length.FILL)
                .gap(Length.rem(0.75f))
                .padding(Length.rem(1.25f), Length.rem(1.5f))
                .alignItems(AlignItems.STRETCH)
                .children(question, about, stage);
        SplitPane split = new SplitPane(gui, SplitPane.Orientation.SIDE_BY_SIDE, list.node(), right).size(LIST_WIDTH);
        split.node().width(Length.FILL).height(Length.grow(1f));

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), split.node());
    }

    @Override
    public Gui gui() {
        return gui;
    }

    @Override
    public void add(Example e) {
        examples.add(e);
        examples.sort(Comparator.comparing(Example::question));
        list.items(List.copyOf(examples));
        e.node().visible(false);
        stage.append(e.node());
        if (shown == null) {
            show(e);
        }
    }

    @Override
    public void chosen(ListView.Selected m) {
        if (m.list().equals(list.address()) && m.chosen().size() == 1
                && m.chosen().iterator().next() instanceof Example e) {
            show(e);
        }
    }

    /** Show {@code e} on the right, in place of the one there: the framework fades the one out and the other in. */
    private void show(Example e) {
        if (e == shown) {
            return;
        }
        if (shown != null) {
            shown.node().visible(false);
        }
        shown = e;
        e.node().visible(true);
        question.text(e.question());
        about.text(e.about());
        list.selection().at(e);
    }
}

package ${packageName}.examples;

import dev.vexelray.framework.api.All;
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
 * <p>It takes every example there is ({@code All<Example>}, in {@link Examples}), so this class names none of them, and
 * deleting an example's folders takes it out of the list. They are listed by question, since the order they come in
 * means nothing.
 *
 * <p>Built in {@code TREE}, as the main window's tree is, so a test builds it headless; written to after that on the
 * GUI thread only, by {@link ExamplesView}.
 */
final class ExamplesPage implements ExamplesList {

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
    /** The example on the right, or null when there is none. */
    private Example shown;

    ExamplesPage(Window window, All<Example> examples) {
        gui = window.gui();
        list = new ListView<Example>(gui, 2.25f, (nodes, e) -> nodes.text(e.question())
                .textSize(Length.rem(0.8125f))
                .padding(Length.ZERO, Length.rem(0.75f)))
                .name("list");
        list.node().width(Length.FILL).height(Length.FILL).background(gui.theme().color(Role.PANEL));
        gui.landmark(ExamplesWindow.LIST, list.node());

        question = gui.text("").textSize(Length.rem(1.25f)).fontWeight(600).textColor(gui.theme().color(Role.INK))
                .width(Length.FILL);
        gui.landmark(ExamplesWindow.QUESTION, question);
        // As wide as the pane and wrapped inside it: it has no width of its own, so a long line never scrolls sideways.
        about = gui.text("").textSize(Length.rem(0.8125f)).textColor(gui.theme().color(Role.DIM)).width(Length.FILL);
        gui.landmark(ExamplesWindow.ABOUT, about);
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

        List<Example> sorted = new ArrayList<>(examples.list());
        sorted.sort(Comparator.comparing(Example::question));
        list.items(List.copyOf(sorted));
        for (Example e : sorted) {
            e.node().visible(false);
            stage.append(e.node());
        }
        // Never open on an empty page while there is something to show, and say why when there is nothing.
        if (!sorted.isEmpty()) {
            show(sorted.get(0));
        } else {
            question.text("No worked examples in this project");
            about.text("It was made without them. To read them, generate a scratch project with every module. To add "
                    + "one, give it a folder under examples in each module, whose configuration provides an Example: "
                    + "this page lists every one there is.");
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

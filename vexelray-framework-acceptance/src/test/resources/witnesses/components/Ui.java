package ${packageName};

import dev.vexelray.framework.api.Publishes;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

/**
 * The witness's tree: the template's counter, plus a button and a readout for each thing a component can do.
 *
 * <p>Every button's handler runs on the handler lane, which is where a send on a {@code BLOCK} mailbox is
 * allowed to wait. None of this runs on the main thread, so {@link Publishes} here is a declaration the
 * processor can hold to T4.7 rather than a violation of it.
 */
@Publishes({Topics.JOB, Topics.PING, Topics.WEDGE_DEFAULT, Topics.WEDGE_ISOLATED})
final class Ui {

    private static final Topic<Job> JOB = Topic.of(Topics.JOB, Job.class);
    private static final Topic<Ping> PING = Topic.of(Topics.PING, Ping.class);
    private static final Topic<Wedge> WEDGE_DEFAULT = Topic.of(Topics.WEDGE_DEFAULT, Wedge.class);
    private static final Topic<Wedge> WEDGE_ISOLATED = Topic.of(Topics.WEDGE_ISOLATED, Wedge.class);

    private final Gui gui;
    private final Bench bench;
    private final Node count;
    private final Node note;
    private final Node result;
    private final Node echo;
    private final Node isolated;
    private final Node standard;
    private final Node stall;

    Ui(Gui gui, Model model, Bench bench, Atchung bus, TitleBar titleBar) {
        this.gui = gui;
        this.bench = bench;

        Node heading = gui.text(${className}.TITLE).font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        count = readout(Landmarks.COUNT, "0", Type.FIGURE);
        note = readout(Landmarks.NOTE, "", Type.SMALL);
        result = readout(Landmarks.RESULT, bench.workedText(), Type.SMALL);
        echo = readout(Landmarks.ECHO, bench.echoedText(), Type.SMALL);
        isolated = readout(Landmarks.ISOLATED, bench.isolatedText(), Type.SMALL);
        standard = readout(Landmarks.DEFAULT, bench.defaultText(), Type.SMALL);
        stall = readout(Landmarks.STALL, bench.stallText(), Type.SMALL);

        AtomicCounter jobs = new AtomicCounter();
        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.COUNT_BUTTON, "Count", model::bump),
                button(Landmarks.RESET_BUTTON, "Reset", model::reset),
                button(Landmarks.WORK_BUTTON, "Work", () -> bus.publish(JOB, new Job(jobs.next()))),
                button(Landmarks.ECHO_BUTTON, "Echo", () -> bus.publish(PING, new Ping(jobs.next()))));
        Node wedges = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.WEDGE_DEFAULT_BUTTON, "Wedge default",
                        () -> bus.publish(WEDGE_DEFAULT, new Wedge(jobs.next()))),
                button(Landmarks.WEDGE_ISOLATED_BUTTON, "Wedge isolated",
                        () -> bus.publish(WEDGE_ISOLATED, new Wedge(jobs.next()))));

        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(heading, count, note, result, echo, isolated, standard, stall, buttons, wedges);
        gui.landmark(Landmarks.CARD, card);

        Node body = gui.row()
                .width(Length.FILL).height(Length.grow(1f))
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.PAGE))
                .children(card);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body);
    }

    private Node readout(String landmark, String text, Length size) {
        Node node = gui.text(text).font(Type.UI).textSize(size).textColor(gui.theme().color(Role.INK));
        gui.landmark(landmark, node);
        return node;
    }

    private Node button(String landmark, String label, Runnable action) {
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

    /** Everything derived from the document and from the bench, written whole. */
    void show(Doc doc) {
        count.text(String.valueOf(doc.count()));
        note.text(doc.note());
        showBench();
    }

    void showBench() {
        result.text(bench.workedText());
        echo.text(bench.echoedText());
        isolated.text(bench.isolatedText());
        standard.text(bench.defaultText());
        stall.text(bench.stallText());
    }

    /** A message number, so no two published messages are equal. */
    private static final class AtomicCounter {
        private final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

        int next() {
            return n.incrementAndGet();
        }
    }
}

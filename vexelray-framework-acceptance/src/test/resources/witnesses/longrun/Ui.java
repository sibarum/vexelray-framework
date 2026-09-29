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
 * The tree for the long-running witness: the template's counter, a start and a stop for the metronome, its tick
 * count, and a button that takes a sample. Handlers run on the handler lane, which is where a send may wait.
 */
@Publishes(Topics.TICK)
final class Ui {

    private static final Topic<Tick> TICK = Topic.of(Topics.TICK, Tick.class);

    private final Gui gui;
    private final Meter meter;
    private final Node count;
    private final Node note;
    private final Node ticks;
    private final Node sample;

    Ui(Gui gui, Model model, Meter meter, Atchung bus, TitleBar titleBar) {
        this.gui = gui;
        this.meter = meter;

        Node heading = gui.text(${className}.TITLE).font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        count = readout(Landmarks.COUNT, "0", Type.FIGURE);
        note = readout(Landmarks.NOTE, "", Type.SMALL);
        ticks = readout(Landmarks.TICKS, meter.ticksText(), Type.SMALL);
        sample = readout(Landmarks.SAMPLE, "sample none", Type.SMALL);

        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.COUNT_BUTTON, "Count", model::bump),
                button(Landmarks.RESET_BUTTON, "Reset", model::reset),
                button(Landmarks.START_BUTTON, "Start", () -> {
                    meter.running(true);
                    bus.publish(TICK, new Tick(0));
                }),
                button(Landmarks.STOP_BUTTON, "Stop", () -> meter.running(false)),
                button(Landmarks.SAMPLE_BUTTON, "Sample", () -> sample.text(meter.snapshot())));

        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(heading, count, note, ticks, sample, buttons);
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

    void show(Doc doc) {
        count.text(String.valueOf(doc.count()));
        note.text(doc.note());
    }

    /** Called from the metronome's thread, on every tick. */
    void showTicks() {
        ticks.text(meter.ticksText());
    }
}

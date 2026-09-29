package ${packageName};

import dev.vexelray.canvas.Color;
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
import dev.vexelray.gui.krono.Colors;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tree for the pacing measurements: a button and a readout for each probe, and the one node the probes write
 * to make a wake. Handlers run on the handler lane, which is where a send may wait.
 */
@Publishes({Topics.PROBE, Topics.STORM})
final class Ui {

    /** The template's pulse: a 600 ms out-and-back ramp, which is what a click starts in a real application. */
    private static final Dur PULSE = Dur.ms(600);

    private static final Topic<Probe> PROBE = Topic.of(Topics.PROBE, Probe.class);
    private static final Topic<Storm> STORM = Topic.of(Topics.STORM, Storm.class);

    private final Gui gui;
    private final KronoGui krono;
    private final Meter meter;
    private final Node figure;
    private final Node pulse;
    private final Node latency;
    private final Node storm;
    private final Node tick;
    private final AtomicInteger pulses = new AtomicInteger();

    Ui(Gui gui, KronoGui krono, Meter meter, Atchung bus, TitleBar titleBar) {
        this.gui = gui;
        this.krono = krono;
        this.meter = meter;

        Node heading = gui.text(${className}.TITLE).font(Type.UI).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));
        figure = readout(Landmarks.COUNT, "0", Type.FIGURE);
        pulse = readout(Landmarks.PULSE, "pulse none", Type.SMALL);
        latency = readout(Landmarks.LATENCY, "latency none", Type.SMALL);
        storm = readout(Landmarks.STORM, "storm none", Type.SMALL);
        tick = readout(Landmarks.TICK, "tick -", Type.SMALL);

        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.PULSE_BUTTON, "Pulse", this::pulse),
                button(Landmarks.PROBE_BUTTON, "Probe", () -> bus.publish(PROBE, new Probe(15))),
                button(Landmarks.STORM_BUTTON, "Storm", () -> bus.publish(STORM, new Storm(3_000))));

        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(heading, figure, pulse, latency, storm, tick, buttons);
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

    /**
     * The template's pulse, timed: the frames the loop ran between the press and the ramp finishing, and the wall
     * time that took. A loop held to a 60 Hz display runs about thirty-six across 600 ms.
     */
    private void pulse() {
        long framesBefore = meter.frames();
        long start = System.nanoTime();
        Color ink = gui.theme().color(Role.INK);
        Color accent = gui.theme().color(Role.ACCENT);
        krono.ramp(PULSE, Ease.LINEAR,
                p -> figure.textColor(Colors.OKLAB.between(ink, accent, (float) Math.sin(Math.PI * p))),
                () -> {
                    long frames = meter.frames() - framesBefore;
                    long millis = (System.nanoTime() - start) / 1_000_000L;
                    figure.textColor(ink);
                    pulse.text("pulse #" + pulses.incrementAndGet() + " frames " + frames + " in " + millis + "ms");
                });
    }

    /** Written by the probes, from their own thread, to earn a wake. Distinct text each time. */
    void tick(int n) {
        tick.text("tick " + n);
    }

    void latency(String text) {
        latency.text(text);
    }

    void storm(String text) {
        storm.text(text);
    }
}

package ${packageName};

import dev.vexelray.canvas.Color;
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
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The tree. Builds it, holds the handles the application needs afterwards, and owns nothing else.
 *
 * <h2>It holds no state</h2>
 *
 * <p>{@link #show} takes a whole {@link Doc} and writes everything derived from it, which is the rule worth
 * keeping as this grows: a view that remembers a value can disagree with the document, and a label that
 * disagrees with the state is not a cosmetic bug -- it is the application lying about what it is doing.
 *
 * <p>{@code show} is called from the committing thread, which is a worker. That is correct and is the
 * framework's own idiom: a prop written off the GUI thread is queued and applied by the next drain, so the
 * frame that presents a value is the frame that reconciled it. What is <em>not</em> allowed is the other
 * direction -- reading the model from inside the frame loop.
 *
 * <p>The one thing it does hold is whether the count is mid-{@linkplain #pulse pulse}, which is motion rather than
 * state: nothing is derived from it, and it is gone the moment the pulse lands.
 */
final class Ui {

    /**
     * How long the count glows after a press: long enough to see, and a transition the automation socket's
     * {@code settle} has to wait out rather than photograph half-way through.
     */
    static final Dur PULSE = Dur.ms(600);

    private final Gui gui;
    private final KronoGui krono;
    private final TitleBar titleBar;
    private final Node count;
    private final Node note;
    private final AtomicBoolean pulsing = new AtomicBoolean();

    Ui(Gui gui, KronoGui krono, Model model, TitleBar titleBar) {
        this.gui = gui;
        this.krono = krono;
        // The bar is the framework's: chrome placement belongs to whoever owns the window, so the instruments
        // in it mean the same thing in every window on the desk. This application places the node, below, and
        // supplies every colour in it through Look. It is already pointed at real window controls by the time
        // a frame is drawn -- the framework hands those down at ATTACH, once the window exists.
        this.titleBar = titleBar;

        Node heading = gui.text(${className}.TITLE)
                .font(Type.UI)
                .textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));

        count = gui.text("0")
                .font(Type.MONO)
                .textSize(Type.FIGURE)
                .textColor(gui.theme().color(Role.INK));
        gui.landmark(Landmarks.COUNT, count);

        note = gui.text("")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.DIM));
        gui.landmark(Landmarks.NOTE, note);

        Node buttons = gui.row().gap(Type.GAP).alignItems(AlignItems.CENTER).children(
                button(Landmarks.COUNT_BUTTON, "Count", () -> {
                    model.bump();
                    pulse();
                }),
                button(Landmarks.RESET_BUTTON, "Reset", model::reset));

        Node card = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .padding(Type.WIDE, Type.WIDE)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.CENTER)
                .children(heading, count, note, buttons);
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

    /**
     * An outline button.
     *
     * <p>There is no button component in {@code vexelray-gui-widget} yet, so this is the hand-rolled shape the
     * reference implementation uses: a text node made focusable, given a pointer cursor, a click handler and a
     * hover wash. Four ordinary calls rather than a widget -- and worth noting in {@code docs/framework-notes.md}
     * as an opportunity, because every project on this framework writes these four calls again.
     */
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
        // The handler runs on a worker, not on the GUI thread. Everything it touches goes through Model.
        gui.onClick(button, action);
        gui.onState(button, state -> button.background(state == InteractionState.HOVER
                ? gui.theme().color(Role.ACCENT, InteractionState.HOVER)
                : gui.theme().color(Role.NONE)));
        gui.landmark(landmark, button);
        return button;
    }

    /**
     * The count glows towards the accent and back, once, in answer to a press.
     *
     * <p><b>Out and back in one ramp</b>, so both ends are the colour the figure already is. A figure that jumped
     * to the accent and faded back would flash, and a sudden change of pixels is the one kind of feedback this
     * application does not give. For the same reason <b>a press during a pulse does not restart it</b>: a second
     * ramp would begin at the ink wherever the first had got to, which is a jump by another route.
     *
     * <p>Started from a click handler, on a worker, through {@link KronoGui#ramp} — which routes it onto the
     * timeline and is safe from any thread. The progress lands on the timeline, once per frame; nothing here is
     * read by the model, and the pulse is not triggered by {@link #show}, so the tree a capture photographs is at
     * rest.
     */
    private void pulse() {
        if (!pulsing.compareAndSet(false, true)) {
            return;
        }
        Color ink = gui.theme().color(Role.INK);
        Color accent = gui.theme().color(Role.ACCENT);
        krono.ramp(PULSE, Ease.LINEAR,
                p -> count.textColor(Colors.OKLAB.between(ink, accent, (float) Math.sin(Math.PI * p))),
                () -> {
                    count.textColor(ink);
                    pulsing.set(false);
                });
    }

    /**
     * Write everything derived from the document.
     *
     * <p>One method taking the whole value, rather than a setter per field: two setters can be called with
     * values from two different versions, and this cannot.
     */
    void show(Doc doc) {
        count.text(String.valueOf(doc.count()));
        note.text(doc.note());
    }
}

# Building a custom view

The widget shelf (`dev.vexelray.gui.widget`) has tabs, trees, tables, fields, sliders and the rest. Sooner or later
your application needs a view it does not have: bars placed on a timeline, boxes over a drawn map, cards on a
board. This page is how to build one from plain nodes so that it lays out, takes clicks, can be reached by a script
and does not jump. [Making it move](motion.md) is the companion page.

## A widget or your own view

| You want | Use |
| --- | --- |
| A list, tree, table, text field, tabs, a toggle, a slider | The shelf. Each one carries an invariant you would otherwise re-derive: a selection anchor, a virtualised row window, a claim on a chord |
| A button that does one thing | The shelf's `Button`: its press is `Button.PRESSED`, which your `@View` hears, as the template's `LaterView` does |
| A card, a heading, a panel | Your own nodes |
| Things placed by a value: a time, a position on a map | Your own view, as below |

## Where a custom view sits

The same as everything else the template does. The `-core` component decides and says so in a message. A `@View`
in `-gui` hears it and changes the nodes. A click on one of those nodes is heard by the view as a message too, and
the view passes it on to the component as another message. The view decides nothing, and nothing in `-gui` holds a
model.

| Part | Module | Does |
| --- | --- | --- |
| Messages | `-core`, `Messages.java` | A record per thing said, each beside its topic |
| The component | `-core` | Holds what is true (a `Ledger`), and sends what the view should show |
| `Ui` | `-gui` | Builds the window in the `TREE` phase and places an empty node for your view |
| Your view's nodes | `-gui`, a plain class | Keeps one node per item and changes it in place |
| Your `@View` | `-gui` | Hears the component and the nodes, and sends what the person did |

## Three things to know first

1. **Every container scrolls by default.** `Node.scroll(allowX, allowY)` is on for both axes unless you say
   otherwise. A box whose content is bigger than it, even for one frame of an animation, grows a scrollbar, and the
   scrollbar takes room, so the content reflows in that same frame. A bar too short for its label shows a small
   scrollbar pill. Turn scrolling off, and clip, on every box that can be smaller than its content:
   `node.scroll(false, false).clip(true)`. (A label set to `wordWrap(false)` already does not scroll sideways. The
   box around it still does.)
2. **A float stays inside its parent.** `floatAt(x, y)` takes a node out of the flow and places it from the
   parent's border-box origin, and it is clamped so it stays inside the parent. A bar that would hang past the end
   is pushed back in, not cut off. A float also draws over, and is hit before, every sibling that comes before it,
   so a float you want on top must be the last child.
3. **Keep the nodes and change them.** Building the children again on every change is the easy way and the wrong
   one. A rebuilt node is a new node, so nothing can slide it or fade it, and the template's `ViewTest` fails on the
   cut. Its click, focus and landmark are dropped with it too, because they are keyed by node id. `Node.keyed` keeps
   a container's children by key for you: a key that stays keeps its node, a new key gets a new node, and a gone
   key's node leaves.

## A worked example: bars on a lane

One lane, with a bar for each slot at its place along the lane. Each bar is a real node: a click on it, or Enter
while it has the keyboard, picks it. It has a role and a landmark, so a script clicks the same thing a person does.
What is picked is the component's to decide; the bar shows it.

This is checked code: it was added to a project generated from the template, its test passed under `mvn test`, and
the `ottermate` script below drove it.

### In `-core`

The messages, in `Messages.java`. Each record crosses as itself, so a list in one is copied. (The template's
`Messages` already has an `Item`, so a timeline's thing is a `Slot`.)

```java
// ------------------------------------------------------------------ the timeline

/** What the timeline shows: every slot, and the one picked ("" for none). The planner tells the view. */
public static final Topic<Agenda> AGENDA_TOPIC = Topic.edge(Agenda.class);

public record Agenda(List<Slot> items, String picked) {

    public Agenda {
        items = List.copyOf(items);
    }
}

/** One bar: where it starts and ends, as fractions of the lane from 0 to 1. */
public record Slot(String id, String label, float from, float to) {
}

/** A bar was clicked, or Enter pressed on it. The view tells the planner. */
public static final Topic<Pick> PICK_TOPIC = Topic.edge(Pick.class);

public record Pick(String id) {
}
```

The component that decides, beside `Editor`. Add it to `Core`'s list
(`@Configuration(components = {Editor.class, Planner.class}, ...)`), or nothing builds it.

```java
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.core.WindowOpened;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;
import sibarum.atchung.Step;

/** Decides what is on the timeline and which slot is picked. The view shows it. */
@Component
@Hears({WindowOpened.class, Pick.class})
@Publishes(Agenda.class)
public final class Planner {

    static final List<Slot> DAY = List.of(
            new Slot("standup", "Stand-up", 0.05f, 0.15f),
            new Slot("review", "Review", 0.30f, 0.55f),
            new Slot("lunch", "Lunch", 0.62f, 0.72f));

    private final Ledger<Agenda> ledger;

    public Planner(Inbox inbox) {
        Ledger.Builder<Agenda> b = Ledger.of(new Agenda(DAY, ""));
        Step<Agenda, WindowOpened> begin = b.step("begin", Planner::begin);
        Step<Agenda, Pick> pick = b.step("pick", Planner::pick);
        b.on(WindowOpened.TOPIC, begin).on(Messages.PICK_TOPIC, pick);
        ledger = b.build(inbox, "planner", 64);
    }

    /** The window is up: show the day. */
    private static Agenda begin(Agenda s, WindowOpened opened, Out out) {
        if (opened.main()) {
            out.send(Messages.AGENDA_TOPIC, s);
        }
        return s;
    }

    /** A slot was picked: it is the picked one now, and the view is told. */
    private static Agenda pick(Agenda s, Pick p, Out out) {
        Agenda now = new Agenda(s.items(), p.id());
        out.send(Messages.AGENDA_TOPIC, now);
        return now;
    }
}
```

### In `-gui`

The names, in `Landmarks.java`. A landmark per item is a method. A name may not contain `/`.

```java
/** The timeline. */
static final String TIMELINE = "timeline";

/** A bar on the timeline, by its slot's id: {@code item.standup}. */
static String item(String id) {
    return "item." + id;
}
```

`Ui` places an empty lane, names it, and hands it out. It builds nothing that needs the view:

```java
lane = gui.box().width(Length.FILL).height(Length.rem(3.3f)).scroll(false, false).clip(true);
gui.landmark(Landmarks.TIMELINE, lane);
gui.root().direction(Direction.COLUMN)
        .background(gui.theme().color(Role.PAGE))
        .children(titleBar.node(), lane, split.node(), status.node());
```

```java
/** The timeline's lane, empty: its view fills it. */
Node lane() {
    return lane;
}
```

The bars, a plain class. It is given the `Sender<Pick>` its bars send through, so a bar's click and its Enter are
declared when the bar is made, as messages, and no code of the application runs at the moment of the click:

```java
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Keyed;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.NodeEvent;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;

/** A bar for each slot at its place along the lane. Its view builds it and tells it what to show. */
final class Timeline {

    private static final Length BAR_TOP = Length.rem(0.6f);

    private final Gui gui;
    private final Sender<Pick> pick;
    private final Keyed<Slot> bars;
    private final Color ink;
    private final Color ground;
    private final Color picked;

    Timeline(Gui gui, Node lane, Sender<Pick> pick) {
        this.gui = gui;
        this.pick = pick;
        this.ink = gui.theme().color(Role.INK);
        this.ground = gui.theme().color(Role.RAISED);
        this.picked = gui.theme().color(Role.ACCENT);
        this.bars = lane.keyed(Slot::id, slot -> make(slot.id()));
    }

    /** Bring the lane to {@code agenda}: a bar that stays is the same node, changed in place. */
    void show(Agenda agenda) {
        bars.reconcile(agenda.items(), (slot, bar) -> {
            bar.text(slot.label()).background(slot.id().equals(agenda.picked()) ? picked : ground);
            place(bar, slot);
        });
    }

    /** The bar for {@code id}, or null: for a test. */
    Node bar(String id) {
        return bars.node(id);
    }

    /** A new bar: a button a person, the keyboard and a script all reach, whose press is a {@link Pick}. */
    private Node make(String id) {
        Node bar = gui.text("").textColor(ink).wordWrap(false)
                .padding(Length.rem(0.4f), Length.dp(7)).corner(Length.dp(6)).role("button")
                .scroll(false, false).clip(true);
        Pick message = new Pick(id);
        gui.hear(bar, NodeEvent.CLICK, click -> pick.send(message));
        gui.focusable(bar, true);
        gui.claim(bar, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, pick, message);
        gui.landmark(Landmarks.item(id), bar);
        return bar;
    }

    /** Float the bar at its fraction of the lane, kept inside the lane, since a float is clamped into it anyway. */
    private static void place(Node bar, Slot slot) {
        float from = Math.clamp(slot.from(), 0f, 1f);
        float to = Math.clamp(slot.to(), from, 1f);
        bar.floatAt(Length.percent(100 * from), BAR_TOP).width(Length.percent(100 * (to - from)));
    }
}
```

The view. The framework finds it by its annotation, builds it after `Ui`, and hands it a `Sender` for each message
type its constructor names:

```java
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.gui.core.Gui;
import sibarum.atchung.Sender;

/** The timeline's view: what the planner decided, shown, and a bar pressed, told. It decides nothing. */
@View
final class TimelineView {

    private final Ui ui;
    private final Timeline timeline;

    TimelineView(Gui gui, Ui ui, Sender<Pick> pick) {
        this.ui = ui;
        this.timeline = new Timeline(gui, ui.lane(), pick);
    }

    Timeline timeline() {
        return timeline;
    }

    @Subscribe
    public void agenda(Agenda m) {
        timeline.show(m);
        for (Slot slot : m.items()) {
            if (slot.id().equals(m.picked())) {
                ui.status("Picked " + slot.label(), false);
            }
        }
    }
}
```

What each part is doing:

- **A click is a message.** `gui.hear(node, NodeEvent.CLICK, ...)` is how a view hears a node. The dispatcher
  publishes the click, addressed to the node, and it reaches the view in the frame's widget tick, on the GUI thread,
  with every other message. The view only sends a `Pick`; whether that changes anything is the planner's call. A
  node can be heard for each kind in `NodeEvent` (`CLICK`, `DOUBLE_CLICK`, `CONTEXT_CLICK`, `STATE` for hover and
  press, `POINTER`, `DRAG`, `WHEEL`, `KEY`, `CHAR`, `RESIZE`), once per kind, and never over a widget's own: hear
  what the widget publishes instead (`Button.PRESSED`, `TreeView.SELECTED`, ...).
- **A chord is declared.** `gui.claim(node, chord, scope, sender, message)` binds the chord to a message ahead of
  time, so the dispatcher sends it without asking anyone. The form that takes a `Runnable` is the widget library's
  (`Seams`), not an application's.
- **Placed by percent.** A `Length` has no pixel unit, and a percentage of the lane stretches with the window, so
  nothing is measured.
- **No `gui.batch`.** The view runs in the widget tick, before the frame applies the tree's changes, so everything
  one message handler writes lands in one frame, whole.
- **A click on a child bubbles** to the nearest node that hears clicks, so a bar that holds more than its words
  (an icon, a second line) still takes the click itself.
- **A gone key's node leaves**, and its click, focus, claims and landmark go with it.

### Boxes over a drawing

When the thing under the boxes is drawn (a map, a graph), draw it as one `Picture` on a layer, and float a box
over each item that should take clicks. Hear the layer's size, and draw again when it changes (`Diagram.draw` is
yours, returning a `dev.vexelray.gui.draw.Picture`):

```java
gui.hear(layer, NodeEvent.RESIZE, layout -> {
    w = layout.rect().w();
    h = layout.rect().h();
    layer.picture(Diagram.draw(graph, w, h, inks));
});
```

VexelRay Inspector's `View` draws its flow diagram this way. Place each box with `floatAt` and `size` in percent of
the layer, computed from the same numbers as the drawing, so that what is drawn and what is hit cannot disagree. Give
each box a role, a landmark and its click, as the bars above have.

## Making it reachable

| For | Do | Checked by |
| --- | --- | --- |
| A script | `gui.landmark(name, node)` with a name from `Landmarks.java` | `click item.standup` in `ottermate` |
| A reader of the tree | `node.role("button")` on the node a person would say they pointed at, with its words in it | `tree` lists the role and the name |
| The keyboard | `gui.focusable(node, true)`, and a claim on Enter (and Space) while focused, sending the click's message | `key TAB` until `tree` shows `focused` on a bar, then `key ENTER` |
| Waiting for a state | Put the state in an accessible name, as the template's `status.file` does | `await <landmark> <text>` |

A role changes nothing drawn or hit; it is what an agent, a test or a screen reader reads. The renderer draws the
theme's focus ring round whatever holds focus (`Node.focusRing` overrides it). Hearing `NodeEvent.KEY` on a node
also makes it focusable.

## Testing it

**Headless**, in the template's `ViewTest`. `VexelApplication.tree` builds the whole application with no window
and no GPU, and the generated wiring hands out each view (`wiring.timelineView()`). Tell the view what the planner
would, by publishing on the bus, and run frames until it has heard:

```java
@Test
void aBarIsMovedNotRebuilt() throws Exception {
    Timeline line = wiring.timelineView().timeline();
    Slot standup = new Slot("standup", "Stand-up", 0.1f, 0.2f);
    shell.bus().publish(Messages.AGENDA_TOPIC, new Agenda(List.of(standup), ""));
    eventually("the bar", () -> line.bar("standup") != null);
    Node first = line.bar("standup");
    Slot moved = new Slot("standup", "Stand-up", 0.3f, 0.4f);
    Slot review = new Slot("review", "Review", 0.5f, 0.7f);
    shell.bus().publish(Messages.AGENDA_TOPIC, new Agenda(List.of(moved, review), "standup"));
    eventually("the second bar", () -> line.bar("review") != null);
    assertSame(first, line.bar("standup"));
    shell.bus().publish(Messages.AGENDA_TOPIC, new Agenda(List.of(), ""));
    eventually("the bar to go", () -> line.bar("standup") == null);
}
```

`ViewTest`'s cut watch checks the same run for anything that changed on screen in one frame, so a bar rebuilt rather
than kept fails it. The planner's steps are tested in `-core` with no GUI at all, as `EditorTest` tests the
editor's. Where
things are on screen, and that a click reaches the planner, is for `ottermate`.

**With `ottermate`**, by landmark:

```
settle
tree
click item.standup
await status.message Picked Stand-up
key TAB
settle
key ENTER
await status.message Picked Review
tree
shot C:/work/timeline.png
```

Run it with `ottermate --size 1600x900 --script timeline.txt --launch mvn.cmd compile exec:exec -Dautomation=0`
from the project's folder. The window remembers its last size, so give `--size` whenever you compare screenshots. In
the `tree` output, `clipped to` and `offscreen` show a bar that is there but cannot be hit.

## Traps

| Symptom | Cause | Fix |
| --- | --- | --- |
| A small scrollbar pill inside a bar or card | Scrolling is on by default for containers, and the content is bigger than the box | `scroll(false, false).clip(true)` on every box that can be smaller than its content |
| A scrollbar flashes for one frame during an animation | Same, mid-animation: something overflowed for a frame | Same fix, on the box that overflowed. Lay out regions whose contents change so they never need to scroll |
| A float sits somewhere other than where you put it, or is squeezed | A float is clamped inside its parent | Keep `from + width` within 100%; make the parent big enough for what floats in it |
| A float you wanted on top is under its siblings | A float draws over only the siblings before it | Append it last again after adding others |
| `ViewTest` fails on a cut, or nothing slides or fades on a change | The view rebuilds its children on each change | `Node.keyed`, and change the kept node in place ([Making it move](motion.md)) |
| A click or a landmark stops working after an update | The node it was registered on was replaced | Same: keep the node |
| `IllegalStateException` from `gui.hear`: the node already has a handler | The node is a widget's, or was heard for that kind already | Hear the widget's own message (`Button.PRESSED`, ...), and hear each node once per kind, when it is made |
| A click reaches the view but nothing changes | The view changed nothing itself and the component never answered | The component must hear the message (`@Hears`, and `Core`'s list) and send what to show |
| The view jumps when something is revealed | `scrollIntoView`, and navigating to a landmark, scroll at once | Do not reveal by scrolling; see [Making it move](motion.md#scrolling) |
| Two screenshots differ though the code did not change | The window came back at last run's size | `ottermate --size` |

## Read next

- **The template's `examples/later/`** (in a generated project): a `-core` component, its own messages and a
  `@View` over a panel of widgets, each in its own folder, with a headless test and an `ottermate` check.
- **VexelRay Inspector, `View.java`** (`vexelray-inspector-gui`, branch `v0.2`): a view of its own over plain nodes.
  It hears its lane's clicks and both nodes' sizes with `gui.hear`, and draws its diagram on a resize.
- **`vexelray-gui`'s `Gui.hear` and `NodeEvent`**: what each kind of event carries, and how a click bubbles.
- **`vexelray-gui`'s `Node.java` and `Keyed.java`**: the javadoc on `floatAt`, `scroll`, `hitInert`, `opacity`,
  `translate` and `keyed` says what each does and what it does not. [Coding in VexelRay IDE](in-vex.md) shows how to
  jump there from your code.
- **The Scheduler and Maistro** (`vexelray-scheduler`, `vexelray-maistro`) are 0.1 applications. Their
  `Schedule.java` and `MapPane.java` are where the timeline and the boxes over a drawing on this page were first
  worked out, and their `docs/framework-notes.md` say what each gap cost; read them for that, not for their wiring.

# Building a custom view

The widget shelf (`dev.vexelray.gui.widget`) has tabs, trees, tables, fields, sliders and the rest. Sooner or later
your application needs a view it does not have: bars placed on a timeline, boxes over a drawn map, cards on a
board. This page is how to build one from plain nodes so that it lays out, takes clicks, can be reached by a script
and does not jump. [Making it move](motion.md) is the companion page.

## A widget or your own view

| You want | Use |
| --- | --- |
| A list, tree, table, text field, tabs, a toggle, a slider | The shelf. Each one carries an invariant you would otherwise re-derive: a selection anchor, a virtualised row window, a claim on a chord |
| A button, a card, a heading | Your own nodes. The shelf leaves painted controls to the application on purpose (`widget/package-info.java`) |
| Things placed by a value: a time, a position on a map | Your own view, as below |

A custom view is an ordinary class beside `Ui.java`. It builds its nodes in its constructor, hands `Ui` one node to
place, and has a `show` method that `Ui.show` calls with what it derived from the `Doc`.

## Three things to know first

Each of these was found the hard way while building the [Scheduler](#read-next), and is written up in its
`docs/framework-notes.md`.

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
3. **Keep the nodes and change them.** Building the children again on every model change is the easy way and the
   wrong one. A rebuilt node is a new node, so nothing can slide it or fade it. Its handlers, focus and landmark
   are also dropped with it, because they are keyed by node id. Keep a map from your item's key to its node.
   On a change, make the new ones, remove the gone ones, and change the rest in place.

## A worked example: bars on a lane

One lane, a bar for each item at its place along the lane. Each bar is a real node with a click handler, a role,
keyboard focus and a landmark, so a script clicks the same thing a person does. This is a trimmed copy of the
Scheduler's `Schedule`.

First the names, in `Landmarks.java`. A landmark per item is a method, as the Scheduler's `Landmarks.visit` is.
A name may not contain `/`.

```java
/** The timeline. */
static final String TIMELINE = "timeline";

/** A bar on the timeline, by its item's id: {@code item.standup}. */
static String item(String id) {
    return "item." + id;
}
```

Then the view:

```java
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import sibarum.tactroller.api.Key;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** One lane, with a bar for each item at its place along it. */
final class Timeline {

    /** One bar: where it starts and ends, as fractions of the lane from 0 to 1. */
    record Item(String id, String label, float from, float to) {
    }

    private record Bar(Node box, Node label) {
    }

    private static final Length BAR = Length.rem(2.1f);
    private static final Length BAR_TOP = Length.rem(0.6f);

    private final Gui gui;
    private final Consumer<String> picked;
    private final Node lane;
    private final Map<String, Bar> bars = new HashMap<>();
    private final Color ground;
    private final Color ink;

    Timeline(Gui gui, Consumer<String> picked) {
        this.gui = gui;
        this.picked = picked;
        this.ground = gui.theme().color(Role.RAISED);
        this.ink = gui.theme().color(Role.INK);
        lane = gui.box().width(Length.FILL).height(Length.rem(3.3f)).scroll(false, false).clip(true);
        gui.landmark(Landmarks.TIMELINE, lane);
    }

    Node node() {
        return lane;
    }

    /** Bring the lane to {@code items}: new bars made, gone ones removed, the rest changed in place. */
    void show(List<Item> items) {
        gui.batch(() -> {
            Set<String> here = new HashSet<>();
            for (Item item : items) {
                here.add(item.id());
                Bar bar = bars.get(item.id());
                if (bar == null) {
                    bar = make(item.id());
                    bars.put(item.id(), bar);
                    lane.append(bar.box());
                }
                bar.label().text(item.label());
                place(bar.box(), item);
            }
            for (var it = bars.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!here.contains(e.getKey())) {
                    e.getValue().box().remove();
                    it.remove();
                }
            }
        });
    }

    /** The bar for {@code id}, or null: for a test. */
    Node bar(String id) {
        Bar bar = bars.get(id);
        return bar == null ? null : bar.box();
    }

    private Bar make(String id) {
        Node label = gui.text("").textColor(ink).wordWrap(false).hitInert(true);
        Node box = gui.row().alignItems(AlignItems.CENTER).padding(Length.ZERO, Length.dp(7))
                .corner(Length.dp(6)).background(ground).role("button")
                .scroll(false, false).clip(true)
                .append(label);
        gui.onClick(box, () -> picked.accept(id));
        gui.focusable(box, true);
        gui.claim(box, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, () -> picked.accept(id));
        gui.landmark(Landmarks.item(id), box);
        return new Bar(box, label);
    }

    /** Float the bar at its fraction of the lane, kept inside the lane, since a float is clamped into it anyway. */
    private static void place(Node box, Item item) {
        float from = Math.clamp(item.from(), 0f, 1f);
        float to = Math.clamp(item.to(), from, 1f);
        box.floatAt(Length.percent(100 * from), BAR_TOP).size(Length.percent(100 * (to - from)), BAR);
    }
}
```

Place it in `Ui`'s constructor, and call it from `Ui.show`:

```java
timeline = new Timeline(gui, model::pick);          // pick is yours: it commits the choice to the model
gui.root().direction(Direction.COLUMN)
        .background(gui.theme().color(Role.PAGE))
        .children(titleBar.node(), timeline.node(), split.node(), status.node());
```

What each part is doing:

- **Placed by percent.** A `Length` has no pixel unit, and a percentage of the lane stretches with the window, so
  nothing is measured. The Scheduler places its visit bars this way; `MapPane` in Maistro converts its pixel
  coordinates to percent of its layer for the same reason.
- **`gui.batch`.** `show` runs on the committing thread, which is not the main thread. A batch posts every edit in it as one
  mutation, so no frame draws half of a change. `Model.onChange` delivers one change at a time, so only `show` touches the
  map and it needs no lock.
- **The label is `hitInert`**, so the bar, not the text in it, is the pointer's target.
- **`remove()` releases the node's registrations**: its click handler, focus and landmark go with it.

### Boxes over a drawing

When the thing under the boxes is drawn (a map, a graph), draw it as one `Picture` on a layer and float a box over
each item that should take clicks. `MapPane` in `vexelray-maistro` is the example. It uses one layer with no
padding, redraws the picture on `gui.onResizeUi(layer, ...)`, and places each box with `floatAt` and `size` in
percent of the layer's pixel size, computed from the same numbers as the drawing. Then what is drawn and what is hit
cannot disagree. Each box is `role("button")`, has a click handler and a landmark (`Landmarks.mapNode(id)`), and
holds a hidden label that names it.

## Making it reachable

| For | Do | Checked by |
| --- | --- | --- |
| A script | `gui.landmark(name, node)` with a name from `Landmarks.java` | `click item.standup` in `ottermate` |
| A reader of the tree | `node.role("button")` on the node a person would say they pointed at, with its text inside it | `tree` lists the role and the name |
| The keyboard | `gui.focusable(node, true)`, and a claim on Enter (and Space) while focused, as `widget/Button.java` does | `key TAB` until `tree` shows `focused` on a bar, then `key ENTER` |
| Waiting for a state | Put the state in an accessible name, as the template's `status.file` does | `await <landmark> <text>` |

A role changes nothing drawn or hit; it is what an agent, a test or a screen reader reads. The renderer draws the
theme's focus ring round whatever holds focus (`Node.focusRing` overrides it). The Scheduler's bars and Maistro's
map boxes are not focusable; decide for your view.

## Testing it

**Headless**, in the same test class as the template's `EditorTest`. `VexelApplication.tree` runs the generated
wiring as far as the tree, with no window and no GPU. Give `Ui` a `timeline()` accessor and test that a bar is kept
across a change:

```java
@Test
void aBarIsMovedNotRebuilt() {
    Timeline line = wiring.ui().timeline();
    line.show(List.of(new Timeline.Item("standup", "Stand-up", 0.1f, 0.2f)));
    Node first = line.bar("standup");
    line.show(List.of(new Timeline.Item("standup", "Stand-up", 0.3f, 0.4f)));
    assertSame(first, line.bar("standup"));
    line.show(List.of());
    assertNull(line.bar("standup"));
}
```

That proves the bookkeeping and the model path (drive the model as `EditorTest` does). Where things are on screen
is for `ottermate`.

**With `ottermate`**, by landmark:

```
settle
tree
click item.standup
await status.message Stand-up
key TAB
tree
shot C:/work/timeline.png
```

This assumes picking an item writes its name to the status line. Run it with
`ottermate --size 1600x900 --script timeline.txt --launch mvn.cmd compile exec:exec -Dautomation=0`. The window
remembers its last size, so give `--size` whenever you compare screenshots. In the `tree` output, `clipped to` and
`offscreen` show a bar that is there but cannot be hit.

## Traps

| Symptom | Cause | Fix |
| --- | --- | --- |
| A small scrollbar pill inside a bar or card | Scrolling is on by default for containers, and the content is bigger than the box | `scroll(false, false).clip(true)` on every box that can be smaller than its content. The Scheduler's `Ui.still` does exactly this |
| A scrollbar flashes for one frame during an animation | Same, mid-animation: something overflowed for a frame | Same fix, on the box that overflowed. Lay out regions whose contents change so they never need to scroll |
| A float sits somewhere other than where you put it, or is squeezed | A float is clamped inside its parent | Keep `from + width` within 100%; make the parent big enough for what floats in it |
| A float you wanted on top is under its siblings | A float draws over only the siblings before it | Append it last again after adding others (the Scheduler's `raiseLines`) |
| Nothing slides or fades on a change; everything cuts | The view rebuilds its children on each change | A map from key to node; change in place ([Making it move](motion.md)) |
| A click or a landmark stops working after an update | The node it was registered on was replaced | Same: keep the node |
| A bar appears or vanishes in one frame | Nothing in the framework animates arrival or departure yet | `appear` and `leave`, in [Making it move](motion.md#arriving-and-leaving) |
| The view jumps when something is revealed | `scrollIntoView`, and navigating to a landmark, scroll at once | Do not reveal by scrolling; see [Making it move](motion.md#scrolling) |
| Two screenshots differ though the code did not change | The window came back at last run's size | `ottermate --size` |

## Read next

- **`vexelray-scheduler`, `vexelray-scheduler-app`** (in the stack's checkouts; not yet published): `Schedule.java` is the timeline this page trims (lanes,
  visit bars floated by percent, kept by visit id across changes, each with a click handler and a landmark).
  `Ui.java` has `still`. `docs/framework-notes.md` lists each gap the retrospective found, with what it cost.
- **`vexelray-maistro`, `MapPane.java`**: boxes floated over a drawn graph, one per node, each a button with a
  landmark, kept by node id and removed when the node leaves the graph.
- **`vexelray-gui`'s `Node.java`**: the javadoc on `floatAt`, `scroll`, `hitInert`, `opacity`, `translate` and
  `append` says what each does and what it does not. [Coding in Vex](in-vex.md) shows how to jump there from your
  code.

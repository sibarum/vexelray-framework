# Making it move

**Nothing on screen changes in one frame.** A colour fades, a moved node slides, an arriving node comes up from
nothing, a leaving one goes down to nothing before it is removed, and changed words dip out and come back. This
page says which of those the framework does for you, which you write, and how to check them. It assumes you have
read [Building a custom view](custom-view.md), and above all its rule: keep the nodes and change them. A node that
is rebuilt cannot be animated, because the node that would move is not the one on screen.

The worked example is `vexelray-scheduler` (in the stack's checkouts; not yet published). Its `Motion.java` has one instrument per kind of change, and its
README's *Motion* section is the short version of this page.

## What the framework already does

| Change | Done by | Timing |
| --- | --- | --- |
| Any colour written to a node: hover, press, selection, theme | `WindowMotion` attaches `Fades` with `gui.paintMotion`, in every window | 120 ms, linear |
| A cut-off name revealed (a tree row, a table cell) | `WindowMotion` sets `Unclip`'s motion | 150 ms, in-out cubic |
| A node that layout moved: a row pushed down, a card reordered | `WindowMotion` attaches `Transitions`, enrolled over the whole tree | 180 ms, out cubic |
| A node added, and a node removed | `WindowMotion` attaches `Presence` as the enter and exit motion, over the whole tree | 140 ms, out cubic, rising 0.35 em |
| The focus ring moving to another control | `TactrollerInputBackend.pointerFeel`: `gui.focusFade` | 120 ms |
| The mouse wheel | `TactrollerInputBackend.pointerFeel`: `gui.scrollGlide` | 45 ms half-life |
| A shelf widget's own motion: `Tabs.slide`, `TreeView.motion`, `Cues` | Only when handed a `Ramp`. The template's `Motion.java` makes them from `KronoGui` | 160 ms, 240 ms for a cue |

Since 2026-10-24 moves, arrivals and departures are on by default in every window the framework builds, the main one
and every named one alike. Three things are deliberately not motion, so a whole window can be enrolled without
seasickness: a resize or a zoom (the window moving under the user's hand), a node moved on every frame by a drag or
by someone else's ramp (followed, not chased, after its first two frames), and the children of a container that says
`childMotion(false)`. `ListView` (and so `Table` and `Select`) says it, because its rows are a view the user scrolls,
and so do `TreeView` and `Board`, which move their own rows. Say it on a container of yours that animates its
children itself, or whose children track something drawn. A node whose colour is data rather than state (a cell
coloured by its value) says `paintCuts(true)`, and a colour an application ramps by hand, frame by frame, is followed
rather than faded behind. A node shown again with `visible(true)` appears where it is; it does not slide from where
it was hidden.

**A panel rebuilt whole is a replacement.** Removing everything a container holds and appending new nodes is the
commonest way to redraw one, and the framework treats it as a replacement: the old children are lifted out of the
flow and fade out where they stood, in half the time, and the new ones take their places and fade in after. No
doubling, and no two pages seen through each other. A container that keeps any child is edited, not replaced: a
removed row keeps its place while it fades, and then the gap closes.

**What default motion still asks of you: keep the nodes and change them.** A row that is rebuilt cannot slide to its
new place, because the node that would move is not the one on screen. Draw a list with `Node.keyed` and it keeps,
moves, adds and removes rows by key.

Everything else cuts unless you make it move. The rest of this page is how.

## The three instruments

**Colour: `Fades`.** Already attached, nothing to call. A change mid-fade starts from the colour being drawn, so
it never jumps. To change its timing (to slow it down for checking, say), attach your own. One source per tree,
and the last call wins:

```java
gui.paintMotion(Fades.on(krono, Dur.ms(140), Ease.LINEAR));
```

**Layout moves: `Transitions`.** Already attached, over the whole window. A `Transitions` is a `LayoutMotion`: when
layout puts a node somewhere new, it draws the node at the old place and decays the difference to zero. A node moved
again mid-slide continues from where it is drawn. To time a tree differently, attach your own, which replaces the
framework's. Enrolment is per node: `follow` (one node), `followChildren` (a container's direct children) or
`followSubtree` (everything under a root, at any depth); the nearest enrolment wins, and `Dur.ZERO` enrols a node
that moves at once.

```java
Transitions moves = Transitions.on(krono);
gui.motion(moves);                                       // one LayoutMotion per tree, last call wins
moves.followSubtree(gui.root(), Dur.ms(200), Ease.OUT_CUBIC);
```

A new `floatAt` percentage is a move, and so is a node appended to another parent. In the Scheduler, a visit given
to another clinician is the same bar appended to another lane, and it slides there (`Schedule.Page.update`). The
displaced position is also where the node is hit, so what the pointer clicks is what is drawn.

**Anything else: `KronoGui.ramp`.**

```java
krono.ramp(Dur.ms(140), Ease.OUT_CUBIC, p -> node.opacity((float) p), () -> { });
```

`ramp(Dur over, Ease ease, DoubleConsumer progress, Runnable done)` calls `progress` once a frame from 0 to 1, then
`done` once. Both ends are delivered exactly, and `done` runs the frame after the one that drew 1. It is safe to
call from a handler, because it goes through `onTimeline`. The template's `Recipes.ui` already takes a `KronoGui`.

Drive the visual properties, never the layout ones. `opacity`, `translate` (in ems), `rotate` and `scale` move
nothing and measure nothing, so they are cheap every frame. A size changed every frame moves everything after it
every frame and lays the window out every frame. `Transitions` follows such moves after their first two frames rather
than chasing them, so the picture holds together, but the first two frames still lag a little and the layout cost is
paid throughout. Make room the Scheduler's way. The arriving node takes its full place at once, invisible, and the
nodes after it slide.

## Arriving and leaving

Done for you. `Presence` is the window's enter and exit motion: a node added after the first frame fades up from
nothing and rises 0.35 em into its place while the nodes after it slide aside, and a node removed keeps its place,
stops taking the pointer at once, fades out, and then leaves the tree so the rest slide into the gap. Only the top of
anything added arrives, so a card built and then appended fades in as one. A node put back before it had gone is the
same node, coming back up from where it got to, with its handlers and state.

So in your view's `show`, append a new key's node and `remove()` a gone one, and nothing more. `Node.keyed` does
both for a list: `rows.reconcile(items, update)` keeps every key still present, builds one for a new key, and removes
the rest. The Scheduler's hand-written `Motion.appear` and `Motion.leave` predate this, and a new application
should not copy them.

### A region replaced whole

When a whole page changes (another day, another document), `Motion.Stage` lifts the old page out of the flow with
`floatAt(0, 0)` where it stands, fades it out, and then fades the new one in. They go one after the other, because
two similar pages seen through each other are a smear. Two conditions come from the float rule. The stage region
has no padding, and it fills what its parent leaves. If it were shorter than the old page, the float would be
squeezed and grow a scrollbar for a frame (the Scheduler's FN-5).

## Text

`Fades` blends a text colour, not the text. Changed words are drawn in one frame. The Scheduler's `Motion.Label`
dips the node's opacity out, swaps the words, and brings it back. It can be interrupted. It is used for the day's
name, the status line and button labels:

```java
Motion.Label count = motion.label(status.slot(Landmarks.STATUS_COUNT),
        s -> status.text(Landmarks.STATUS_COUNT, s));
count.set(n + " visits");          // the first set is at once; later ones dip
```

`TitleBar.title` is the one label this cannot reach, because the framework draws it. So keep in the title only
words that do not change as the user works.

## Zoom

`Gui.zoomIn` and `zoomOut` multiply the zoom by the step at once, which lays the whole window out again between two
frames. The template's `Recipes.zoomShortcuts` binds them as they are. To glide, drive `Gui.zoom(float)` instead,
as the Scheduler's `Recipes.shortcuts` does. It uses a `Motion.Glide` from `gui.zoom().value()`. Each key resets the
glide to the framework's value while it is at rest (the framework restores last run's zoom and clamps every value
to `Appearance.ZoomRange`), then glides to its target times or divided by 1.25. A key pressed mid-glide steps from
where the glide is heading, so three presses are three steps.

## Scrolling

`scrollIntoView` scrolls at once, and so does `scrollTo` ("at once, not glided"). A walk to a landmark
(`gui.navigate`) reveals through `scrollIntoView` too. Nothing glides a reveal yet. The Scheduler stopped revealing
anything: the explanation that had to be scrolled to, under the lanes, moved into the briefing beside them. The wheel's glide is the
framework's, above.

Scrollbars appear in one frame and narrow what they scroll. So any box whose contents change should not be able
to scroll at the sizes you design for. Turn scrolling off and clip
([Building a custom view](custom-view.md#three-things-to-know-first)).

## Checking it

`settle` waits for the frame loop and then for the clock to go quiet, so a `shot` after it is the window at rest.
That proves where things end up. It does not prove how they got there. A transition is over in a fifth of a
second, too quick for a screenshot to catch in the middle. What works today is the Scheduler's way:

1. **A slow-motion switch.** `Motion` reads `-Dscheduler.slowmo` once, at start, and multiplies every duration it
   makes by it, including the `Fades` it attaches itself. Give your `Motion` the same, under your own property
   name. The template's pom passes `app.jvmArgs` through:
   `mvn compile exec:exec -Dautomation=0 "-Dapp.jvmArgs=-Dmyapp.slowmo=12"`.
2. **A burst of shots.** In a script, the change (a `click`, a `key`) followed by several `shot`s with no `settle`
   between them. How far apart they land depends on how long each `shot` takes, so slow things down enough that
   several fit inside one transition.
3. **Read the burst.** A slide is a long run of similar steps; a jump stands alone, much larger than the steps on
   either side.

Run with `ottermate --size` every time. The window remembers its last size, and two bursts at different sizes
cannot be compared. Slow motion does not reach the framework's focus ring or wheel. They keep real time and show up
in a slow burst as steps that are not there at full speed.

There is no `ottermate` verb that steps frames or records them. A recorder is planned ([TODO.md](../TODO.md), *The
camera records as well as photographs*). Until it lands, the burst is the check, and a person reads it.

## Traps

| Symptom | Cause | Fix |
| --- | --- | --- |
| Nothing slides | Your own `gui.motion(...)` replaced the framework's and does not enrol the node | `followSubtree(gui.root(), ...)` on yours, or attach none |
| A list cross-fades whole on every change instead of moving the rows that changed | The view rebuilds its children, so the framework sees a replacement | `Node.keyed`: keep a node per key and change it ([Building a custom view](custom-view.md)) |
| A button rebuilt beside kept siblings shows twice for a moment | The container kept something, so it is an edit: the old button keeps its place while it fades | Keep the button and change it, or move it with `append` |
| Your own animation of a container's children fights the window's | Both move the same nodes | `childMotion(false)` on that container |
| Rows fade in as a list of your own scrolls | It realizes rows as it scrolls and does not say so | `childMotion(false)` on the container whose children it recycles |
| A pooled marker passes through its last owner's colour | Its colour is data, and colours fade | `paintCuts(true)` on that node |
| A fading-out node you faded by hand still takes clicks | Opacity is visual only | `remove()` it and let the framework fade it; or `hitInert(true)` as it starts to leave |
| The page wobbles at the start of something growing | A size animated frame by frame: the first two frames are chased | Take the full size at once, fade it in, let the rest slide |
| A scrollbar flickers in mid-animation | Scrolling is on by default; something overflowed for a frame | `scroll(false, false).clip(true)` on that box |
| An outgoing page is squeezed during a crossfade | A float is clamped inside its parent | The stage region fills its parent and has no padding |
| Words change in one frame | `Fades` does not cover text | A `Label`: dip out, swap, dip in |
| The view jumps to show something | `scrollIntoView` (and navigation) scroll at once | Do not reveal by scrolling |
| Zoom keys re-lay the window in one frame | `zoomIn`/`zoomOut` step | Glide `Gui.zoom(float)` |
| A slow-motion burst shows a step at full speed it does not have | The framework's ring and wheel are not slowed | Ignore those two; read your own transitions |
| Two screenshots differ at the same code | The window came back at last run's size | `ottermate --size` |

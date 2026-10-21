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
| Any colour written to a node in the main window: hover, press, selection, theme | `VexelApplication` attaches `Fades` with `gui.paintMotion` | 120 ms, linear |
| A cut-off name revealed (a tree row, a table cell) | `VexelApplication` sets `Unclip`'s motion | 150 ms, in-out cubic |
| The focus ring moving to another control | `TactrollerInputBackend.pointerFeel`: `gui.focusFade` | 120 ms |
| The mouse wheel | `TactrollerInputBackend.pointerFeel`: `gui.scrollGlide` | 45 ms half-life |
| A shelf widget's own motion: `Tabs.slide`, `TreeView.motion`, `Cues` | Only when handed a `Ramp`. The template's `Motion.java` makes them from `KronoGui` | 160 ms, 240 ms for a cue |

Everything else cuts unless you make it move. The rest of this page is how.

## The three instruments

**Colour: `Fades`.** Already attached, nothing to call. A change mid-fade starts from the colour being drawn, so
it never jumps. To change its timing (to slow it down for checking, say), attach your own. One source per tree,
and the last call wins:

```java
gui.paintMotion(Fades.on(krono, Dur.ms(140), Ease.LINEAR));
```

**Layout moves: `Transitions`.** Not attached by the framework. A `Transitions` is a `LayoutMotion`: when layout
puts a node somewhere new, it draws the node at the old place and decays the difference to zero. Enrolment is per
node: `follow` (one node), `followChildren` (a container's direct children) or `followSubtree` (everything under a
root, at any depth). The nearest enrolment wins. A node moved again mid-slide continues from where it is drawn.
`Dur.ZERO` enrols a node that moves at once, which is the reduced-motion setting.

```java
Transitions moves = Transitions.on(krono);
gui.motion(moves);                                       // one LayoutMotion per tree, last call wins
moves.followSubtree(page, Dur.ms(200), Ease.OUT_CUBIC);  // page: the root of your view
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
every frame, and inside a followed region `Transitions` chases each of those moves with a lag of its own: two
clocks, pulling the picture apart. Make room the Scheduler's way. The arriving node takes its full place at once,
invisible, and the nodes after it slide.

## Arriving and leaving

Nothing in the framework animates a node's arrival or departure today. `LayoutMotion` reports nothing for a first
appearance, by design, and `remove()` takes a node out in the frame it lands. So write two methods. The Scheduler's,
trimmed:

```java
/** Bring node up from nothing, rising into place. Call in the batch that adds it. */
Node appear(Node node) {
    node.opacity(0f).translate(0f, RISE);
    krono.ramp(FADE, Ease.OUT_CUBIC,
            v -> node.opacity((float) v).translate(0f, (float) (RISE * (1d - v))), () -> { });
    return node;
}

/** Take node down to nothing, then out of the tree. It stops taking the pointer at once. */
void leave(Node node) {
    node.hitInert(true);
    krono.ramp(FADE, Ease.LINEAR, v -> node.opacity((float) (1d - v)), node::remove);
}
```

`RISE` is 0.35 (ems) and `FADE` 140 ms there. `hitInert` matters: a node at opacity 0 still occupies its box and is
still under the pointer. The real `Motion.appear` and `Motion.leave` do one thing more. They keep a map of each
node's opacity `Glide` by node id, so a node that leaves before it has finished arriving goes down from where it
got to, not from fully there. Copy those, with `Motion.Glide`, rather than the trimmed version above.

In your view's `show`, a new key gets `appear` after it is appended, and a gone key gets `leave` instead of
`remove()`. Drop it from your map at once, as `Schedule` does, so a later `show` makes a fresh node if the item
comes back.

**Check `vexelray-gui` first.** A framework `EnterMotion` and `ExitMotion`, enrolled the way `Transitions` is, and
a reconcile for keyed children are proposed but not in the framework yet. When they exist, use them instead.

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
| Nothing slides | No `Transitions` attached, or the node is not enrolled | `gui.motion(moves)` once, and `followSubtree` on your view's root |
| Everything cuts on every change | The view rebuilds its children | Keep a node per key and change it ([Building a custom view](custom-view.md)) |
| A list pops when it grows or shrinks | Arrival and removal are not animated by the framework | `appear` after append, `leave` instead of `remove()` |
| A fading-out node still takes clicks | Opacity is visual only | `hitInert(true)` as it starts to leave |
| The page tears or wobbles while something grows | A size animated inside a followed region: two clocks | Take the full size at once, fade it in, let the rest slide |
| A scrollbar flickers in mid-animation | Scrolling is on by default; something overflowed for a frame | `scroll(false, false).clip(true)` on that box |
| An outgoing page is squeezed during a crossfade | A float is clamped inside its parent | The stage region fills its parent and has no padding |
| Words change in one frame | `Fades` does not cover text | A `Label`: dip out, swap, dip in |
| The view jumps to show something | `scrollIntoView` (and navigation) scroll at once | Do not reveal by scrolling |
| Zoom keys re-lay the window in one frame | `zoomIn`/`zoomOut` step | Glide `Gui.zoom(float)` |
| A slow-motion burst shows a step at full speed it does not have | The framework's ring and wheel are not slowed | Ignore those two; read your own transitions |
| Two screenshots differ at the same code | The window came back at last run's size | `ottermate --size` |

# Units of position and size

An audit of every public API on the stack that takes or returns a position, size, offset or scale, done on
2026-10-05 after the text editor's [FN-20](../../text-editor-vexel-demo/docs/framework-notes.md) put it as
*"the public geometry API speaking three units"*. It turned out to be more than three. Below: what exists, what is
wrong today, why most of it is invisible, and a recommended unified system. **Nothing here is ruled yet.** The
proposal at the end is a recommendation for the user to accept, change or reject, and each decision in it says
which [v1](v1.md) question it answers.

## The short version

- **The write side is mostly coherent.** `Length` (em, rem, dp, %, vw, vh) is a good model, and the rule for
  em versus dp ("glyphs scale with zoom, chrome does not") is written down in `vexelray-gui`'s
  architecture.md §6.
- **The read side has no units at all, only px.** Every number an application reads back is a bare `float` in
  px: `NodeLayout`, events, `Table.columnWidth`. But "px" means at least four different spaces, and the one
  number that converts between px and what the application wrote is private: `FlexLayout.emBasis`.
- **Conversion is therefore done by hand, in eleven places.** Five widgets and one application convert px back
  into a `Length`, and four widgets rebuild a `LayoutContext` from three getters. Three of those conversions are
  wrong.
- **None of the bugs show, because `dpi` is always 1.0.** Nothing feeds the display scale into `Gui.dpi`, by
  design until engine gap E4 lands (`vexelray-gui` architecture.md §"E4"). While dpi = 1, px = dp, and every
  confusion of the two is invisible. **They all surface the day E4 is wired**, which is why this is a v1 question
  and not a polish item.

## The spaces that actually exist

The docs use "px", "pixels", "root space", "client space", "screen px", "logical" and "physical" for these, and
not consistently. Naming them is half the fix.

| Space | What it is | Who speaks it |
| --- | --- | --- |
| **layout px** | Root-space pixels after `rootEmPx · zoom · dpi`, origin at the window's drawable top-left. Equal to tactroller's `CLIENT` space and to the canvas, but only while dpi = 1 | `NodeLayout`, `Rect`, `ClickEvent`, `DragEvent`, `DragState`, `Drop`, `HitTest`, `TextMetrics`, `Gui.viewport`, `WindowControls.width/height/resize`, automation's `tree`/`click`/`resize`, `Table.columnWidth` |
| **box-local px** | Layout px with the origin at a node's top-left | `Picture`/`Sketch` marks, `Cue.Box`, `DropIndicator.paint`. `TitleBar`'s instrument mark is handed a root-space `Rect` instead (below) |
| **run-local px** | Measured from the start of a text run | `TextMeasurer.offsetAt` (every other text API takes layout px) |
| **density-1 px** | `rootEmPx · zoom`, no dpi: that is, dp | typeset's `Placed`, `ToneMap` and `TypesetBlock` (documented as "pixels") and `Tooltip.GAP_PX` (documented as "px at density 1") |
| **dp** | `v · dpi`, no zoom | `Length.dp`, `SplitPane.sizeDp`/`onResize`, and every widget's `Length.dp(px / dpi)` |
| **em-multiple** | A bare float × the root em (`rootEmPx · zoom · dpi`) | `Node.translate`, `Rail.slide(travel)`, `Tabs`'s travel |
| **window outer px** | The OS outer rect including the frame, in the process's DPI-awareness space (logical while unaware) | `@VexelApp.width/height`, `AppInfo`, `GuiApp(title, w, h)`, `WindowConfig`, `requestPopup`, `Modal.size`, `WindowMemory` |
| **screen px** | Virtual-desktop coordinates | `WorkArea` ("physical"), `NativeWindow.screenX/Y`, `WindowMemory`'s `x`/`y` |
| **notches** | Wheel detents; 1.0 per click | tactroller's `Scrolled`, automation's `scroll`; × 3 em in the dispatcher |
| **fractions** | 0..1 | `DragEvent.fractionX/Y`, `Slider`, `Ramp`, `Cue.at`, `ImageRegion`, plot's `Frame` (downward) and `Camera.project` (**upward**) |

## What an application writes, reads and animates in today

| Operation | Unit | Example |
| --- | --- | --- |
| Lay out | `Length` | `width(rem(16))`, `padding(dp(6))` |
| Read where something is | layout px | `node.layout().rect()` |
| React to the pointer | layout px | `DragEvent.x/dx/nodeX` |
| Animate a displacement | em-multiple (`translate`), or layout px (`LayoutMotion`) | `Workspace.slideMarkTo`, `Transitions.moved` |
| Animate a size | `Length`, but only within one unit class | krono `Lengths.LERP` steps rather than blends `em(1) → rem(2)` |
| Place an overlay at a point | `Length.dp(px / dpi)`, by hand | `ContextMenu`, `Tooltip`, `Select`, `DragChapter` |
| Draw | box-local px | every `Picture` |
| Get a widget's size back | dp float (`SplitPane`), px float (`Table`) | `Ui.navigatorDp` |
| Size a window | window outer px ints for the first run, `Length` em for the minimum | template `App.W`/`H` vs `MIN_W_EM` |
| Persist | outer px (`WindowMemory`), px (mainframe `font.px`), nothing (text editor's navigator width) | |

## Defects found

Each was checked against the source. File paths are relative to the repo named.

### Wrong numbers, hidden by dpi = 1 or by default settings

1. **`SplitPane.size(Length)` resolves against a fake context** (`vexelray-gui-widget/.../SplitPane.java:174`).
   `LayoutContext.of(em*100, em*100)` hard-codes root em 16, zoom 1 and dpi 1, and its arguments only set the
   viewport. So `sizeDp()` and `onResize` misreport any em size once zoom ≠ 1 or dpi ≠ 1, and a `percent` size
   resolves to 0. Then `sized(pane)` calls `place(dp(sizeDp))`, so `size(percent(30)).sized(SECOND)` collapses
   the pane.
2. **`SplitPane`'s drag uses the same fake context** (`SplitPane.java:241`). The gutter, `minFirst` and
   `minSecond` resolve at zoom 1 and dpi 1, then get compared with real layout px. The default `rem(6)` minimum
   is 96 px at every zoom.
3. **A dragged `SplitPane` or `Table` column changes unit.** After a drag the size is stored as `dp(px / dpi)`
   (`SplitPane.java:252`, `Table.java:375/442/505`). A pane declared in rem (zooms) becomes dp (does not zoom).
   The text editor carries the same drift into its own state: the navigator starts as `rem(16)` and comes back
   from hide/show as `dp(navigatorDp)` (`Ui.java:123`).
4. **`Tooltip`'s gap is added before dividing by dpi** (`Tooltip.java:186`). It is documented as "px at
   density 1", so at dpi 2 it is 3 dp.
5. **Hand-built `LayoutContext`s use the window rather than the canvas** (`Select.java:481`,
   `ListView.java:347`, `Table.java:564`). Layout resolves vw/vh against the clamped canvas
   (`Metrics.java:143`), so the two disagree whenever `Gui.minSize` clamps.
6. **px constants that do not scale with density:** `InputDispatcher.DRAG_DISTANCE_PX = 6` (its neighbour
   `LOCK_EDGE_EPS_EM` is in em), `NodeLayout.CLIP_EPS = 0.5`, `DropIndicator.SEAM_MAX_PX = 4` (public),
   `TreeView.SEAM_PX`, `TreeRenderer`'s 2 px bevel floor, and `Modals.CHAR_WIDTH = 8` (which also ignores zoom).
7. **`Popout`'s window size ignores its own `size(Length)`.** It is hard-coded to `WindowConfig.of(title, 480,
   360)` (`Popout.java:395`), although its javadoc says the size is also the popped-out window's size. Its
   header is `dp(28)` "matching TitleBar's", but `TitleBar` uses `dp(32)`.
8. **`TitleBar` hands an instrument mark a root-space `Rect`** (`TitleBar.java:191`) where the contract says
   "the button's box". `Cue.Box` exists precisely to avoid handing out root-space x/y.
9. **Window geometry is persisted with no scale** (`WindowMemory.poll`). Once the process is DPI-aware, a window
   saved on a 200% monitor restores at twice its physical size on a 100% one.

### Docs that contradict the code

**Fixed 2026-10-05**, all of this list except one: in `vexelray-gui`, `vexelray` (`NativeWindow`, `WindowConfig`),
`tactroller` (`InputEvent`, `PointerState`), `vexplore` and here. `RetainedNode` also no longer calls layout px
"screen px". What was fixed is the docs alone, not any code. Still open: `docs/status.html` lists `zoom` and
`resize` as missing, and is left as the dated snapshot it says it is. Two items above are a mismatch between doc
and code where the doc states the intent, so they are defects rather than doc errors (`Tooltip.GAP_PX`, and
`Popout.size` claiming to set the window size).

- **`LayoutMotion.displacementX/Y` says "left of" and "above"** (`LayoutMotion.java:89-95`). The code adds it
  (`Displacement.java:117`), so a positive value moves the node right and down, as `Transitions` assumes.
- **`Placed` (typeset) says "zoom and DPI included"** (`Placed.java:18-21`). `TypesetBlock.basisPx()` deliberately
  leaves dpi out, and `Length.dp` adds it.
- **"Multiples of its own em"** (`Node.translate`, `PropKey`, `RetainedNode`, `Rail`, `Tabs`, architecture.md §7).
  `emPx` is the flat root em, the same on every node and independent of `textSize`, which is the opposite of
  the CSS meaning. The text editor's FN-20 conversion is correct, but only because the doc is wrong.
- **em and rem are identical in code** (`Length.java:25, 32`; architecture.md: "em = rem"). Yet krono treats
  them as different units, and its doc says "rem scales with zoom, dp deliberately does not" as if em differed.
  `Rail.slide` says "the panel's own em" and then "a rem or two".
- **`Gui.zoom()` says anything that does not move with zoom "is still pinned to device pixels".** dp exists
  precisely not to move with zoom.
- **`NativeWindow.width()` says "framebuffer width in pixels (after DPI scaling)".** The demo and the framework
  both document the canvas as logical, and dpi is held at 1 because of that.
- **tactroller's `InputEvent` says "virtual-screen pixels"** for coordinates that are in the selected
  `CoordinateSpace`, which is `CLIENT` here.
- **`Appearance.ZoomRange` says `Gui`'s own range is 0.25–4.** It has been 0.5–3 since `vexelray-gui` todo §6.3.
- **`Gui.viewport()` has no javadoc.** The one meant for it (`Gui.java:438`) is orphaned above `modifiers()`.
- **The `Length` doc's list of percent bases omits text size** (root em), elevation (own width) and `floatAt`
  (parent width for x, parent height for y). `FlexLayout.measure`'s list of basis-free units omits dp.
- **`Camera`'s class doc mentions a magnification it does not have,** and its `v` runs upward while plot's
  `Frame` and `Span` run downward.

### What applications pay

From the witnesses (`text-editor-vexel-demo`, `calculator-vexel-demo`, `mainframe`, `vexplore`,
`vexelray-sim-fluid`, `vexelray-gui-demo`):

- **The em basis rebuilt by hand:** `rootEmPx × zoom × dpi` in the text editor's `Workspace.java:384`, and in
  automation's `size`/`resize`.
- **px read back and turned into a `Length`:** sim-fluid's `fit()` converts px to percent so that it never has
  to convert to em, and uses the sidebar's px width as a stand-in for the em. The gui demo's `DragChapter`
  passes event px straight into `dp`, which is right only while dpi = 1.
- **Text sizes hard-coded in px inside pictures,** so they do not zoom: the calculator `Plot` (`11` fallback,
  `0.6 * text` glyph guess) and the gui demo's `Chart`. A picture has no way to ask for "0.75 em" in px.
- **Design px converted to rem in comments, and wrong:** vexplore's `Type.java` says `rem(1.0625)` is 18 px.
  It is 17.
- **Sizes persisted in px:** mainframe's `font.px`. Nothing persists em or dp.

## Recommended: one system

The principle, from FN-20: **an application reads in the units it writes.** px stays underneath. It is correct
for `NodeLayout` as a transport and hit-test model, and for pictures, which are drawn. But it should be
something an application can convert out of in one call, never by knowing the formula.

**U1 — One scale object, published.** `Gui.scale()` → `State<Scale>`, where `record Scale(float rootEmPx, float
zoom, float dpi, float canvasW, float canvasH)` answers `emPx()`, `dpPx()`, `px(Length)`, `em(float px)` and
`dp(float px)`, and `context()` gives the `LayoutContext` the last frame actually used. It replaces the private
`emBasis`, the four hand-built contexts (defect 5), the text editor's product and automation's copy. It is a
`State`, so a picture or a cached size can rebuild on change rather than polling three getters.
*v1:* additive, but it is what every other item uses, so it goes first.

**U2 — `NodeLayout` carries its scale.** Add `emPx` and `dpi` (and `scrollbarPx`) so a reader converts with no
`Gui` in hand: `layout.em(rect.x())`, and picture text as `layout.emPx() * 0.75`. That fixes the calculator's and
the demo's px label sizes.
*v1:* `NodeLayout` is a public record, so adding a component breaks its constructor and any deconstruction
pattern. **Do it before the freeze**, or make it a class first.

**U3 — `translate` takes a `Length`.** `translate(Length x, Length y)` accepts em and dp. The renderer resolves
it from values already baked onto the node: `emPx` exists, and `dpPx` would be one more float, so it is still
no layout pass and no allocation per frame. The float overload stays and means em. Fix the "own em" wording
wherever it appears.
*v1:* additive. The wording fix is free.

**U4 — Widgets report sizes as `Length`, in the unit the author used.** `SplitPane.size()` → `Length` and
`onResize(Consumer<Length>)`: a pane declared in rem stays rem after a drag (px ÷ the scale's em). The same goes
for `Table` columns. This removes `sizeDp`, fixes defects 1–3 at the root, and lets the text editor delete
`navigatorDp`.
*v1:* **breaking** (`sizeDp`, `onResize(Consumer<Float>)`, `columnWidth` in px). Before the freeze.

**U5 — Place at a point without converting.** Something like `Node.floatAt(Point)`, or a `Scale.at(x, y)`
returning the two `Length`s for a root-space point. It covers the context menu, tooltip, select popup and drag
ghost, which today each write `dp(px / dpi)` and silently assume they are root children.
*v1:* additive.

**U6 — Decide what em means, and write it down.** em and rem are the same today. Either:
- **(a)** declare it permanent: em is the root em, there is no cascade, ever. Then make krono blend `em ↔ rem`,
  and consider deprecating one of the two names; or
- **(b)** make em mean the node's own text size, as in CSS. That changes every widget's em padding (TreeView's
  indent, ContextMenu's gap, TextMetrics' insets) wherever the text size is not 1 rem.

Recommended: (a). A cascade is exactly the kind of change v1 promises never to make, so if it is coming it has
to come now, and nothing has asked for it.
*v1:* this is the decision with the most blast radius, and it costs nothing to make before the freeze.

**U7 — Constants scale with density.** Make `DRAG_DISTANCE_PX`, `CLIP_EPS`, `SEAM_MAX_PX`, `SEAM_PX`, the
bevel floor and `Tooltip.GAP_PX` dp. Fix `Tooltip`'s order of operations. Replace `Modals.CHAR_WIDTH` with a
measure.
*v1:* not API, except `DropIndicator.SEAM_MAX_PX`. It moves pixels only when dpi ≠ 1, which is never today.

**U8 — Window geometry is dp, and says so.** OS logical coordinates *are* dp: one unit per 1/96 inch on
Windows, one point on macOS. Name every `int width`/`x` on `WindowConfig`, `GuiApp`, `@VexelApp`, `Modal` and
`WindowMemory` as dp in the javadoc now. When E4 makes the process DPI-aware, divide by the monitor's scale
where the engine hands px up, so `WindowMemory` keeps storing dp and survives a monitor move. Separately, decide
whether `@VexelApp`'s first-run size should be in em like its minimum. Not on that list: `WindowControls.resize`
and automation's `resize`, which take drawable layout px and should stay that way.
*v1:* the persisted format is application data, so get it into dp before anything ships. The ints keep their
type.

**U9 — Fix the docs.** Fix the contradictions listed above, and adopt the space names from the table at the top
in javadoc: "layout px" rather than "root space", "client space" and "absolute screen px", which are used today
for the same thing.

**Not recommended:** a px `Length`. The rule against it (architecture.md §6) is right, and every case that
seemed to need one is U1, U2 or U5.

## Order

U6 and U9 cost nothing and settle vocabulary. U1 next: everything else uses it. U2 and U4 are the two
**breaking** ones and so the only ones the freeze forces. U3, U5 and U7 can follow at leisure. U8's javadoc is
now, and its arithmetic lands with E4. Nearly all of it is upstream in `vexelray-gui`. The framework's own share
is `Appearance`'s stale zoom doc, `AppInfo`/`@VexelApp` naming their unit, and the template's `App.W`/`H`.

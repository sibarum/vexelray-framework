# SDG, natively: full-colour signed distance graphics everywhere but text

*Proposed 2026-11-07, written by the coding agent at the author's ask: "native support for full-colour SDG everywhere
except fonts". Its five questions were answered the same day, each **accepted as recommended**: see
[Rulings](#rulings-wanted).*

SDG is specified in `vexelray-fields/docs/sdg.md` (1.0 draft) and exists today only as a library:
`vexelray-fields-sdg` (70830d6) reads and writes `.sdg`, draws it on the CPU as the reference, and makes one from an SVG
or by tracing a raster. Nothing in the stack draws one. This plan is how every place that draws graphics comes to
draw SDG on the GPU, with text left as it is.

## What the stack draws today

| where | what it draws | format | entry point |
|---|---|---|---|
| core canvas | fields and images | one uber-shader; a field is the red channel of the run's one image, in one tint | `Canvas.field` (vexelray-canvas `Canvas.java:644`), `CoreShadelets.FIELD`/`FIELD_FILL`, `CoreLooks.Primitive` |
| GUI icons | the icon set, file icons | mono SDF atlas + generated enum; multi-colour as `LayeredIcon` (N fields, N theme colours, N nodes) | icons-maven-plugin `bake`/`file-families`; `IconView.paint` → `Node.field` → `TreeRenderer` 942–954 |
| app marks | logo, About, splashes | `ArtPair` (MTSDF + colour square, `.vxpx`), read by `AboutIntro` with `Condense`; Pix's `Splash` drops the field half | `pair` goal in six app poms |
| OS marks | window, taskbar, tray | `.ico`/`.png` only, enforced by the processor | `VexelApp` mark, shell `Mark.java:110`, `Win32Window.setIcon` |
| images | Pix (Rasterware), previews, Glance, Tile | RGBA sheets, sRGB with mips | codec-image `Pictures`, `ImageView`, `Textures.show` |
| drawings | plots, diagrams | `Picture.Sink`: fill, outline, line, glyphs, no images | vexelray-gui-draw |
| assets | `.vxa` | `DistanceField` items, mono; nothing draws a `.vxa` | codec-asset `Asset` |
| **text (out of scope)** | glyphs | MSDF/SDF atlas on descriptor set 0 | `Canvas.text`, `CoreShadelets.MSDF`/`SDF`, `TreeRenderer.drawText` |

Text and everything else already share the uber-shader and vertex but part at the primitive and the descriptor set
(glyphs set 0, everything else set 1), so this plan touches nothing on set 0.

## The core idea: one SDG layer is one canvas element

A layer is a quad over its stencil's rectangle (spec §6 already allows this), so it fits the canvas as it is:

- **A new primitive, `SDG`,** a source and a painter in `CoreShadelets`, beside `FIELD`. The source reads the stencil
  (`sdf` in its channel, `msdf` as the median after filtering, spec §2.2); the painter reads the paint and lays it over,
  premultiplied. One vertex quad per layer; the GPU blends the layers in order, as it blends everything.
- **The layer's parameters go in the per-element record** (set 2, the shadelet tables): stencil kind and channel,
  range, scale, the paint's rectangle, its solid colour or role. The vertex keeps the stencil's UV, as a field's.
- **One page per run.** The canvas binds one image per run (set 1). If a layer's stencil and paint sit on the same
  page, one bind draws it, and a whole drawing on one page is one run. So the native reader asks the encoder for that:
  `Assembly` packs a layer's stencil and paint onto one page, and a drawing too large for one splits by page, a run
  each. This is an encoder rule, not a format change; a file that breaks it still draws, at one run per layer.
- **`Canvas.sdg(drawing, x, y, w, h, roles, opacity)`** lays the canvas rectangle over the drawing's canvas and
  pushes its layers. Pages are uploaded RGBA8 **UNORM, no sRGB decode** (spec §2: half of them are distances).
- **Checked against `SdgRender`.** The witness draws the fixtures of `SdgTest` (the SVG conversions, the traced
  raster, the magnified star) on the GPU and compares them pixel by pixel with the CPU reference, within a stated
  tolerance.

One thing to settle in the first slice: spec §2 says colour is blended as stored, sRGB-encoded, as browsers do. If
the stack's swapchain blends in linear light, the GPU will differ from `SdgRender` at every soft edge and every
translucent layer. The slice measures it and brings the answer back: change the spec, or the target.

## The slices

Each one is usable when done, and each has a witness.

1. **Canvas (core `vexelray`).** The `SDG` primitive, `Canvas.sdg`, page upload, the witness above. Excludes
   effects: outline, glow and shadow need MTSDF stencils, which spec §7 lists as *not yet*.
2. **GUI.** An `ImageSdg` node prop beside `ImageField`, `Node.sdg`, and a branch in `TreeRenderer` beside the field
   branch. `DeviceImages` uploads a drawing's pages once per device. Theme roles (ruling 3) are resolved here, per
   draw, so light/dark switching costs nothing.
3. **Icons.** The icons plugin bakes SDG: one drawing per icon per size, packed into shared pages, with the generated
   enum unchanged in shape. `IconSource` gives a drawing; `IconView` draws it. A mono icon is a one-layer drawing in
   the foreground role, so the whole set moves at once and draws as before. File icons' layered families become
   multi-layer drawings. Whether `LayeredIcon` and `ArtPair` go is ruling 4.
4. **Images and drawings.** codec-image's `Pictures` opens `.sdg` as a vector picture, so Rasterware, Glance, Tile and
   Vexplore's previews show one sharp at any zoom, with no rasterising. `Picture.Sink` gains a drawing op, so plots and
   diagrams can place one. `.vxa` gains a `drawings` list (appended, `@Field(since=…)`, as its compatibility rules ask).
5. **Marks.** `VexelApp` takes an `.sdg` mark. About, splashes and wordmarks draw it natively (Pix's `Splash` stops
   dropping the field). The OS needs pixels for window, taskbar and tray icons, so the build renders the ICO sizes from
   the `.sdg` with `SdgRender` (ruling 5); the runtime never rasterises.

Order: 1, then 2, then 3, 4 and 5 in any order. 3 is the biggest and the most visible; 5 is the smallest.

## Slice 1, as built

Built 2026-11-07 in `Spike/vexelray` (v0.2, 0.2.0-SNAPSHOT) and `Spike/vexelray-fields` (7a8e62b).

- **The shadelets.** `CoreShadelets.SDG_STENCIL` (source) and `SDG_PAINT` (painter) are added at the end of their
  roles in the core set, and `CoreLooks.Primitive.SDG` is their look. That look is at a fixed offset in every table
  (`CoreLooks.lookOffset`), so `Canvas.sdg` writes a layer's record with no look table set. Each layer carries 19
  floats in its own record: the stencil's rectangle, its channel, msdf or not, and pixels per value; then the paint's
  map from stencil to paint coordinates, its rectangle, and sampled or not. The vertex keeps the stencil UV. The
  colour is the solid paint, with its role resolved by the caller's `Canvas.Roles`, or white for a sampled one; its
  alpha carries the opacity.
- **One page per run, by re-packing, not by an encoder rule.** `Assembly` keeps colour on pages of its own, so every
  textured layer (gradients, traced textures) has its stencil and paint on two pages. So
  `SdgPages.colocated(Sdg)` (canvas, pure Java) copies each such pair side by side onto a new page when a drawing is
  loaded. Any file draws, and drawings that need no copy are returned as they are. `Canvas.sdg` skips, and says once,
  a layer that is still split. The plan above said such a file would draw at a run per layer: it cannot, since a run
  binds one image. Teaching `Assembly` to co-locate would make the copy unnecessary; it is not needed for anything
  to draw.
- **sRGB, settled.** The window swapchain is `B8G8R8A8_UNORM` in the sRGB colour space, and the pipeline blends
  `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`. So the GPU blends stored values, as spec §2 says, and neither the spec nor the
  target changes. Pages must be uploaded UNORM.
- **The witness.** `vexelray-shadelets` `SdgRenderTest` draws the badge (SVG: MSDF fills, an SDF stroke, a gradient)
  and its traced raster on the GPU, fitted and magnified 8×, and compares each pixel with `SdgRender`. Fitted, the
  worst is 2/255. Magnified, the worst is 5, with 47 of 36,864 pixels past 3: the GPU weighs texels in fixed point.
  The bound is ≤ 1% past 3 and none past 8. A role is checked too.
- **The version.** The core repo is 0.2.0-SNAPSHOT on v0.2, but nothing points at it yet. The v0.2 clones still pin
  `vexelray.version` 0.1.3-SNAPSHOT (gui, codec, framework and its template, the apps, `build/src`). Moving them is
  slice 2's first step, done together, since the template's generated poms and `bootstrap.sh` name it too.

## Slice 2, as built

Built 2026-11-07.

- **The version move.** The v0.2 clones build against vexelray 0.2.0-SNAPSHOT, each in a commit of its own:
  mainframe, phasearray, Pix, text-editor-vexel-demo, vex-suite-common, vexelray-codec (and its video-player demo),
  vexelray-framework (and the template's preset), vexelray-gui, vexelray-inspector, vexplore, and pontif-framework's
  anybox. vex-4mat is on `develop` and was left alone. `build/src` is a build's staging copy and was not touched.
  `GitHub/vexelray` has no v0.2 branch yet. Until `Spike/vexelray`'s v0.2 is pushed (two hops, as the others), a
  fresh `bootstrap.sh` from GitHub would install core 0.1.3 under clones that pin 0.2.0.
- **The node.** `Node.sdg(ImageSdg)` (prop `SDG`, not layout-affecting) draws the drawing fitted into the box, its
  aspect kept, over the image and the field and under the border. `ImageSdg.of(Sdg)` colocates the drawing and wraps
  each page as `Pixels`, so `DeviceImages` uploads it once per device, UNORM, as it does the icon atlas. Hold one
  `ImageSdg` per drawing: identity is what uploads.
- **Roles.** `SdgRoles` names every framework `Role` in lower case (`ink`, `accent`, `danger`, `panel`, ...), and
  adds `foreground` (ink), `muted` (dim) and `background` (page). `TreeRenderer` resolves each named paint against
  the frame's drawn theme, keeping the authored alpha, so a theme swap recolours it with everything else. A name not
  in the list draws as authored.
- **The witness.** `vexelray-gui-harness` `SdgCaptureTest` draws the badge in a background window with a real
  `GuiApp` and compares the capture with `SdgRender` over the page: 0 pixels past 3, worst 2/255. A star in
  `foreground` is the dark theme's ink, and after a swap the light theme's. The full vexelray-gui suite (3,283 tests)
  and the framework's build and tests are green on core 0.2.0.
- **Not yet:** loading a `.sdg`/`.sdgb` file into an `ImageSdg` in one call. The file API is being reworked in
  vexelray-fields (XML and intrabuf), so slice 4 (images) takes that up once it lands. For now an application reads
  an `Sdg` with `SdgFile` and calls `ImageSdg.of`.

## Slice 3, as built

Built 2026-11-07. The author chose the larger scope when asked: give SDG layers effects first, then move every icon.

- **Effects on an SDG layer.** `SDG_STENCIL` honours an offset: it moves the read by `uvPerPx`, two more
  per-instance floats. `Canvas.sdg` takes a `Look` (plus the other parts' per-instance values) for the drawing's
  back-most layer. The look's source must be `sdgStencil` and its fill `sdgPaint`; the canvas finds where the paint's
  values go with the new `LookTable.instanceAt`, and grows the quad by the look's reach. `vexelray-shadelets`
  `SdgEffectsRenderTest` draws one SDF in the icon grid's layout, as a field and as an SDG layer, plain, with glow,
  shadow, weight, outline and all four. The worst pixel is 1/255, and the test holds it to none past 2.
- **Texels on the GPU only.** `SdgDrawing` (canvas) is a drawing's layers and its pages' sizes, with no texels. An
  icon's drawing points into the atlas that is already uploaded, without reading it. `Canvas.sdg(Sdg, …)` still
  takes a whole `Sdg`.
- **The GUI.** `ImageSdg` now holds an `SdgDrawing` and adds three things:
  - a `tint`, the colour of paints in the role `tint`, faded by paint motion as a field's tint was;
  - a `look`, and its per-instance values.

  It also gains `ofField`/`ofFields`, the drawing of a field or of a layered icon's fields over their atlas cells,
  and `sdgLook`, which turns a field's look into a layer's.
- **The icons.** By default, `IconSource.sdg(px, tint)` and `LayeredIcon.sdg(px, dark)` are drawings over their
  atlas cells, so the suite's set, every application's baked set and the file-icon families draw as SDG with nothing
  regenerated. `IconView` draws one node (`Node.sdg`); a layered icon no longer floats a node per layer.
- **The witness.** `vexelray-gui-harness` `IconSdgCaptureTest` draws each icon twice in one window: as the field it
  was, and by `IconView` as SDG. The cases are small and large, plain and with glow and shadow, and three layered
  file-icon families stacked as `IconView` used to draw them. Inside each cell, every pair matches to 2/255.
  Outside the cells, the old path drew 11 faint pixels in two small icons: a field's quad runs 2 px past its cell
  (its AA pad) and samples the next atlas cell. An SDG layer reads nothing past its rectangle, as the spec says, so
  those pixels are gone.
- **What ruling 4 came to.** What was replaced is how icons are drawn: the field path and the stacked layer nodes.
  The atlases themselves are SDG pages now, still baked by the plugin, still one texture for every icon. Baking
  `.sdg` files instead would only add a copy of the same texels. `LayeredIcon` stays as the source of a layered
  icon's layers and colour rows; its stacked-node drawing is gone. `ArtPair` and `Condense` are slice 5's.
- Widget tests that told one mark from another by its field's region (`TableTest`, `TreeCheckTest`) now read the
  node's SDG stencil (witnesses-are-made-to-break).

## Slice 4, as built

Built 2026-11-07, on vexelray-fields 746eee5 (the format's XML `.sdg` and compiled `.sdgb`, by another session).

- **Pictures.** codec-image's `Pictures` opens `.sdg` and `.sdgb` by the compiled signature, an `<sdg>` root, or the
  name, from a file (an XML drawing's pages beside it are read) or from bytes in hand. Both forms are listed with
  the other picture extensions. `Sdgs.decode` makes a `Picture` per size asked, as any image, so caches, thumbnails
  and filmstrips take it unchanged. The sheet is a poster drawn by `SdgRender` (at most 1024 px,
  `-Dcodec.sdg.poster`), and `Picture.drawing` carries the drawing itself, one `ImageSdg` for every size. The
  identity reads "SDG drawing · 5 layers · 100 × 100".
- **ImageView** (codec-image) puts the drawing itself on its plate, sharp at every zoom. It falls back to the poster
  only while the pose mirrors it, since a layer cannot be read mirrored, and it refuses a zoomed "detail" part,
  which would only cover the drawing.
- **Picture.Sink** has a fifth operation, `drawing`, and `Picture.Drawing` and `Sketch.drawing` go with it.
  `CanvasSink` draws it natively, and the tree renderer hands it the theme's roles. `SvgSink` embeds it as a PNG
  drawn by `SdgRender` at twice the box, so an export never has a blank where the drawing was. The mark carries
  the drawing with its texels and the page handles to draw it with, so `ImageSdg` now keeps its `source`.
- **.vxa.** `Asset` minor 1 has `drawings`, each a `Drawing(name, sdgb)`: the compiled file's bytes, so the asset
  carries a drawing whole without copying its schema. Older files open with none; the golden 1.0 file checks it.
  The test's future schema moved to minor 2.
- **The witnesses.** `SdgPicturesTest` (codec-image) checks both forms and bytes in hand, that each poster is the
  reference at its size, that a broken file is a reason, and that an SVG is never taken for a drawing.
  `SdgCaptureTest` (harness) draws a drawing placed in a picture in a real window: worst 2/255 against the
  reference. The export holds it as a tagged image.
- **Rasterware does not show it natively yet.** Seen live: Rasterware opens `badge.sdg` ("SDG drawing · 5 layers"),
  but it draws the poster. It has its own view: `ReadPicture` turns a `Picture` into a `Feed` in the `Pictured`
  message, and `Ui` names the feed on `@picture`, sized by the poster's pixels. The fix touches Rasterware's
  `Messages.java`, which its CLAUDE.md asks to be proposed first: `Pictured` gains `Optional<ImageSdg> drawing`;
  `ReadPicture` sizes a drawing as an SVG decoded at `SIDE`; `View`/`Ui` name the drawing on the layer in place
  of the feed. Vexplore's previews, mainframe and the suite's `PictureViewer` (which does use `ImageView`) were not
  checked live.

## Rulings wanted

**Ruled 2026-11-07: all five accepted as recommended.** The core renderer is cloned to `Spike/vexelray` on `v0.2`;
the format is split into `vexelray-fields-sdg-format`; solid paints take a trailing role (spec 1.1); the mono atlases,
`LayeredIcon` and `ArtPair` are replaced once SDG draws the same things; OS icon sizes are rendered at build time.

1. **Where the core renderer's part is built.** The core repo `GitHub/vexelray` has no Spike clone and no v0.2
   branch: it is on `develop` at 0.1.3-SNAPSHOT, which the 0.1 apps and the v0.2 clones both pin. Slice 1 changes its
   shader, its vertex records and `Canvas`.
   *Recommended:* clone it into `Spike/vexelray` on a new `v0.2` branch from `develop`, and point the v0.2 clones at
   its 0.2.0-SNAPSHOT, as the other repos did. *Alternative:* build it on `develop`, additively, since nothing
   existing changes. That is cheaper, but it puts 0.2 work into the line the installer and live sites ship
   (installer-stays-0.1).

2. **How the stack reads `.sdg`.** Today the format lives in `vexelray-fields-sdg`, alongside the SVG and raster
   encoders and their dependencies (core, sdf, msdf, art).
   *Recommended:* split out `vexelray-fields-sdg-format`: the `Sdg` record and `SdgFile`, with no dependencies. The GUI,
   codec and canvas depend only on that, and the encoders and `SdgRender` stay where they are for the build tools and
   tests. *Alternative:* the canvas gets its own reader. That is two readers of one spec.

3. **Theme colours in full-colour art.** Mono icons follow light/dark and the accent through their one tint. A
   full-colour drawing has fixed colours, so a dark theme needs a way to change them.
   *Recommended:* a **role** on a solid paint. The `solid` block gains a trailing `str role` (`"foreground"`,
   `"accent"`, `"muted"`, …; empty for none). That is spec 1.1, a field added at the end of a block, which the
   compatibility rules allow. A reader that knows the role uses the theme's colour, and an older reader draws the stored
   colour as the fallback. Art with no roles is drawn exactly as authored in both themes. *Alternatives:* a separate
   dark drawing per icon (twice the art), or a tint multiplied over the whole drawing (which loses full colour).

4. **Replace or keep the existing paths.** These are mono SDF icon atlases, `LayeredIcon`, and `ArtPair` + `Condense`
   for marks.
   *Recommended:* replace all three on v0.2 once slice 3 and slice 5 draw the same things. They are three partial
   answers to "a field plus a colour", and SDG is the whole answer. Witnesses that name them get rewritten
   (witnesses-are-made-to-break). `Condense`'s orb lift is an effect over an MTSDF, so it waits for MTSDF stencils, or
   stays as the one user of `ArtPair` until then. *Alternative:* keep them alongside SDG.

5. **OS icons.** Windows and X11 take pixels.
   *Recommended:* render the ICO sizes at build time (the icons plugin, from the app's `.sdg` mark), so the processor's
   `.ico`/`.png` check still holds for what the shell loads. *Alternative:* the shell renders them at startup with
   `SdgRender`, which puts the fields encoders' reference renderer on every app's runtime path.

## Not in this plan

- **Text.** Glyphs stay MSDF/SDF on set 0, at the author's word.
- **Effects on SDG** (outline, glow, shadow, weight, morph). These need distance far from the edge, which means MTSDF
  stencils (spec §7). That is a follow-up once slice 1 stands.
- **Spec §7's other items:** analytic gradients, groups, and sharp corners from a raster. Each is a format change of
  its own. They make better SDG, but the stack does not need them to draw SDG.

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

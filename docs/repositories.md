# The repositories

Every piece of VexelRay is its own repository under [github.com/sibarum](https://github.com/sibarum). This page says
which of them an application made from the framework's template needs, which ones add something you may want, and
which ones you can leave out. [Getting started](getting-started.md) has the commands to clone and install them.

## Needed

A project made from the `vexel-desktop` template builds against these eleven. Install them in this order, because
each needs the ones before it:

| Repository | What it is for |
| --- | --- |
| [`vexelray-build`](https://github.com/sibarum/vexelray-build) | A Maven plugin that stamps each build with the exact source it came from. Every repository below uses it in its build, your project too, so it goes first |
| [`intrabuf`](https://github.com/sibarum/intrabuf) | Serialization the message bus carries its envelopes in |
| [`atchung`](https://github.com/sibarum/atchung) | The typed message bus and the logger nearly every repository uses, with elektroq, the queue and transports under the bus |
| [`supirvast`](https://github.com/sibarum/supirvast) | The expression language the engine's shaders are written in |
| [`dxgi-bridge`](https://github.com/sibarum/dxgi-bridge) | Presentation on Windows |
| [`kronometer`](https://github.com/sibarum/kronometer) | Time and animation: one logical clock drives every animation |
| [`tactroller`](https://github.com/sibarum/tactroller) | Input: keyboard, pointer and clipboard |
| [`vexelray`](https://github.com/sibarum/vexelray) | The engine: Vulkan, 2D drawing, text and windowing |
| [`imagelib-wrapper`](https://github.com/sibarum/imagelib-wrapper) | Image decoding, in Rust behind a small Java API. Your application does not link it, but `vexelray-gui`'s build does: its icon plugin reads icon art with it. Its native library is built from source first (`./native/build.sh`), which needs a Rust toolchain |
| [`vexelray-gui`](https://github.com/sibarum/vexelray-gui) | The widgets, layout and theme, and the automation that drives a running window (`ottermate`) |
| [`vexelray-framework`](https://github.com/sibarum/vexelray-framework) | The framework itself, and the template a new project is made from |

## Optional

Each of these adds something to an application. Nothing in the needed repositories depends on them, so an
application that does not use one does not carry it.

| Repository | What it adds | Before it |
| --- | --- | --- |
| [`vexelray-codec`](https://github.com/sibarum/vexelray-codec) | Pictures, video with sound, and 3D meshes in your application: decoded files put onto the GPU and into the widget tree | `imagelib-wrapper` and `ffmpeg-wrapper` |
| [`ffmpeg-wrapper`](https://github.com/sibarum/ffmpeg-wrapper) | Video and audio decoding through FFmpeg, which `vexelray-codec`'s video uses. Its native shim and a pinned FFmpeg are fetched and built first (`./native/build.sh`), which needs a C compiler | `vexelray-build` |
| [`vexelray-installer`](https://github.com/sibarum/vexelray-installer) | Shipping: turns your native executable into a one-line installer served from a GitHub repository. See [Shipping](developers/shipping.md) | `vexelray-build` |
| [`vexelray-sim-core`](https://github.com/sibarum/vexelray-sim-core) | What GPU simulations share. [`vexelray-sim-fluid`](https://github.com/sibarum/vexelray-sim-fluid) and [`vexelray-sim-rigid`](https://github.com/sibarum/vexelray-sim-rigid) build on it | The needed repositories |
| [`vexelray-inspector`](https://github.com/sibarum/vexelray-inspector) | DevTools for your application: attach to it while it runs and watch its frame loop, messages, timers and animations. Your project can already be attached to; the Inspector is the application that does it, and [installs with the others](https://sibarum.github.io/) | The needed repositories |

## Not needed

These belong to the suite's own applications. You do not need them to build one of your own.

| Repository | What it is |
| --- | --- |
| [`vex-suite-common`](https://github.com/sibarum/vex-suite-common) | What the suite's applications share: Vexplore's and Pix's image viewer, Vex's and MainFrame's terminal |
| [`cott-engine`](https://github.com/sibarum/cott-engine) | The Calculator's number model, which divides by zero |
| [`cardinal`](https://github.com/sibarum/cardinal) and [`concordance-indexer`](https://github.com/sibarum/concordance-indexer) | Vex's source index: queries over Java that go beyond text search |
| [`vexelray-icons`](https://github.com/sibarum/vexelray-icons) | The source art of the suite's icons. `vexelray-gui` carries a copy, so it builds without this |
| [`vexelray-dist`](https://github.com/sibarum/vexelray-dist) | How the suite's applications are built, released and installed |
| The applications | [Vexplore](https://github.com/sibarum/vexplore), [Vex](https://github.com/sibarum/text-editor-vexel-demo), [Pix](https://github.com/sibarum/Pix), [MainFrame](https://github.com/sibarum/mainframe), [Calculator](https://github.com/sibarum/calculator-vexel-demo), [Maistro](https://github.com/sibarum/vexelray-maistro), and the simulations. Each is a worked example of something larger than the template, and worth reading |

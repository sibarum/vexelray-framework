# Overview

How a VexelRay application is put together, at the altitude you need to build one. Each section ends with where
the full reasoning is; [architecture.md](architecture.md) is the deep version and the record of why each choice
was made.

## The stack

<!-- image: the stack as layered bands, with each repository's name, a one-line role, and arrows for the bus and input -->

A VexelRay application is a Java program that draws its whole window itself, on the GPU through Vulkan, and is
compiled ahead of time by GraalVM native-image into one executable. Nothing in it is a native widget: the text,
the buttons and the title bar are drawn by the stack.

| Layer | Repository | What it gives an application |
| --- | --- | --- |
| Application framework | `vexelray-framework` | The wiring, the startup order, the frame loop, threads, windows, dialogs, settings |
| GUI | `vexelray-gui` | Retained-mode widgets, layout, the theme, zoom, the automation instrument |
| Time | `kronometer` | One timeline for animation and scheduled work |
| Messages | `atchung` | A typed bus that parts talk over instead of calling each other; also the logger |
| Input | `tactroller` | Every keyboard, pointer and clipboard event, as messages on the bus |
| Engine | `vexelray` | Vulkan, 2D drawing in signed distance fields, text, native windows |
| Shaders | `supirvast` | The expression language the engine's shaders are written in |

Underneath those, three more repositories do one job each: `intrabuf` (the serialization the bus carries),
`dxgi-bridge` (presentation on Windows) and `imagelib-wrapper` (image decoding). An application written from the
template depends on the framework and on a few `vexelray-gui` modules for its widgets; the rest arrives with them.
[Getting started](getting-started.md#2-install-the-stack) installs all ten.

## What the framework owns, and what you write

<!-- image: two columns, "you write" and "the framework does", the second noticeably longer -->

Every desktop application on this stack used to begin with a few hundred lines of setup, and they were nearly the
same lines every time: open the input devices, install the clipboard, restore where the window was, start the
clock, run the frame loop at the right pace, parse the command line, show dialogs, close everything in reverse.
Each had exactly one correct shape, and each one written by hand could get it subtly wrong. That setup is the
**application edge**, and the framework owns it.

What an application writes:

- **One `@VexelApp` class**: its name (which is also its settings folder), title, size, icon, and the starters it
  pulls in.
- **`@Configuration` classes of `@Provides` methods**: one per part of the application, each saying what it needs.
- **Optionally, `@Component` classes**: parts that run on a thread of their own and receive messages.

Five of the framework's defaults are overridable, one at a time, by providing the same type yourself: the look
(`Appearance`), the input backend, the clipboard, the liveness policy and the dialogs. When you do, the framework's
version is never built, so there is no precedence to reason about. The rest of the edge is the framework's alone
for now.

### The vocabulary

| Annotation | Does |
| --- | --- |
| `@VexelApp` | The application: its name (the settings directory), title, size, `icon` and `starters` |
| `@Configuration` / `@Provides` | Recipes: one method per part, its parameters what it needs |
| `@Default` | The framework's answer, unless the application provides its own |
| `@Component` | An actor on a lane: the shared default lane unless it names one |
| `@Subscribe` | A component method that receives a topic, through a mailbox of its own with a declared overflow |
| `@Publishes` | Which topics a type or method sends on, so a blocking send from the main thread is a compile error |
| `@MainThread` | May only be touched on the main thread, checked at compile time |
| `@BeforeFrame(FrameStage)` | Per-frame work, in a named stage |
| `@OnMode(RunMode)` | Exists only in some run modes |
| `@ConditionalOnType` | Contributes only if a type is on the compile classpath |
| `@Setting` | Binds one configuration value, by key, overridable for one launch with `--key=value` |

## The wiring is generated

<!-- image: flow from annotated source → processor (checks + order) → generated Wiring → native executable -->

There is no runtime scan, no reflection and no proxies. An annotation processor reads the annotations while the
project compiles and writes one ordinary Java class, named for the `@VexelApp` class with `Wiring` after it (in a
generated project, `MyAppApp` gives `MyAppAppWiring`), that calls each `@Provides` method once,
in a valid order, and hands each part what it asked for. You can open it under `target/generated-sources` and read
it.

This is what makes native-image cheap here. GraalVM removes exactly the things a runtime container depends on: a
classpath to scan, reflection without prior declaration, generating classes at runtime, and a second to spare at
startup. Doing the work at compile time avoids needing any of them back.

It also moves failures to the earliest place they can appear. A part that asks for something nothing provides, two
parts that need each other, a background part handed something that may only be touched on the main thread: each
is a compile error naming the declarations involved. The rule throughout is that **a compile error beats a startup
error beats a runtime error**.

The cost is that everything conditional is decided when the application is built. Adding a jar to the classpath
contributes nothing until you recompile, which for a single native executable is no cost at all.

*More: [architecture.md](architecture.md#why-none-of-spring-boots-mechanisms-come-along).*

## Phases

<!-- image: the seven phases as a left-to-right timeline, showing what exists by each, with the main-thread boundary marked at WINDOW -->

Some parts are only valid at certain points during startup. A widget built before the theme is applied is drawn
half-themed; one built before the clock is attached never animates. Neither throws. So startup is divided into
phases, and every part is built in one:

| Phase | What exists by then |
| --- | --- |
| `CONFIG` | The settings store and the look: values, before there is anything to apply them to |
| `MODEL` | What the application knows, before there is anything to draw it with |
| `GUI` | The `Gui` and its clock, with the theme and zoom range already applied |
| `TREE` | The widgets. Buildable without a window, which is what lets a test build them with no GPU |
| `WINDOW` | The window and the GPU device. From here on, the main thread |
| `ATTACH` | Anything that needs the window: input, clipboard, title bar controls, close checks, automation |
| `RUN` | The frame loop, and the shutdown in reverse order after it |

**You never declare a phase.** A part's phase is the latest phase of anything it takes. A method taking a `Model`
runs in `MODEL`; one taking a `Gui` runs in `TREE`, after the framework has configured the `Gui` in `GUI`; one taking the `Shell` runs last. Because the phase follows from
the code, it cannot disagree with it.

*More: [architecture.md](architecture.md#1-phases--construction-order-is-a-correctness-constraint-not-a-scheduling-detail).*

## The frame

<!-- image: one frame as a horizontal bar: INPUT, CLOCK, APP, SETTLE, then reconcile and present, then park until the next deadline or wake -->

The window is redrawn by a loop on the main thread. Each frame runs four stages in a fixed order, then reconciles
the widget tree and presents it:

| Stage | What runs |
| --- | --- |
| `INPUT` | Device input onto the bus |
| `CLOCK` | The timeline ticks: animations and anything scheduled for now |
| `APP` | The application's own per-frame work, usually draining what a background thread produced |
| `SETTLE` | Deferred bookkeeping |

Input comes before the clock so that a frame shows the value it computed, rather than one frame late. Per-frame
work joins a stage with `@BeforeFrame(FrameStage.APP)`; the stages are a closed list rather than numeric priorities,
so no part's position depends on another part's number.

**The loop sleeps when there is nothing to do.** It does not redraw at a fixed rate: it waits until the earliest
deadline any part holds (the next animation step, a window position to save) or until something wakes it. Parts
contribute their deadlines and wakes; the framework combines them. That is why an idle VexelRay window costs
nothing.

The frame is the only framework code inside the frame budget, and on its normal path it allocates nothing and
logs nothing; only an overrun or a failing hook is reported.

*More: [architecture.md](architecture.md#3-the-frame-loop-is-the-lifecycle).*

## Threads

<!-- image: the five lanes as horizontal swimlanes, with messages crossing between them on the bus and the main thread never waiting on another lane -->

Vulkan, the window and presenting stay on the main thread. Everything else runs elsewhere, on a short fixed list of
**lanes**:

| Lane | What runs there |
| --- | --- |
| **Main thread** | The window, the GPU, the frame loop. Never waits on another lane |
| **Timeline** | Kronometer: animations and scheduled work, one at a time |
| **Handler lane** | Event handlers: a click, a key, a submitted text field |
| **Offload lane** | Slow work such as reading and writing files, bounded so it waits rather than grows |
| **Component lanes** | Each `@Component`: a shared default lane, or one it names for itself |

A component is one part, on one thread, with one mailbox. It does not hold references to other components; it
receives messages through `@Subscribe` methods and sends them on the bus. Each mailbox says what happens when it is
full (fail, drop, keep the latest, or wait), and a send that could block the main thread is a compile error.

```java
@Component(lane = "physics")
final class Physics {

    @Subscribe(topic = Messages.START, overflow = Overflow.COALESCE_LATEST)
    public void start(Start s) {
        ...
    }
}
```

Lanes are assigned when the application is compiled, never at runtime, and are platform threads rather than virtual
ones. A value that may only be used on the main thread is marked `@MainThread`, and handing one to anything else is
a compile error.

*More: [threading.md](threading.md) for the rules, [components.md](components.md) for the component model, and
[architecture.md](architecture.md#the-concurrency-model) for the reasoning.*

## Never a frozen window

<!-- image: a stalled component lane greyed out while the main thread keeps presenting frames; the watchdog sitting outside all lanes -->

Because nothing the application writes runs on the main thread by default, a part that loops forever or blocks
takes only its own lane with it. The window keeps painting and taking input, and the user can always close it.
Behind that, in layers:

1. **Isolation.** Components run on lanes that are not the main thread, and the main thread never waits on them.
2. **Supervision.** A lane stuck inside one message for longer than a threshold (ten seconds by default) is
   noticed and reported by name; a lane idle on an empty mailbox is not stuck. What happens next is a
   `LivenessPolicy` the application can replace; the default reports and exits.
3. **Reaching the threads.** Each component lane runs in its own thread group, so it and every thread it started
   can be interrupted together.
4. **Ending the process.** A watchdog outside every lane halts the process if nothing else worked.

A per-frame hook that throws is switched off and reported, rather than taking the loop down with it.

Not yet: the main thread itself is not watched (it legitimately blocks in a native file dialog), offloaded work
cannot be cancelled, and nothing is restarted: the default policy reports and exits.

*More: [v1.md](v1.md#the-liveness-guarantee) for exactly what is promised, and
[architecture.md](architecture.md#a-wedged-component-cannot-freeze-the-window) for the design.*

## Windows, chrome and dialogs

The main window is a `Window`, and so is every other. A second window is declared in a recipe and the framework
builds and remembers it the same way. Each window can have a close check (`Window.onClose`), for unsaved work; the
framework installs none of its own, because the default has to be that closing closes.

The title bar is drawn by the framework from the application's identity (its title and icon) and in the
application's theme. Its placement and controls are the framework's, so every window behaves the same and tools
like screenshots can rely on it. Its look is entirely the application's.

Questions to the user (confirmations, open and save pickers) go through `Dialogs`, which a test can replace with
one that answers by itself.

*More: [architecture.md](architecture.md#5-chrome-placement-is-the-frameworks-chrome-appearance-is-the-applications).*

## Debug and release editions

A generated project declares its `@VexelApp` twice. The debug edition adds the automation starter, which can open
a local socket that `ottermate` uses to drive the application and take screenshots for tests. It opens only when a
launch asks for it (`--automation=<port>`), so a debug build run normally listens on nothing. The release edition
leaves it out, and its native build does not link the automation code at all, so the shipped executable cannot be
driven from outside.

## The modules

<!-- image: module dependency diagram; -api and -core inside a "JDK only, no GPU" box -->

| Module | What it is |
| --- | --- |
| `-api` | The annotations. JDK-only |
| `-core` | Phases, launch, frame stages, pacing, disposal, lanes, the watchdog. JDK-only, so every decision the container makes is tested with no GPU |
| `-processor` | The compile-time checks and the generated wiring |
| `-shell` | The application edge itself, and the only module that builds the window and the GPU device |
| `-automation` | The driving socket and `AutomationStarter` |
| `-template` | The project generator and the reference application it writes |
| `-acceptance` | Generates a project, builds it, runs it and drives it, under `-Pacceptance` |

*More: [architecture.md](architecture.md#modules).*

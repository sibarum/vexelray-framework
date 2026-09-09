# Architecture

## The thesis

Spring Boot's contribution was never dependency injection. It was the claim that the wiring most
applications write by hand has *one right answer nearly always*, and that the answer should be
**overridable rather than retyped**.

This stack has already reached the point where that claim is testable, and has already written the
evidence down. `mainframe-template`'s scaffold — the file a new VexelRay application starts from —
is ~350 lines of wiring whose own Javadoc says:

> The **application edge** — the part a client of vexelray-gui has to write for itself... Every one
> of those is a decision the framework deliberately does not take on an application's behalf, which
> is why they are all in one file rather than scattered.

Four applications now hold near-identical copies of that file. They have drifted from each other in
ways that are individually small and collectively the argument for this repo: the flags parse in
different orders, `--capture`'s optional path is read three different ways, and two of them carry a
comment warning the reader not to open `Settings` twice, because nothing stops them.

So the target is not "Spring Boot, ported". It is: **the decisions in that file, taken by default,
checked at compile time, and overridable one at a time.**

## Why none of Spring Boot's mechanisms come along

GraalVM native-image removes exactly the four capabilities Spring Boot is built on — a runtime
classpath, reflection without prior declaration, runtime bytecode generation, and the freedom to
spend a second or two on startup. Spring Boot 3's AOT engine exists to claw back the first three, and
it is a large piece of machinery devoted to undoing a design decision.

Starting after that decision is cheaper. Every mechanism has a compile-time replacement, and the
house already uses this pattern — `atchung/elektroq` generates message codecs from annotated records
and states the payoff as *"native-image needs no reflect-config.json"*.

| Spring Boot mechanism | Why it cannot come | Replacement here |
| --- | --- | --- |
| Runtime classpath scanning | No classpath in the binary; costs the startup you bought native-image for | Processor walks the annotated set while compiling |
| Reflective instantiation | Needs reachability metadata per bean | Generated `new` calls |
| CGLIB / JDK proxies | No runtime bytecode generation | Generated decorators, or nothing |
| `@ConditionalOnClass` | `Class.forName` at runtime | `ConditionalOnType` — `Elements.getTypeElement` at processor time |
| `@ConditionalOnMissingBean` | Registry state during population; hence ordering rules | `Default` — every provider visible to the processor at once, so there is no order to depend on |
| Reflective property binding | Metadata for every property class | `Setting` — generated calls to `Settings`' typed accessors |
| Actuator over HTTP/JMX | Wrong shape for a desktop app | A diagnostics overlay; `vexelray-diagnostics` and `vexelray-gui-automation` already exist |

The cost is real and worth stating once: **every conditional is frozen at the application's build.**
Adding a jar to the runtime classpath contributes nothing until recompilation. For a framework whose
output is one native binary, this is not a cost.

### There is no scan, at runtime or at build time

Moving a classpath scan from startup to compile time is not a fix, and it is the mistake this design
is most likely to be talked into. A build-time scan is still proportional to the size of the
classpath rather than to what the application uses, is still paid on every compile, and still leaves
nobody able to say which jar contributed what.

What a processor actually gets is the elements of the compilation it was handed —
`RoundEnvironment.getElementsAnnotatedWith` — which does **not** include annotated types sitting in
dependency jars. Everything else this framework reads, it resolves *by name*:

| Needed | Obtained by |
| --- | --- |
| the application's own components and configurations | `getElementsAnnotatedWith`, current round |
| a starter's configuration in another jar | the class literal in `@VexelApp(starters = …)` |
| whether an optional type is present | `Elements.getTypeElement("fqcn")` — a lookup, not a walk |
| `@MainThread` / `@Default` / `@Setting` on a compiled element | read off the element once resolved above |

The house precedent agrees: `atchung/elektroq`'s `ElektroProcessor` reads
`roundEnv.getElementsAnnotatedWith(Message.class)` and nothing else — no `getResource(CLASS_PATH, …)`,
no `ServiceLoader`, no class-file walk.

Naming starters as class literals is what makes this possible, and it pays for itself: javac resolves
them, so a renamed or absent starter is a **compile error** rather than a component missing at
startup. It is also the house position on implicit defaults, stated in `TextEditorApp`: *"a default
is not a choice anyone can read."*

One caution taken from the same precedent: `ElektroProcessor` writes its registrar at a **fixed**
FQN (`sibarum.elektro.queue.generated.ElektroRegistrar`), which collides if two modules on one
classpath both generate one. The wiring class here is named after its `@VexelApp` type
(`TextEditorAppWiring`) for that reason.

## What Spring has no answer for

These are the parts that are not a port of anything.

### 1. Phases — construction order is a correctness constraint, not a scheduling detail

Spring is free to instantiate in any order the graph allows. Here the graph is not the only
constraint: several seams are valid only inside a window of time, and every one of those windows is
currently a comment in a hand-written `main`, enforced by nothing.

`Phase` turns them into a type. A component **never declares its phase** — it is inferred as the
latest phase of anything it depends on, so the phase is a consequence of the code rather than a
second thing to keep in agreement with it. What the processor rejects is a *backwards* dependency.

| Phase | The constraint, and where it was already written down |
| --- | --- |
| `CONFIG` | One `Settings` per application. *"two instances over the same file each hold their own copy of it, so the second one to save would drop whatever the first had added"* |
| `MODEL` | *"everything the application knows is in Model"*; the edge *"holds no state of its own"* |
| `GUI` | Theme: *"a role resolves at the moment a widget writes a prop"*. Clock: *"a widget that animates is handed its timing at construction"* |
| `TREE` | Buildable before a window exists — which is what makes headless capture possible at all |
| `WINDOW` | The main-thread boundary. `GuiApp` exists; a window handle is real |
| `ATTACH` | Everything needing the handle: input, clipboard, chrome controls, window memory, dialogs, automation |
| `RUN` | The loop, and the reverse-order shutdown after it |

Note what the `GUI` and `CONFIG` failures have in common: they are **silent**. A half-themed window,
an animation that never runs, a preference that saves and then vanishes. None throws. This is the
class of defect that survives a green suite and is found by eye months later — and the reason a
container here earns its keep on correctness, not on convenience.

### 2. Thread affinity is a type-level concern

`vexelray-gui/CLAUDE.md` lists this under *constraints that are not visible in the code*:

> Vulkan, the window and present stay on the main thread.

`MainThread` makes it visible. One rule, one direction: **a main-thread value may not be injected
into anything that is not itself main-thread.** A worker-safe component asking for a `GuiApp` is a
compile error naming both types, instead of a Vulkan call from a worker that happens to survive
testing on one driver. The inverse is allowed — handing the render thread an immutable model violates
nothing, and requiring an annotation for it would put `@MainThread` on most of an application.

### 3. The frame loop is the lifecycle

A request-scoped container can afford a reflective dispatch; a frame loop cannot afford an
allocation. `FrameHooks` resolves stage order at build time into one flat `Runnable[]` walked by a
counted loop. Stages exist in the source; at runtime they have already been spent.

`FrameStage` is a **closed enum of four positions**, not `@Order(int)`. Integer priorities make every
hook's position a negotiation with every other hook's, decided by numbers whose meaning lives
nowhere — and the bug that produces is one frame of latency, which is invisible in a screenshot and
nearly unattributable after the fact. The order and its reason are already documented on this stack:

> Input first, then the clock: the tick returns with its batch complete, so anything an animation
> posts this frame is on the bus before `Gui.frame` reconciles it — the frame that presents a value
> is the frame that computed it.

### 4. Deadlines and wakes are contributed, not enumerated

Render-on-demand means the loop parks, and parking correctly requires knowing every deadline the
application holds. Today that is one expression:

```java
app.pacing(() -> Math.min(krono.kron().sleepTimeout().nanos(), memory.nanosUntilSettle()));
gui.onWork(app::postWake);
krono.kron().onWork(app::postWake);
```

Correct, and the wrong shape: it must be edited by whoever adds the next deadline holder, and the
failure when they forget is that **the loop parks past a deadline**. Nothing throws — the window
placement is not written, or an animation resumes late, on a machine that was idle at the time.
`DeadlineSource` and `WakeSource` make a component bring its own; `Pacing` reduces over whatever the
graph produced.

The GUI's own record of the wake half: five missing wakes shipped past a green suite, because a test
that draws its own frames cannot notice a wake that never came.

### 5. Chrome placement is the framework's; chrome appearance is the application's

This direction is easy to get backwards, and `vexelray-gui/docs/automation.md` §7 already argues it:

> A title bar is window chrome, and chrome belongs to whoever owns the window. An application
> contributes **identity** — its title, an icon as identity — and never controls... The moment one app
> puts its own button in the caption, the strip is app-addressable, and no framework instrument can
> rely on the space existing or on its meaning being the same from one window to the next.
> "Screenshot this window" is only free if the framework owns the place it lives.

So the framework builds the `TitleBar`, supplies identity from `@VexelApp`, and hands it real
`WindowControls` at `ATTACH` — the seam that exists because *"a native window cannot photograph
itself... only `GuiApp` owns a window's render bundle, so only `GuiApp` can make working controls."*

**The look stays entirely the application's.** `Appearance.theme()` is whatever the application says,
including a `Theme` of its own construction with its own nine-anchor `Palette`, `Shading` and
`Relief`; the chrome reads that same theme rather than one of its own. An application that wants to
draw things differently is not fighting a default — it is supplying the only value there is.

Instruments follow §7's rule: the framework supplies `WindowInstrument.standard()` and a window may
take fewer or none. Free to *enable*, not present unconditionally.

Two knobs an application will want are **not yet expressible**, and neither gap is the framework's to
close:

- **Corner radius** is a per-node prop (`Node.corner(Length)`) with no theme-level default. Making it
  convenient means `Theme` gaining a radius anchor beside `Relief`, in `vexelray-gui`. Backwards
  compatible if added as a `default` member.
- **Light direction** is *hardcoded in the generated shader* — `CanvasShader.java`, `unit top-left
  light dir`, baked into the SPIR-V. Making it app-controllable means a uniform threaded through the
  SDF uber-shader in `vexelray`. That is the one change here that touches the path every pixel in the
  stack goes through.

### 6. Scopes are application / window / frame

Not singleton / request / session. Window scope is already real and already hand-maintained — the
text editor binds the clipboard to *every* window in a loop, and remembers each window under its own
`WindowMemory` key.

## The absorption boundary

The decision taken for this repo is **absorb the app shell**: `GuiApp` shrinks to the render seam and
the shell moves here. Reading the actual dependency edges sharpens where that line falls.

**Confirmed movable.** `GuiApp`'s only two mentions of `Settings` are *Javadoc prose* — `{@code
Settings}`, not even `{@link}` — so there is **zero compile coupling** from the frame loop to the
settings store. `WindowMemory` already inverts its one dependency behind a seam (`Desktop PLATFORM =
GuiApp::workArea`). `AppHome` and `Settings` are JDK-only.

**Not movable, and not shell.** `AppWindow`, `WindowSpec`, `Standing`, `OpenWindow`, `CloseGate` and
`ModalScrim` are referenced 7–11 times each from inside `GuiApp`. They are its multi-window
machinery, not the application edge. They stay.

**The blast radius is wider than the demos.** `mainframe-vexel-gui` — a *library*, consumed by
text-editor-vexel-demo — imports `WindowMemory` in three files and `Settings` in `Desktop`, where it
carries its own copy of the two-instances comment and threads both through a factory SPI
(`List<ConsoleApp> of(Settings settings, WindowMemory memory)` — dependency injection by parameter
threading). Moving those types puts `mainframe-vexel-gui` downstream of this framework.

That is the correct end state rather than an obstacle: **mainframe's console becomes a starter**,
contributing window-scoped console components, which is precisely how Spring Boot absorbed its
ecosystem. But it makes the move a coordinated sweep across four repos, so it is sequenced last:

1. Absorb what no library depends on — CLI parsing, capture dispatch, app icon, the wiring recipe.
   (Done.)
2. Port one demo. Prove the claim against real code. (Done twice: `calculator-vexel-demo`
   and `text-editor-vexel-demo` — see *What porting the text editor found*, below.)
3. Move `WindowMemory`, `Settings`, `AppHome` in one atomic sweep, in dependency order with an
   `mvn install` between each repo. Java has no type aliases, so there is no deprecation window
   available — this is a single coordinated change or it is a broken build.
4. `mainframe-vexel-gui` becomes `vexelray-framework-starter-mainframe`.

## What porting the text editor found

The calculator was the first port and it went through the seams as designed, which is a weak test: a
one-window application with no files to lose exercises the parts of an application edge that are
easiest to get right. The text editor was chosen second for the opposite reason — **three windows, an
OS clipboard on all of them, unsaved documents, an application mark, and its own headless capture** —
and it is the first thing on this stack the framework could not run.

The gaps were all one shape. In each case the decision had already been taken, written down in prose,
and copied by hand into every application; the framework had absorbed the *statement* and not the
*seam*. Four of them:

| What was missing | What it cost | Where the decision already was |
| --- | --- | --- |
| **The application's mark** | `VexelApplication` built the main window's `WindowConfig` and never named an icon, so no framework application could wear one | `AppIcon`'s own Javadoc, and `automation.md` §7: an application contributes *"identity — its title, an icon as identity"*. The README listed the app icon among the defaults, and it did not exist |
| **The clipboard, past the main window** | `ClipboardBackend` was opened, installed on the one `Gui`, and dropped. An application's second window had no way to reach it | `ClipboardBackend`'s own Javadoc quotes the editor's loop — *"copy out of the terminal's prompt has to reach the same place copy out of a tab does"* — and then made it unreachable |
| **The close gate, and the dialogs** | `GuiApp.onCloseRequest` had no seam at all, so an application with unsaved work could not be asked before it quit | `Phase.ATTACH` lists *"dialogs installed, the close gate armed"* among the things that happen in it. Neither did |
| **A build that stops at `TREE`** | `Phase.TREE` exists to make a headless tree possible, and no entry point produced one | `Phase.TREE`: *"A tree that cannot be built without a window could not be captured headlessly."* |

The pattern is worth naming, because the processor will not catch it: **a phase can document a
capability the runtime does not offer.** `Phase.TREE`'s Javadoc is a complete and correct argument for
something no method returned. Nothing type-checks the claim that a phase's list of contents is the
list the phase actually builds.

So every member's list was then read against `VexelApplication` line by line, and the enum now
carries that warning on the type itself. The audit found three more, of two different kinds:

| Claim | Kind | Resolution |
| --- | --- | --- |
| `Phase.GUI`: *"theme, minimum size, zoom range, and the frame clock attached"* | A capability that should exist | Taken — `Appearance.ZoomRange`, applied beside `minSize`. See [below](#what-the-port-left-as-the-applications) for why the range moves and the chords do not |
| `Phase.ATTACH`: *"the close gate armed"* | A seam deliberately left empty | Text corrected. `Shell.onClose` is registerable there and the framework installs no gate: the default has to be that closing closes |
| `Phase.ATTACH`: *"the automation socket bound"* | Bound by an optional module | Text corrected. `-automation`'s `Driver`, called from the application's own `attach` — see [the two reserved keys](#the-two-reserved-keys) |

The second kind is the one worth having a name for, because it is not a defect and reads like one: a
phase is the right *place* for something the framework will never do itself, and a list of contents
that does not distinguish the two invites somebody to close a gap that is a decision.

What the port did *not* need is the more interesting half. Pacing, wakes, the frame stages, ordered
shutdown, the one `Settings`, the window memory and the framework's title bar all took the editor's
real requirements without modification — including the two the editor had hand-written most
carefully, the `min` over two deadlines and the pair of `onWork` calls. A 541-line `main` became a
67-line wiring class, and the `--verbse` case works as advertised:

```
$ text-editor --verbse
unknown option: --verbse (known: automation, profile, terminal)
usage: text-editor [--key=value] [frames]
settings: terminal
framework: automation, profile
```

That list is the point — `terminal` is on it because the application declared one setting key. The
second line is the application's own keys and the third is the names the framework reserves, kept
apart because they are not the same promise: see [the two reserved keys](#the-two-reserved-keys)
below. `terminal` is also hand-typed for now, and `AppInfo.settingKeys` says so — the claim that the
list "cannot fall out of date, because it is not written by anybody" is the argument for the
processor, not a description of today.

### What the port left as the application's

Three things looked like framework candidates and are not, recorded here so they are not re-proposed:

- **The zoom chords** — but *not* the zoom range, and the split is the point. `CalculatorWiring` has
  ruled on the chords: *"which chord zooms, or whether zooming exists at all, is not something a
  framework should be choosing."* The drift there is real and visible (the calculator binds the numpad
  chords, the editor does not) but it is drift between two applications' decisions rather than between
  two copies of one. How far the zoom *goes* is a different question, and it was answered identically
  in all five places — `gui.zoomRange(0.5f, 3f, 1.25f)`, with no reason given at any of them, which is
  what a default looks like before anybody has taken it. That half is now
  `Appearance.ZoomRange`, applied where `minSize` already is, defaulting to the numbers everybody
  chose; the framework had already owned the other half, that the zoom is *remembered*. Owning both is
  what makes them agree, because the remembered factor is restored through `Gui.zoom`, which clamps to
  the range.

  Taking it turned up the clipboard's problem in a second guise. The framework dresses the one `Gui`
  it built, and the editor calls `zoomShortcuts` on three — so a range set only on the main window
  would leave the others on different bounds. Hence `Appearance.applyTo(Gui)`: the theme and the zoom
  range, in one call, for a window the framework never saw. Not the minimum size, because
  `Gui.minSize` is *"not an OS window minimum"* but the smallest canvas one tree can be laid out on,
  and the main window's floor is the wrong answer for a tool window beside it. The same call is what
  `VexelApplication` uses on its own `Gui`, so there is one definition of what applying an appearance
  means rather than two to keep in agreement — and it is the seam that stops the `Modals` defect
  below from being every multi-window application's as well.

  **And then the editor corrected it.** Applying that method to the editor's other two windows is
  wrong: its file drawer is deliberately a different hue — *"they are different machines... hue is
  the cheapest thing a glance resolves"* — and the MainFrame console it opens brings its own palette,
  because *"a window that had to be themed by whoever embedded it would look different in every
  application that used it."* So the two facts are not the same kind after all, and the split is
  worth stating exactly:

  | Fact | Whose | Why |
  | --- | --- | --- |
  | How far the zoom goes | Every window on the desk | A second window that disagreed is not making a point; it is inconsistent. `ZoomRange.applyTo` |
  | The theme | The application, *unless a window has its own* | A departure can be a decision, and overwriting one with a default is the defect, not the fix. `Appearance.applyTo` |
  | The minimum size | One tree | It is a floor for a layout, not for an application |

  `ZoomRange.applyTo` is also what a **library** window reaches for. `EditorWindow` and
  `FolderWindow` both run under MainFrame as well as under this framework, and under that host there
  is no `Shell` to ask — so they state their own look and take the range off `ZoomRange.DEFAULT`,
  which keeps the three numbers in one place on the stack even where the container is not running.
- **`--capture` and its two siblings.** The editor has three headless entry points and two of them
  photograph a window that is not the main one. `Launch` already says an application with its own
  capture tooling intercepts its own flag first; what was missing was only a tree to point it at.
- **`FpsProbe`.** Generic enough to move and specific enough not to: it deliberately pokes the loop —
  a timeline post, a node mutated from a worker, a handler that changes nothing — to prove each wake
  path is still alive. That belongs in `-diagnostics` when there is one, and until then
  `Launch.FRAMEWORK_KEYS` advertising `profile` is a reserved name rather than a promise: the flag
  parses, and the application still owns the probe it is supposed to turn on.

### The two reserved keys

`Launch.FRAMEWORK_KEYS` accepts `--profile` and `--automation` without the application declaring
them, and **neither is consumed by `-core` or `-shell`.** The socket is bound by `-automation`'s
`Driver`, from the application's own `attach`; the probe is the application's until `-diagnostics`
exists. So an application depending on neither can be given `--automation=7654`, have it parse, and
have nothing happen — which is the failure `Launch` exists to prevent, sitting inside `Launch`.

It is kept, because both alternatives are worse:

| Alternative | Why not |
| --- | --- |
| Refuse the key unless something consumes it | Requires asking at runtime whether `Driver` is linked — a `Class.forName` on the startup path, and **nothing here reflects** |
| Make each application declare the key | Returns the stack to what it had: the scaffold read `System.getProperty("automation", "off")` inside a factory method, and every application spelled the switch for itself |

Reserving the name is what buys the one property worth having — the same instrument is asked for the
same way in every application on the desk — and the honest fix is a compile-time one that does not
exist yet. `@ConditionalOnType` makes the dependency decide, and a key with no consumer becomes a
build question rather than a quiet launch. Until then the gap is documented on `FRAMEWORK_KEYS`
itself, and `Launch.usage` prints the reserved keys on their own line so the two categories are not
presented as one list.

## What porting the designer found

The third port, and the first with **two windows on one device and one frame loop** — a tree window
and a ray-marched viewport, each with its own `Gui`. It was chosen for that: the calculator was one
window, the editor was three windows that were three separate applications' worth of `Gui` with one
device between them, and neither exercised a second window whose *content is rendered by the
application into a target the host mints*.

It went through the seams. `DesignerApp` was 558 lines; **about 130 of them were edge** — `run`, two
input backends, the flag parsing behind two automation sockets, the shutdown ordering, and a `main`
that decided what `--capture` meant — and that is now roughly 40 lines of `DesignerWiring`, most of
it the one thing the framework could not take. The file total went *up*, to 611 across two files,
because the rest of `DesignerApp` was always the toolbar, the properties panel and the tree source,
and the phase methods now carry as Javadoc the reasoning that used to be comments inside `run()`.
Counting lines is the wrong measure here; what changed is that none of the remaining lines are about
starting an application.

Three things it confirmed rather than found:

- **`InputBackend.perWindow()` was already exactly right.** The designer's `attachWindowInput` — open
  a backend, attach it to the new window's handle, settle `CLIENT`, bridge it to that window's bus,
  close it with the window — is the framework's method line for line, including the comment about the
  two unrelated `NativeWindow` types. A second window's input needed nothing.
- **The `CLIENT` census.** `Appearance.decorations` defaults to `CLIENT` on the grounds that *"three
  of the four applications on this stack draw their own frame"*. This is the fourth. It says
  `Decorations.SYSTEM`, gets no framework title bar and therefore no instrument strip — which is
  right, because the screenshot that matters in this application is the viewport's and it comes off
  the driver.
- **`Appearance.applyTo`**, found in the editor's three `Gui`s, has its second witness here: the
  viewport's window is the application's, so its `Gui` is too, and without dressing it that window
  disagrees with the one beside it about the theme.

And two that were new. Neither is a defect in something the framework does; both are the framework
declining to do something, which is the second kind of gap the phase audit above had to name:

| What | Why it stayed the application's |
| --- | --- |
| **A second automation socket** | `-automation`'s `Driver` binds one, for the `Gui` the framework built. Two window trees need two — `tree` on the first does not list the viewport and `shot` on it photographs the wrong window. The application binds the second at `Driver.port() + 1`, so it still never parses the flag; what it cannot do is ask the framework for it. One application needs this, which is not yet enough to grow the API — see `docs/TODO.md` |
| **A named window's controls** | `WindowSpec.onControls` hands them down when the window opens, and the driver starts before the frame loop creates it, so they have to be resolved per command. That is a fact about a window that can be closed and reopened, not about starting an application |

### A phase is decided by a component's listeners, not only by its data

The one genuinely new thing the port taught about `Phase`, and it is a trap worth naming. The
designer seeds a starting design so the first frame is not an empty sky, and a seed is model data, so
it reads as `MODEL`. It is not: `Design.silently` coalesces the four edits into a single `onChange`,
and that one announcement is what refreshes the tree view and compiles the first `Surface`. Both
listeners have to exist before it fires, so the seed belongs in `TREE` — **after the things it wakes
up, not beside the model it edits.**

`Phase`'s own rule already covers this correctly and says so in a way that is easy to read past: a
component's phase is *"the latest phase of anything the component depends on"*, and an announcement
depends on its listeners. The processor will infer that from constructor parameters and get it right
without anybody thinking about it. A hand-written wiring has to think about it, and putting the seed
one phase too early is a first frame that draws nothing with no error anywhere.

## Modules

```
vexelray-framework                    parent (pom)
├─ vexelray-framework-api        the vocabulary: annotations only, JDK-only          [built]
├─ vexelray-framework-core       phases, launch, frame stages, pacing, disposal      [built]
├─ vexelray-framework-shell      the absorbed edge: input, clipboard, memory, loop,
│                                and the window chrome                              [built]
├─ vexelray-framework-automation the driving socket, off unless asked for            [built]
├─ vexelray-framework-processor  annotation processor -> generated wiring             [next]
└─ vexelray-framework-diagnostics  the Actuator analogue: frame budget, bean graph   [planned]
```

The processor is deliberately **last**. A code generator whose output has never been written by hand
is a generator whose output nobody has checked the shape of — so three applications' wiring got
written by hand first, against the real `-shell`, and the processor's job becomes "reproduce these
files". `CalculatorWiring`, `TextEditorWiring` and `DesignerWiring` live in their own repos rather
than in a `-demo` module here, and that is the right place for them: they are what an application
author writes, so they should be read where an application author would look. Three is the number
that matters — one window, three windows, and two windows on one device — because each found
something the other two could not have. That also settles the incremental-compilation
question with evidence rather than a guess, because the generated shape is known before the
generator is designed around it.

`-api` and `-core` are **JDK-only**, and not as an agnosticism goal — it is simply where the
dependency edges fall. A phase enum and a topological sort do not need a Vulkan device. The payoff is
that every decision the container makes is testable headlessly, on a machine with no GPU, and cannot
drift out of agreement with a driver.

## Open questions

- **Incremental compilation.** A processor that generates one wiring class from the whole annotated
  set is a whole-program view, which is the thing incremental javac is trying not to give it. Needs a
  deliberate answer before the processor is written, not after.
- **Window scope onto a callback API.** `GuiApp.window(key, Supplier<WindowSpec>)` is
  callback-shaped; mapping per-window components onto it needs care about what exists when.
- **`@MainThread` on foreign types.** The check is only as good as the annotations, and the types
  that most need it live in other repos. `Provides`-level `@MainThread` covers this, but it has to
  actually be applied.
- **Reachability metadata as a framework asset.** The framework adds no reflection, but the *stack*
  needs metadata already — `vexelray-gui-demo` and `mainframe-dist` both ship
  `reachability-metadata.json`. Aggregating those into the starters, so an application inherits the
  whole stack's metadata by depending on the framework, is the same value Spring Boot delivers by
  shipping hints for its ecosystem. Probably the highest-leverage feature not yet listed.

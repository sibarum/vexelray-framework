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

### 5. Scopes are application / window / frame

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
   (`Launch` is done.)
2. Port one demo. Prove the claim against real code.
3. Move `WindowMemory`, `Settings`, `AppHome` in one atomic sweep, in dependency order with an
   `mvn install` between each repo. Java has no type aliases, so there is no deprecation window
   available — this is a single coordinated change or it is a broken build.
4. `mainframe-vexel-gui` becomes `vexelray-framework-starter-mainframe`.

## Modules

```
vexelray-framework                    parent (pom)
├─ vexelray-framework-api        the vocabulary: annotations only, JDK-only          [built]
├─ vexelray-framework-core       phases, launch, frame stages, pacing, disposal      [built]
├─ vexelray-framework-shell      the absorbed edge: input, clipboard, memory, loop   [built]
├─ vexelray-framework-demo       calculator's wiring, hand-written                   [next]
├─ vexelray-framework-processor  annotation processor -> generated wiring             [after]
└─ vexelray-framework-diagnostics  the Actuator analogue: frame budget, bean graph   [planned]
```

The processor is deliberately **last**. A code generator whose output has never been written by hand
is a generator whose output nobody has checked the shape of — so the calculator's wiring gets written
by hand first, against the real `-shell`, and the processor's job becomes "reproduce this file". That
also settles the incremental-compilation question with evidence rather than a guess, because the
generated shape will be known before the generator is designed around it.

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

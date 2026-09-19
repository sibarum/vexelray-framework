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

**Two colours is the whole of it, and that is deliberate for now.** `@MainThread` says main-thread or
not-main-thread, which is exactly enough to protect Vulkan and not enough to describe where the rest
of an application runs. The model it is a first instalment of — one component, one thread, one mailbox
— is [The concurrency model](#the-concurrency-model) below, along with what a third colour would have
to be before the processor can check it.

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

That closed enum is **the main thread's frame and nothing else's**, which is a scope worth stating
before somebody reads it as the stack's general answer to "when does my work run". A component says
that with a Kronometer `Rate`, and the two do not compete: see [the frame is the main thread's, and a
`Rate` is everyone else's](#the-frame-is-the-main-threads-and-a-rate-is-everyone-elses).

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

## The concurrency model

*The reasoning is here; the rules it produces are in [threading.md](threading.md), stated normatively and
each carrying whether anything enforces it yet.*

The intended shape of a VexelRay application is **one component, one thread, one mailbox**, and it is
the most load-bearing constraint in this design that has never been written down. It is why
`atchung-core` exists — its own README routes *"one process, many components (input, graphics, GUI,
workers meeting on a bus)"* to the bus — and it is why tactroller does not expose a poll but publishes
every device frame onto a topic. Until now this document's only statement about threads was
[§2](#2-thread-affinity-is-a-type-level-concern), which is `@MainThread` and the two-colour rule.

That is precisely the failure `@MainThread` was created to fix. `vexelray-gui/CLAUDE.md` files the
main-thread rule under constraints *not visible in the code*, and the annotation's own Javadoc says
why that was worth fixing: it was *"true, load-bearing, and enforced by nothing but the reader's
memory."* The concurrency model has been in the same condition, one level up.

**Sequence matters.** The processor generates the wiring, so whatever `@Component` means when the
processor is written is what gets baked into every generated application. Its contract today is a DI
one — *"a container-managed singleton, constructed once, by its one constructor, with its parameters
supplied"* — and constructor injection hands component A a direct reference to component B, which is
the thing a mailbox exists to prevent. Writing the processor first would freeze a vocabulary the
concurrency model then has to fight. So this is settled first, and the mismatches it names are part of
the processor's brief.

### The mapping is static, and that is the load-bearing simplification

A component lives on a thread that is not the main thread. Whether each component gets its own thread
or several share one is **decided in the wiring and never at runtime**: there is no work stealing, no
placement decision, and nothing to tune while running. This is not a pool and not a scheduler.

That one restriction is what makes everything below fall out.

**The processor can emit the whole thing.** Static assignment means thread construction, the
component-to-thread mapping and barrier participation are all generated code, with no scheduler in the
binary. It is the same trade the container already makes everywhere else — a compile-time replacement
for a runtime mechanism — so it fits the thesis rather than straining it.

**Platform threads, not virtual ones**, and the argument is measured rather than stylistic. Virtual
threads exist to multiplex many blocking tasks onto few carriers dynamically, which is exactly the
thing being declined. More sharply: Kronometer's kernel already owns the virtual-thread scheduler in a
desktop application that follows its advice. `kronometer/docs/architecture.md` §3.1 recommends
`-Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1` — worth 3× on
the baton handoff — and then states the sharp edge of that decision:

> **The precompute pool and `offload()` must run on their own executors, never the kernel's carrier.**
> One carrier means one runnable virtual thread. Pure evaluation scheduled onto that carrier would not
> merely be slow, it would deadlock against the serialization that makes the baton fast.

Component work is in the same category as that precompute pool. Placing components on virtual threads
would put every component in the application onto the one carrier the baton needs in order to be
answered — so the choice is not "platform threads are a little simpler" but "virtual threads are the
documented deadlock". A platform thread is outside that scheduler entirely. Pinning inside a Vulkan
call would be a hazard for no benefit on top of that.

**Placement is semantically neutral, and Kronometer is the reason.** Order over effectful work is *"a
total order, one baton, strictly at `now`"* with *"no two shreds can race"*, and a `Rate` is *"an
independent sampling grid over the timeline"* carrying its own `priority` — the tie-break *"for shreds
of different domains waking at the same moment"*. So a component keeps its schedule and its ordering
wherever it is placed. **Regrouping costs capacity, not meaning.** And capacity is a reading rather
than a guess: slip is a debt, `wall(m) = m + slip`, and `Overrun` says which kind of trouble it is —
*"a slip that drains is a hiccup, a slip that plateaus is a capacity problem, and a slip that climbs is
a system heading for a `RESYNC`."*

### The component thread does not touch the timeline

This is the part the phrase "in sync with Kronometer" hides, and getting it wrong would make the model
unbuildable. `KronBridge` — the module that exists to join the bus to the timeline — states the
constraint plainly:

> Two systems with incompatible threading models, which is the whole problem. The bus publishes on
> whatever thread published — that is what makes it fast — while the timeline is single-threaded by
> construction, because that is what makes it ordered.

and rules out the shortcut: delivering on the publisher's thread *"would mean mutating the graph from
off the timeline, which is the one thing the design does not permit."*

So a component thread never holds the baton, and never reads or writes a `Signal`, `Cell` or `Effect`.
What "in sync with Kronometer" means is a round trip through the seam the stack has already built:

| Step | Where it runs |
| --- | --- |
| The component declares a `Rate` — its grid, its `maxCatchUp`, its `priority` | The wiring, at compile time |
| The rate steps, and the step publishes to the component's mailbox | The timeline, on the baton |
| The mailbox drains and the component does its work | The component's own platform thread |
| The result is published on a `Topic` | The component's thread |
| The bridge folds that topic into a `Cell`, and the graph sees it | The timeline, on the baton |

That last step is `KronBridge`'s stated purpose — a topic driving a cell is *"the natural way live
input enters the graph (with `horizon == now`, as it should be)"* — so a component's output enters the
predictable world by the same door tactroller's input does.

The cost is one drain period of latency, and it is already priced rather than waiting to be
discovered: *"input latency is bounded by the draining domain's period... The frame of latency is the
price of the ordering guarantee, and it is the same price every retained-mode GUI pays."* Naming it
here is what stops it being re-litigated later as a defect of the component model.

### What the framework owes the model, and what it already has

Two of the three obligations are seams that already exist, written for a single-threaded edge without
anyone noticing they were the multi-threaded answer as well:

- **The deadline is already covered.** `Kron.sleepTimeout()` is *"how long a host may block before it
  should tick again — the whole render-on-demand condition, as one number"*, composed over the
  timeline with its rate domains in it. The framework already registers that as a `DeadlineSource`, so
  a component whose rate is due in 20 ms is a loop that parks for 20 ms, with no new API. That matters
  more than it sounds: the timeline is driven from the main thread's frame, so a parked window would
  otherwise stop a 50 Hz component dead. `DeadlineSource`'s own Javadoc already lists *"a component
  waiting out a cue"* among its implementors.
- **The wake was a real gap, and it is now closed by construction.** A component that finishes early and
  publishes a result has produced work the loop cannot predict — which is `WakeSource`'s definition — and
  no wake existed for it. `gui::onWork` covers a node mutated off the frame thread and
  `krono.kron()::onWork` covers the timeline; a component's own publish is a third path, and the symptom
  of omitting it is the exact one the GUI already paid for — a window that is responsive except for the
  interactions that happened to arrive that way. **Each component mailbox owes a `WakeSource`**, so a
  `Placement` *is* one: the container connects it when it starts the component, and a component publishes
  through `published()`. The obligation is no longer something the processor has to remember to emit —
  what it will emit is the call site, which is a smaller job than the registration and a much smaller one
  to get wrong. The designer's hand-written component is the argument for doing it this way round: it met
  the obligation *by accident*, because announcing a phase wrote to a node and a node mutation wakes the
  loop, so a real wake was hanging on an unrelated line of reporting.
- **`Overrun` wants surfacing per thread.** Slip is a property of the one timeline and cannot be made
  per-domain, but *which grouping is late* is a question a static mapping makes answerable — so an
  overloaded grouping should name itself rather than show up as a global plateau.

Measurement is still worth doing, and its purpose is narrower than it looks: a synthetic barrier
against Kronometer at the N a real application reaches says where the tail starts to hurt, since frame
jitter is `max()` over participants rather than the mean. That chooses a sensible **default grouping**.
It does not decide the architecture, because the reasoning above already did.

### What is actually wired today

The model above is the design. This is an inventory of the code as it stands, taken so that the gap
between the two is a readable distance rather than an impression. Everything here is a grep over the
four modules' main sources.

> **Both of it have since been built**, and the paragraphs below are struck through where they have
> stopped being true. The container owns the application's bus — `Shell.bus()`, handed to the framework's
> `Gui` — and now its threads as well: `Shell.lanes()` is the handler lane, the offload lane and the
> component threads, and `Shell.place(name)` puts a component on one of the last with its mailboxes and
> its wake. The two constructor arguments named at the end of this section are both taken. **This section
> is now a record of where the model started rather than an inventory of the present**, which is what it
> was written to become.

~~**The framework contains no concurrency primitives at all.** Zero occurrences of `Thread`, `Executor`,
`java.util.concurrent`, `volatile`, `synchronized` or `Atomic`. The only matches for those searches are
the string `@MainThread` inside Javadoc.~~ `Lanes` is in `-core` and `Placement` in `-shell`, and the
module split held under the change: `Lanes` is pure JDK, because `-api` and `-core` stay JDK-only and a
pool is exactly the kind of thing that keeps the container testable with no GPU; `Placement` is in
`-shell` because a mailbox is atchung's and that dependency edge already falls there. Its thread-aware API
surface was one `Runnable`:

| Seam | What it says about threads |
| --- | --- |
| `WakeSource.onWake(Runnable)` | the runnable is *"safe to call from any thread — that is its purpose"* |
| `DeadlineSource.nanosUntilNextFrame()` | *"Called on the main thread… must be cheap and must not block"* |
| `FrameHooks` | *"Not thread-safe and not meant to be"*, and sealed before the first frame |
| `Disposer` | reverse construction order; no timeout, no drain, no start order distinct from it |
| `@MainThread` | inert — there is no processor, so the two-colour rule is enforced by nobody |

Two rows of that table have moved. `Disposer` now has a component half — `Placement.close` is drain then
stop, on a timer, and a placement's start is a separate moment from its construction, which is the row's
"no start order distinct from it" answered rather than restated. `@MainThread` is still inert, and that
has not moved at all: it is the colour rule, and the colour rule is the processor's.

~~**The stack is already multithreaded, and none of the threading is the framework's.**~~ The threading is
now the framework's, which is the whole of what changed. `Gui` used to own an
`Executors.newCachedThreadPool` per instance and run every input handler on it. The applications make
almost no threads of their own: one daemon reporter in the editor's `FpsProbe`, and none at all in the
calculator or the designer. ~~So the concurrency an application has today is a property of how many
`Gui`s it happens to hold.~~ It is now a property of what the wiring placed, which is the sentence the
whole model turns on: the container builds one set of lanes, hands them to every tree it makes, and
`Gui.close()` shuts down only the lanes that `Gui` built itself — so a dialog closing cannot take the
application's threads with it.

**Atchung is named once, and only as a fabric.** ~~No `sibarum.atchung.*` import appears anywhere in
the four modules~~ — `Shell` now imports `Atchung`, creates one in its constructor and hands it out as
`Shell.bus()`; an application no longer reaches a bus as `shell.gui().bus()`, which was *that `Gui`'s*
own. That is the whole of the change. No `Topic`, `Pump`, `State`, `Backpressure` or `Fold` appears in
any framework signature, so what the container owns is the fabric and not one thing published on it.
Beyond that the bus still reaches this repo only transitively through `tactroller-atchung`, of which
exactly one type is used — `TactrollerInputBridge`. **elektro-Q is absent entirely**, from the
framework and from all three ported applications.

**Kronometer is never named either.** No `sibarum.kronometer.*` import — only
`dev.vexelray.gui.krono.KronoGui`. The framework owns the clock's *lifecycle* and none of its
*vocabulary*: it constructs it in `GUI`, ticks it at `FrameStage.CLOCK`, parks on
`kron().sleepTimeout()`, wakes on `kron()::onWork`, and hands it over through the single accessor
`Shell.krono()`. `Rate`, `Settlement`, `Overrun` and `Moment` appear in no framework signature.
Applications reach them directly instead — the calculator's `Motion` imports `Kron`, `Rate`, `Cell`,
`Curve` and `Animator`, and its `Model` is built on Atchung's `State<T>` and `Committer`.

#### Every window was an island, and half of that is now closed

`new Gui()` is `this(Atchung.create())` — **a private bus per `Gui`**, and a private worker pool with
it. Nothing on this stack shared one. A calculator, which looks like a one-window application, ran two:
the framework's main `Gui`, and the one `Modals` builds for the dialogs. The editor has that plus its
file drawer and its editor windows; the designer has that plus its viewport.

The seams for fixing it already existed, upstream:

```java
public Gui(Atchung bus)                                            // one shared fabric
public Gui(Atchung bus, java.util.concurrent.Executor handlers)    // and who runs the handlers
```

**The first is now taken**, and it reaches less far than it first appeared to. `Shell` owns an
`Atchung` and `VexelApplication` builds its `Gui` on it, asserted headlessly by
`OneBusPerApplicationTest`. The dialogs do **not** join it, and neither does a second window's tree —
see the constraint below, which was found by putting them there and watching an application stop.

#### One `Gui` per bus, and everything else on it

`Gui`'s topics are `static`: `vexelray.gui.mutations` is one name for every instance. So two trees on
one bus each receive the other's mutations, into a mailbox bounded at 65,536 with
`Backpressure.BLOCK` — and a tree that is not being presented never drains it. The second `Gui` fills
up and then blocks the first one's node setters permanently. An application that freezes after some
tens of thousands of edits, with no exception and nothing in a log.

It was found the hard way: putting the designer's viewport window on the application's bus stopped the
viewport marching at all, and moving that one line back brought it straight back. The framework had the
same defect for three commits, because `Modals` builds a tree of its own and the dialogs' mailbox is
drained only while a dialog is on screen.

So the rule the bus is actually offering today:

| May share a bus | May not |
| --- | --- |
| Input publishers, workers, **components and their mailboxes**, anything that is not a tree | A second `Gui` |

`Gui(Atchung)`'s Javadoc now carries the constraint, where it previously carried an invitation. **And
this is the ceiling on the wider ambition** — one inspectable fabric carrying every application
semantic — because it is exactly what a second window is barred from joining. Lifting it means `Gui`
naming its topics per instance rather than per class, which is a real change in `vexelray-gui` and not
a line in this repo.

**The second is now taken too, and what it cost is worth recording.** `Gui`'s worker pool was a field
initializer, so a `Gui` built a `newCachedThreadPool` whatever it was handed and `Gui.async` submitted to
*that* pool rather than to the executor. Passing a handler executor therefore redirected handlers and did
not remove the per-`Gui` pool — so *one bus was not one thread*, and this was correctly filed as an
upstream change rather than another argument at this call site.

It was one change upstream and it closed three things at once. `Gui` now takes both lanes, `null` means
*build that one and close it*, and `close()` shuts down only what that `Gui` built. The lanes are named
apart, because "worker thread" meant both of them; and the offload default is bounded, because the
unbounded one answered a wedged filesystem mount by spawning a thread per blocked call. Two of those
three were filed here as `upstream` rules that this repo could not fix, and they turned out to be one
problem rather than two.

So the distance between this section and the model above is no longer the substrate *or* the seam.
`Pump`, `State`, `Fold`, `Backpressure`, `Rate` and `KronBridge` were all built and none of them was
reached from here; `Shell.place` reaches the first four now. The container owns **the bus a component
publishes on** and **the threads it runs on**, which is where the model starts. What remains is the
colour rule — which is the processor's, and is the one thing on this list that a runtime object cannot
be made to hold.

### The first component, written by hand

`vexelray-designer`'s shader composition now runs as one: `Mailbox<T>` — one platform thread, one slot,
latest wins — with `Composed` as the immutable result it publishes. Nothing was extracted here, on
purpose. What two of these have in common is what the framework should own, and one of them is not a
census.

**It found a defect, which is the answer to whether the exercise was worth doing.** `Viewport.show`
hand-rolled the same mechanism — an `AtomicInteger` revision, a submit to `Gui`'s pool, and a check
that this task was still the newest — and made the check *before* composing rather than around it. So
an edit that passed it and then lost its place kept composing beside the edit that replaced it, and
both wrote five independently `volatile` fields:

```
reports, in the order they finished:
  6,188 B in 17 ms       the sphere — asked for last, finished first
  2,662,152 B in 171 ms  the heavy design — superseded after 40 ms, composed anyway, and landed last
```

The viewport settled on a design the user had already replaced, and the frame pump could build a
pipeline from one compose's modules and another's push size. A slider emits an edit every few
milliseconds and a compose takes tens, so that is the ordinary case of dragging one.

Four things the hand-written version turned up that the model above does not say:

- **The mailbox is the bus's, after atchung grew the one thing it was missing.** `COALESCE_LATEST` was
  always the right policy and its capacity *is* one; what `Pump` had no answer for was *waiting*, since
  a pump is drained by an owner that already has a wake — a frame loop. `Pump.drain(long)` is that
  answer: it parks on a pump-level monitor, holds no mailbox lock while parked, and costs a publisher
  one field read when nobody is waiting. `Pump.wake()` ends a park for shutdown. There is deliberately
  no untimed form, because a drain that waits forever on a thread that also publishes is a hung
  application rather than a slow one. The designer's hand-written `Mailbox` was **deleted** rather than
  moved: an edit is now an ordinary message on `designer.viewport.edit`, which a debug port can watch
  and a script can send — which a private queue could never have been.
- **The wake obligation is real, and was being met by accident.** A compose landing woke the loop
  because announcing the phase writes to a `Node` and a node mutation wakes the loop. A real wake
  hanging on an unrelated line of reporting: stop announcing and the window parks. It is now
  `shell.wake(viewport::onWork)`, which is `WakeSource` used as written.
- **Drain-then-stop is an edge rule, not a universal one.** It exists so nothing downstream loses what
  it cannot reconstruct. A coalescing mailbox holds a *sample* by construction, and the sample it holds
  at shutdown is a picture nobody will see — so this one stops.
- **Coalescing cannot cancel work already started, and the useful move is to not *apply* it.** Nothing
  here interrupts a compose in flight; what a component can do is ask whether it has been superseded
  before publishing, and stay quiet if it has. That makes *superseded* a third outcome beside composed
  and refused, and it is the difference between a stale picture flashing on screen and never appearing.

### Three mismatches this leaves for the processor

None of these is a defect today. Each is a place where a vocabulary written for one thread has to grow
one more axis, and writing them down is what stops the processor freezing the current shape.

| What | Why it does not fit yet |
| --- | --- |
| **`@Component` is a DI contract, not an actor one** | Constructor injection hands A a direct reference to B. Under this model most parameters should resolve to a `Topic` or an address, not to the object |
| **`@MainThread` is two-coloured; the model needs one colour per thread** | Main-thread versus worker-safe cannot express "confined to *this* component", so a graph that passes today's check can still be two worker components racing. Because placement is static, the colour of a value **is** the thread it was placed on — known while compiling — so the processor can allow a direct reference between two components sharing a thread and reject one that crosses, alongside a shareable set (immutable, `Versioned`, `State<T>`). Dynamic placement would have made that check undecidable; this does not |
| **`FrameHooks` is the barrier's degenerate case** | A flat `Runnable[]` walked on one thread, *"not thread-safe and not meant to be"*, is the N=1 answer. The model wants release-at-tick, drain, await quiescence, reconcile — and saying so in the file is what stops its no-allocation rigour being defended into a shape that cannot grow |

### What a full mailbox does, and where survivability actually lives

Settled before the first component rather than after, because a default chosen once something depends
on it is not a choice. It divides in two, and keeping the halves apart is the whole of the answer.

**A bus fault is the framework's to answer, and the answer is still a halt.** `Atchung.onFatal` is
process-wide and says *"call it once, at the application edge"* — which is `VexelApplication`, for
every application on this stack, and nothing called it. So the answer to *what does this application do
when the bus cannot continue honestly* was `Fatal.HALT` by inheritance: the right answer, and nobody's
decision. `Faults` now installs one. The obvious framework addition — save the window placement first,
since the `Disposer` and `WindowMemory.save` are right there — is refused, and the reason is upstream's:
*"running application code on a thread that is mid-publish, holding a mailbox lock, with a full queue
behind it, is how a crash becomes a hang."* A lost placement is the cheaper loss. What the framework
adds is the sentence naming the application, and then upstream's report and upstream's exit code,
called rather than copied.

**A wedged component is not a bus fault, and reading it as one would put survivability in the wrong
place.** A component that stops draining is a mailbox filling up, and the answer to that is the
`Backpressure` chosen for that channel — which atchung has already reasoned out, per channel, by the
loss class of what it carries:

| The payload | Policy | Because |
| --- | --- | --- |
| A **sample** — a pointer position, a window size, a clock reading | `COALESCE_LATEST` | the next one supersedes this one, so dropping it costs nothing that could still have been drawn |
| An **edge** — a keystroke, a command, a tree mutation | `FAIL`, or `BLOCK` where the publisher can safely wait | nothing supersedes it and nothing downstream can reconstruct it |

And the rule that falls out of it, which is the one a component author will meet first: *"a channel
carrying both classes cannot be given a correct policy — every choice is wrong for half the traffic.
That is not a policy problem to be solved here; it is a signal to split the channel."* So a component
does not get one mailbox with one policy; it gets a mailbox per loss class, and the processor's job is
to make that structural rather than remembered.

**What is still open** is the part neither half covers: an application staying *responsive* while one
component is wedged. Coalescing keeps its publishers running and the loop alive, which is most of it —
but nothing yet notices that a component has stopped draining, names it, or decides what the rest of
the application does about it. That is supervision, and it wants `Overrun` surfaced per grouping
(above) before it can be built on anything but a timeout.

`Disposer` needs the matching answer too. An actor's shutdown is *drain then stop*, with a timeout, and
a start order distinct from construction order — a mailbox must not pump before its publishers exist.
Not cosmetic: `Backpressure.FAIL` is the default and resolves through `Fatal`, so getting shutdown
ordering wrong kills the process rather than dropping a message.

### The frame is the main thread's, and a `Rate` is everyone else's

`FrameStage` and `Rate.priority` read as two answers to "when does my work run", and settling the
concurrency model means saying which is which before somebody makes one match the other.

They are not rivals once you look at what actually registers in a `FrameStage`: `input::pump`,
`krono::tick` and `memory::poll` are all framework-owned, and `APP` is an empty slot held for the
application. Four stages is not a scale anybody could need a fifth point on — it is the list of things
**one thread** does between waking and presenting. So the enum is the main thread's frame recipe, and
its internal order is **causal** rather than ranked: `CLOCK` follows `INPUT` because the tick reads
what the pump delivered, not because it outranks it.

A `Rate` is the other thing entirely — independent grids over one timeline, with `priority` breaking a
tie when two come due at the same moment. That is where a component says when its work runs, and
[no component enters the frame pipeline](#the-component-thread-does-not-touch-the-timeline).

| | `FrameStage` | `Rate` |
| --- | --- | --- |
| Scope | The main thread, which is special because Vulkan makes it special | Every component |
| What the order means | Causal — each stage reads what the last produced | A tie-break between grids due at the same moment |
| Who declares one | The framework, four times; the application, in `APP` | The component, in the wiring |
| Open to more positions | No. It is a recipe, not a scale | Yes. That is what a grid is for |

**`FrameStage`'s argument against integer priorities wants a scope, not an answer.** *"An integer
priority makes every hook's position a negotiation with every other hook's"* holds for an open hook
list, where unrelated parties pick numbers with no shared meaning. It does not hold for
`Rate.priority`, where one author declares a few grids whose relationship is real — physics before
render, because render displays what physics computed. Recording that distinction is what stops the
next reader collapsing one into the other, and there are two collapses to refuse:

- **The four stages as four `Rate`s at priorities 0–3** loses the causality and makes "equal priority"
  both expressible and meaningless.
- **Everything as a `FrameStage`** cannot express independent rates at all, which is `Rate`'s whole
  reason to exist — *"an animation framework with one frame rate is a toy."*

#### The meeting point is `APP`, and it is narrower than it was

The two scopes meet where `FrameStage.APP` narrows from "the application's own per-frame work" to
**the main thread draining what a worker left for it**. Its own Javadoc was already uneasy about the
wider reading — *"the right place for reading a queue drained by a worker, and the wrong place for the
work the worker was doing"* — and `vexelray-gui` names the same hook from the other side of the seam,
describing what an input handler does when its effect is neither a tree mutation nor a clock
operation:

> it drops a request on one of the application's own queues — a history to restore, a file to open, a
> preview to render — each drained once per frame from the host's beforeFrame hook.

The designer is the worked example on this stack today. Its viewport marches on the GPU, and *"a
`VkQueue` is not thread-safe, so a drag handler running on a worker must only move the camera and
raise a flag — `pump()` is called from the frame loop and is the only place the GPU is touched"*, with
that `pump()` given exactly one home, one hook in `APP`.

**And the framework registers nothing there, which is a correction to how this was first planned.**
The obvious move — have the framework own a `Pump` on the Gui's bus and drain it in `APP` — is wrong
for a reason the GUI already documents: `Gui` owns a pump on that bus and drains it *inside*
`Gui.frame`, folded by cell and lossless, with navigation arriving on the same mailbox so that
everything enters the tree *"at the same point in the frame the tree's own edits do — before the
drain, never in the middle of one."* A framework hook draining that bus would be a second drain point
landing at a different moment than the edits it has to agree with. What gets drained in `APP` is the
*application's* queues, and only the application knows what they are — so `APP` stays an empty slot,
and what changed is the sentence saying what the slot is for.

Notably, the designer's witness is a flag and a GPU submission rather than an Atchung mailbox. The
shape is the same and the plumbing is not, which is the evidence that the mailbox half belongs with
the component model rather than ahead of it.

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

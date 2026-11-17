# `vexelray-runtime`: the runtime model, clean slate

Status: **plan**. No code has moved. The author chose the repository name and ruled on `@Daemon` and the
runtime model, and chose a clean-slate design over an in-place extraction, on 2026-11-17.

The compile-time wiring in this repo, the `-api` vocabulary and the `-processor` that reads it, was written for a
desktop application with a window. Most of it isn't about windows. It describes how *any* VexelRay process is put
together and how it runs: parts built once in an inferred order, actors on lanes, jobs, sources and destinations,
a watchdog, an orderly close. The first program that wants it without a window is `vexelray-knowledge-engine`, a
pure-Java distributed database. Its `docs/architecture.md` §0 asks for no reflection, no runtime scanning and
native-image builds with no hand-written reachability config, which is exactly what this processor already
provides.

This document plans a repository of its own for that model, **`vexelray-runtime`**. Eventually
`vexelray-framework` becomes *the GUI profile of the runtime*: it supplies the window phases, the frame, the
shell's values and the GUI-only checks.

**Clean slate, then migrate, never both at once** (the author, 2026-11-17): *"we don't need to design the
-runtime API and do a migration simultaneously. Too much cognitive load, the API design might suffer."* So
`vexelray-runtime` is designed fresh. Code that translates verbatim is copied in, and `vexelray-framework` is left
alone while that happens. Moving the framework and its applications onto the runtime is a later plan of its own,
written once the runtime's API has settled (§4.2). This document is the first half. §1 is a map of what can be
copied, what needs redesign, and what the GUI keeps. §2–§3 describe the runtime. §4 builds it.

**The scope is the runtime model, not just a DI library** (the author, 2026-11-17): *"It's the implementation of
my runtime model that would apply to any jar or native-image binary."* So the concurrency model, `@MainThread` and
`@View` move along with the injection. `@View` moves as a generic MVC presentation and `@MainThread` as a fact
about every process. What stays here is what only a GUI has: the frame and its stages, the window, and the
binding that drains views inside a frame.

**The model, as ruled on 2026-11-17** (§2.4 has the detail):

- **`@Daemon` is the runtime's basic unit**, not a framework component kind: *"if @Daemon was built as part of
  vexelray-runtime, it wouldn't need to be defined as a component kind."* A daemon is portable, has one thread and
  a mailbox, lives as long as its runtime, parks until it is told what to do, and talks by messages only.
- **A runtime is a compiled set of daemons plus the host process that owns the main thread.** Daemons are fixed
  at compile time: *"Yes, daemons are fixed at compile time. But a runtime can spawn another runtime and directly
  integrate with it via ElektroQ."*
- **`@VexelApp` is a runtime whose main thread drives the window.** A headless runtime gives the main thread to
  its host.
- *"The Knowledge Engine is a network of portable daemons."*

---

## 1. What is generic, what is the bus, what is the GUI

The classification below comes from the code as of `develop` on 2026-11-17, and each row gives its evidence.
There are three columns of destination:

- **R**: belongs in `vexelray-runtime`. For code, that means copied verbatim (repackaged) in R1. For an
  annotation, it means the concept carries over, but the API is designed fresh in R2, and the existing
  declaration is the starting point, not the answer.
- **R/split**: generic in concept, but today's declaration carries a GUI detail. The runtime's version is
  designed without it.
- **G**: stays in `vexelray-framework` and becomes the GUI profile in the later migration (§4.2).

"Moves" below means "belongs in the runtime". Nothing is removed from `vexelray-framework` by this plan.

### 1.1 `vexelray-framework-api` (21 annotations and types, 1.4k lines)

| Type | Dest. | Evidence |
|---|---|---|
| `@Configuration` | R | Holds `@Provides`. Its `components`/`jobs`/`sources`/`destinations` members list runtime parts, and none is GUI-specific. |
| `@Provides` | R | Factory method, called once, with null meaning absent. Its javadoc mentions `Tactroller`/`Clipboard`, but only as examples. A provided value becomes either portable or local to its runtime (§2.5). |
| `@Default` | R | Back-off rule, decided per mode (§3.3 for modes). |
| `@ConditionalOnType` | R | `Elements.getTypeElement` at compile time. Pure DI. |
| `All<T>` | R | Set-valued injection (regions.md). The processor names it by string in `Graph.ALL`. |
| `@Setting` | R/split | Concept is generic (key, default, typed accessor, one precedence order). But `Generator.setting` emits `shell.setting(k, def)`, so the settings source is the GUI `Shell` → it becomes a profile root (§2.2). |
| `@Withers` | R | Writes a record's withers. `RecordWithers` touches nothing else. |
| `@Stability` | R | A marker that nothing reads. Every moved type carries it. |
| `@Component` | R | An actor on the logic lane (T1.6). `lane = ...` names a `Crew` thread. No GUI type is involved. How it relates to the daemon as the basic unit is Q15. |
| `@Subscribe` | R | A mailbox on a component, a daemon *or a view*. The view half's drain is a binding (§2.3). |
| `@Hears`, `@Publishes` | R | Declared ends of atchung channels. They are needed for the message graph and `StepCheck`'s ledger rule. |
| `@Job` | R | Request → job lane → reply. Runtime half is `Jobs` (atchung + probe only). |
| `@Source`, `@Destination`, `Closer` | R | Producers and writers outside the process. Runtime halves are `Sources`/`Destinations`, which import no GUI type. Per components.md §14, question 5, they are optimised cases of a daemon. |
| `@MainThread` | R | The author: *"Everything that can run has a @MainThread."* Today it is refused in applications (T7.1), and the framework marks its own main-thread values in `Framework.Root.onMain` instead. The rule (a main-thread value may not reach a non-main part) is generic. Whether application code may own the main thread is the profile's decision: the GUI's refuses, the headless one allows (§2.3). |
| `@View` | R/split | The concept (presentation shows what the logic decided and reports what a person did, by messages, and decides nothing) works for an HTTP endpoint or a socket. Two details are GUI-specific: `window()`, and the javadoc's *"drained on the GUI thread in each frame's widget tick"*. Both move into the GUI profile's view binding. |
| `@OnMode` | R | A startup `if` on the mode. The mechanism is generic. |
| `RunMode` | R/split | Its values, `WINDOWED` and `FRAMES`, are GUI modes. The enum can't stay closed (§3.3). |
| `@VexelApp` | G (entry) | *"A runtime whose main thread drives the window"*: the GUI profile's entry, and it keeps `title`, `width`, `height` and `icon`. Its `name`, `formerNames` and `starters` are what every runtime needs, so the processor reads them through the profile's entry (§2.4). A headless runtime needs an entry that carries them too (Q10). |
| `@Draw` | G | A view's asked half, on every frame. Takes `Frame`/`Drawn`. |
| `@BeforeFrame`, `FrameStage` | G | Frame hooks. They have been refused since 0.2 and are kept only so the refusal can name what replaces them. |
| `@Daemon` | R (to build) | **The runtime's basic unit** (ruled 2026-11-17). [components.md](../components.md) §14 (ruled 2026-11-12, commit `9401cb0`, not built) still holds for its threads, its mailbox (atchung's ordered `Pump`), ordering, capacity (64 per edge mailbox, overflow names the fix), ordered shutdown, `superseded.now()`, and `@Source`/`@Destination` as optimised cases. **§14's framing of it as a component kind is superseded**: it is not a kind of framework component but the unit a runtime is built from. It needs `Superseded`, `Daemons` in the host and named job lanes (`@Job(lane = ...)`), none of which is GUI. It is built new in the runtime (R4). |

### 1.2 `vexelray-framework-core` (26 types)

| Types | Dest. | Evidence |
|---|---|---|
| `Lanes`, `Watchdog`, `Timer`, `Disposer`, `Stalled`, `MainThreadBeat` | R | They import only `-api`'s `Stability`, `atchung` and `probe`. `MainThreadBeat` is the main thread's pulse for the watchdog. Its "parks when nothing is due" is GUI phrasing, but a headless host's main thread has the same three states. `Timer` is *"a daemon in all but name"* (components.md §14, question 5): whether it becomes one is decided after the first application daemon is built, as that ruling says. |
| `Launch` | R/split | Parses the generic `args`, but carries `RunMode` and `frames`. |
| `Phase` | → profile | `CONFIG, MODEL, GUI, TREE, WINDOW, ATTACH, RUN`. This is the GUI lifecycle. Each profile declares its own phases (§2.2). |
| `FrameHooks`, `Pacing`, `DeadlineSource`, `WakeSource` | G | The frame loop and its pacing. |
| `WindowCommand`, `WindowOpened`, `WindowClosed`, `WindowFullScreen`, `Modifiers`, `ThemeShifted`, `PickFile`/`Picked`, `Open`/`Opened` | G | Window and desktop messages. |
| `Ask`/`Answer` | R | A question from the logic to a person, answered by whichever view presents it: a dialog in a window, a prompt over HTTP or a socket. The records carry only an id, text, button labels and the asker's name, so they are MVC messages, not GUI ones. `Shell` answers them with native dialogs today, and that host side stays with the GUI profile. Under §2.5 an ask also carries a deadline. |
| `CloseRequested`/`CloseAnswer` | G for now | A window's close, asked of each hearer in turn. A runtime's equivalent is "shutdown requested; daemons may delay it", which is a generic cousin. That is open question Q5, not part of the first move. |

### 1.3 `vexelray-framework-shell` (53 types, the runtime half of the wiring)

GUI-free today: `Logic` (the tick), `Crew`, `Inboxes`, `Jobs`, `Sources`, `Destinations`, `Senders`, `Absences`,
`Faults`, `Heard`, `UncleanExits` and the `Wiring` base class (0 GUI imports each). They move with the runtime
model. `Inboxes` names `Answer` (which moves with it) and `CloseRequested` (which doesn't, until Q5) by type for
the hearers, and the second dependency is cut along with Q5. `Views` (imports `Gui`), `Shell` (8 GUI imports),
`VexelApplication` (12) and the rest are the GUI profile's host and stay. `Stalls` and `Closes` have one GUI
import each, so R1 copies their generic half by hand.

### 1.4 `vexelray-framework-processor` (15 classes, 7.2k lines)

The processor depends on `-api` and on `-core`, the latter for `Phase` alone. It names `-shell` and
`vexelray-gui` types only by string, so that the graphics stack stays off the processor path. That is why the
GUI coupling is spread through the processor as string constants, not imports.

| Class | Lines | Dest. | Evidence |
|---|---|---|---|
| `Graph` | 803 | R | Whole-program resolution, `@Default`/`@OnMode`/`@ConditionalOnType`/`All`/`Optional`, the colour rules (T2.2/T2.3/T3.7) and executors refused. It reads `View`, `MainThread`, `Hears` and `Subscribe` too, but each is runtime vocabulary once §1.1 holds. Main-thread-ness comes from `Framework.Root.onMain`, which becomes a profile root's flag. The portable/local rule (§2.5) lands here. |
| `Declarations` | 1066 | R | Shape checks (one constructor, `@Provides` in a configuration, `@Setting` types, …). It checks `@View` shapes, which are generic by §1.1. |
| `Generator` | 1331 | R/split | Phase inference (*"the latest phase of anything a part takes"*), mode narrowing, `if`s per mode and reverse close are generic. GUI parts: `Phase` constants, the floor at `Phase.TREE` for a `@View` (line 659), `Framework.method(phase)` naming `Wiring`'s six methods, `shell.setting(...)`, the frame-stage array, `@VexelApp`'s window facts and the hand-backs. All of those become profile data (§2.2). |
| `Channels` | 775 | R | The message graph and T4.1/T2.4 on payloads. Atchung is part of the runtime model. `GUI_PACKAGE = "dev.vexelray.gui."` (widget messages heard by a view aren't crossings) becomes a profile hook. `TIMER` moves with `Timer`. The wire rules for daemon payloads (§2.5) land here. |
| `Topics` | 171 | R | `Topic.edge`/`Topic.state` by payload. Atchung only. |
| `StepCheck` | 1332 | R/split | Ledger steps (captures, statics, locks, effects, `@Publishes`) and deliveries that block (T5.8) are generic. `@Draw` bodies (no allocation, no log, no bus), display functions handed to a widget, `Gui.hear/mailbox/batch` and the widget-package constants are GUI. The reader and the effect table are shared, and the GUI rules plug in as a contributed check (§2.2). |
| `Shareable` | 297 | R/split | Deep immutability is generic. `Feed`, `ImageSdg`, `Node`, `TechniqueView`, `Paint`/`Role`/`Ink` are GUI value types accepted by contract. They become a profile's "trusted value types", and are local by nature (§2.5). |
| `Unresolved`, `Mirrors`, `Report` | 337 | R | Reading the vocabulary, counting errors, missing types (FN-30). |
| `RecordWithers` | 174 | R | |
| `BuildBill` | 82 | R | Reads `vexelray-build`'s bill. This is specific to the stack, not to the GUI. The knowledge engine is built with `vexelray-build` too (open question Q6). |
| `Framework` | 222 | G | The table: ~40 `Shell`/GUI type names as roots in `CONFIG`/`TREE`/`WINDOW`/`ATTACH`, hand-backs, `REFUSED` (raw bus, `Senders`, `Lanes`, `FrameHooks`, `Disposer`) and `SHELL_REFUSED`. *Its shape* (roots, hand-backs, refusals) is the SPI. *Its contents* are the GUI profile. `REFUSED` splits: the bus, `Senders`, `Lanes` and `Disposer` refusals are runtime rules, and `FrameHooks` is the GUI's. |
| `SpiBoundary` | 82 | G | A `@View` may not name `dev.vexelray.gui.core.spi`. |
| `TechniqueCalls` | 120 | G | `RenderTechnique.realize/record/close` are called only by the host. |
| `VexelProcessor` | 405 | R/split | The entry point and round logic. Its `VOCABULARY` list mixes all three columns. `@BeforeFrame`/`@Draw`/`@VexelApp` become the GUI profile's annotations. |

Rough proportions: about 5.5k of the 7.2k lines are generic once their GUI constants are lifted out. About 0.4k
are GUI outright (`Framework`, `SpiBoundary`, `TechniqueCalls`). The remaining ~1.3k are GUI rules inside shared
classes (`StepCheck`'s draw rules, `Generator`'s GUI emission and `Shareable`'s GUI types), and they move out
behind the SPI.

---

## 2. Profiles, daemons and runtimes

### 2.1 The idea

The processor today is one environment hard-coded: the roots, the phases, the wiring base class, the extra
annotations and the extra checks are all GUI. A **profile** is that environment written as data plus a few hooks,
loaded by the generic processor. `vexelray-framework` ships the GUI profile. The runtime has a headless profile
built in.

The profile lives **on the processor path**, not the runtime path. It is compile-time code like the processor, so
loading it with `ServiceLoader` from the processor path is fine. The application's binary never sees it, so
nothing about it reaches native-image. The ban on runtime discovery (`-api`'s package-info: *"Nothing here is
discovered"*) is about the application's classpath at run time. The processor path is javac's.

**Choosing the profile:** each profile declares its **entry annotation**, and the processor runs the profile
whose entry the compilation uses. `@VexelApp` selects the GUI profile, and the headless entry (Q10) selects the
headless one. A compilation with no entry (a library, a starter, or a set of daemons compiled alone) gets only
the generic checks, as `Graph` already allows today (*"A starter compiled on its own is allowed to need things"*).
Two entries in one compilation, or an entry whose profile is not on the processor path, is a compile error naming
the missing artifact. A profile-only annotation such as `@Draw`, met under a profile that doesn't know it, is a
compile error too.

### 2.2 What a profile contributes

A sketch, not a final signature. R3 builds it with the headless profile as its first implementation. The GUI
profile is its second, in the later migration, and the SPI should be written so that today's `Framework` table
fits it without contortion.

```java
package dev.vexelray.runtime.spi;

/** One environment a runtime can run in: its lifecycle, what the host hands out, and its own rules. */
public interface Profile {

    /** Canonical name of the entry annotation that selects this profile ("dev.vexelray.framework.api.VexelApp"). */
    String entry();

    /** The construction phases, in order. A part is built in the latest phase of anything it takes. */
    List<String> phases();                       // GUI: config, model, gui, tree, window, attach; headless: config, run

    /** The generated class's base, and the method each phase's construction goes in. */
    String wiringBase();                         // GUI: dev.vexelray.framework.shell.Wiring
    String phaseMethod(String phase);

    /** What the host owns and hands out: type, expression, first phase, main thread or not, portable or local. */
    List<Root> roots();                          // today's Framework.ROOTS
    List<HandBack> handBacks();                  // today's Framework.HAND_BACKS
    Map<String, String> refused();               // added to the runtime's own refusals, each with its reason

    /** Whether application code may own the main thread (§2.3). GUI: no. Headless: yes. */
    boolean mainThreadForApplication();

    /** Where @Setting values come from: an expression over the host, typed by the parameter. */
    SettingSource settings();                    // GUI: "shell.setting(k, def)"

    /** How a @View is driven: the earliest phase one is built in, what it may take, and what drains its mailboxes. */
    ViewBinding views();                         // GUI: floor "tree", drained in the frame by Views

    /** Value types accepted as shareable on their own contract, as Feed and Paint are today. */
    Set<String> trustedValues();

    /** Annotations only this profile understands (@Draw, @BeforeFrame), and the checks it adds. */
    Set<String> annotations();
    List<Check> checks();                        // run over the same model Graph and Channels see

    /** The run modes this profile knows, for @OnMode and @Default's per-mode question (§3.3). */
    List<String> modes();
}
```

Notes on the shape:

- **Phases become names, not an enum.** `Generator` compares phases with `compareTo`. It will compare indices in
  `phases()` instead. This is the single change that drops the processor's dependency on `-core`.
- **Roots stay strings.** That is the existing discipline (`Framework`'s javadoc: *"named by string, because the
  processor cannot depend on -shell"*), and `FrameworkTableTest` already checks the table against the real
  `Shell`. Each profile ships the same test, and the runtime provides it as a reusable base (`-testing`).
- **`Check` gets the model, not javac.** A check sees the resolved graph (bindings, channels, roots) plus the
  `Trees` reader `StepCheck` already uses, so `@Draw`'s rules move out as a check without a second tree walk.
- **The runtime's own refusals are not the profile's to lift.** The raw bus, `Senders`, `Lanes`, executors and
  `Disposer` are refused by the runtime for every profile, and so are the portability rules (§2.5). A profile can
  only add refusals.

### 2.3 Views and the main thread, generically

- **`@MainThread`**: every runtime has a host that owns the main thread. A profile's roots carry `onMain`, as
  today, and the runtime keeps the one-direction rule: nothing that isn't main-thread may take a main-thread
  value, except a view whose binding drains on the main thread. In a `@VexelApp` the main thread drives the window,
  and the GUI profile keeps refusing `@MainThread` in application code (T7.1). **A headless runtime gives the main
  thread to its host, and application code may own it** (ruled 2026-11-17). The proposed shape is that at most one
  daemon declares `@MainThread`, and the host runs that daemon's mailbox on the main thread instead of starting a
  thread for it. That covers a library with main-thread affinity, or a process whose one job is a loop. A
  `@MainThread` daemon is never portable (§2.5).
- **`@View`**: the runtime owns the rules that make it presentation: decides nothing, takes no job, no inbox, no
  component on another lane, and nothing that isn't a view takes it. The profile's `ViewBinding` owns *when and
  where it runs*. For the GUI that means in each frame's widget tick, from `TREE`, may take the `Gui`, with
  `window()`. On the headless profile it could mean per request, on the thread of the daemon that accepted it, and
  may take the request's channel. `@View.window()` moves into a GUI-profile annotation, or into the binding (Q3).

### 2.4 Daemons, and what a runtime is

**A daemon is the basic unit.** It is one class with `@Daemon`, one thread for the life of its runtime, a
mailbox it drains in arrival order, and messages in and out, nothing else. components.md §14 rules its mechanics,
and they all stand: the ordered `Pump`, supersession by a newer state value or a declared stop, edge capacity 64
with a fault that names the fix, an ordered shutdown with a bounded wait, the watchdog not watching it, its sends
recorded for replay as arriving from outside, and `@Source`/`@Destination` as optimised cases (one class is one
kind). What changes on 2026-11-17 is its standing. It isn't a fourth kind of framework component beside
`@Component`, `@Job` and `@Source`. It is what a runtime is built from, and it is defined in `vexelray-runtime`.

**Daemons are fixed at compile time.** The processor sees every daemon a runtime has. Nothing creates a daemon
while the runtime runs, and there are no keyed or dynamic instances. Work that varies (three documents to render,
three shards to serve) is either messages in one daemon's mailbox or more runtimes (§2.6).

**A runtime is a compiled set of daemons plus the host process that owns the main thread.** Its entry names it
and lists its starters, and the generated wiring builds its parts and starts its daemons in order.

- A `@VexelApp` is a runtime whose main thread drives the window.
- A headless runtime gives the main thread to its host (§2.3).
- What a runtime *does* is its daemons. Its lifecycle is theirs: they start in the order the wiring built them
  and stop in reverse, as `AutoCloseable` parts already do. The headless profile therefore needs only `config`
  and `run` phases. A "storage" or "listening" phase is a storage or listener daemon starting in order.

**Lifecycle hooks** (the author: *"If we give daemons lifecycle hooks, we can extend them if desired"*). The
proposal:

- *Started* is called once on the daemon's own thread, after the wiring has built every part and before the
  first delivery. A storage daemon opens its log here, and a listener binds its port.
- *Stopping* is called once on its own thread during §14's ordered shutdown, before the bounded wait ends in an
  interrupt. A storage daemon flushes here, and a listener stops accepting.
- The hooks are declarations the processor reads, for example `@Started` and `@Stopping` methods: no arguments,
  void, at most one of each per daemon, with a `@Subscribe` method's checks. A library may ship an abstract daemon
  whose hooks do the common part, and a project extends it. The names, the subclassing rule, and whether a daemon
  may wait for another's *started* message are Q11.

**The entry a headless runtime needs.** It needs one. Something has to carry the runtime's `name` (its app id,
log names and home directory come from it, as `Wiring.appId()` shows today) and its `starters`, which are named,
not found (`@VexelApp.starters`' javadoc). A spawned runtime also needs a compile-time handle, the generated
wiring class, for its parent to name (§2.6). `@VexelApp` can't be that entry, because it is the windowed runtime's,
and `@Daemon` can't, because it is the unit. The name and shape are open question Q10.

### 2.5 Portable daemons

A daemon has to be correct over the wire, not only in-VM, because the runtime it talks to may be in the same
process, another process or another machine (§2.6). The rules, as ruled on 2026-11-17, and how the processor
would hold them:

| Rule | What the processor checks |
|---|---|
| **Payloads are intrabuf records.** | Every payload a daemon hears or sends is a record annotated `sibarum.intrabuf.annotation.IntrabufMessage`, named by string as the processor names atchung types today. This replaces T2.4's in-VM deep-immutability check for daemon payloads. `Shareable` stays for what never leaves a runtime. |
| **Every ask has a deadline.** | A request that expects a reply (a job's request, an `Ask`, any request/reply pair the graph shows) carries a deadline component. A request without one is a compile error naming the record. |
| **Anything that changes state takes an idempotency key.** | A state topic is idempotent by its key already (latest wins, T4.1). An **edge** payload sent to a daemon is work of its own, so it must carry an idempotency key component, so that a retry over the wire is the same request and not a second one. |
| **Injection is portable or explicitly local.** | Everything a daemon takes in its constructor is either portable (a `@Setting`, an intrabuf value, a `Sender<T>` on a portable topic) or a value marked local to its runtime: a provided resource, a file handle, a GUI value, a `@MainThread` root. A local value is fine. The point is that the declaration says so, and the processor refuses a daemon that takes an unmarked value whose type isn't portable. Profile roots carry the flag, and `Shareable`'s GUI types are local by nature. |

How "deadline", "idempotency key" and "local" are spelled (a component type, an annotation on a record component,
an annotation on a provider) is Q16. The rules themselves are ruled.

### 2.6 Runtimes spawning runtimes

Daemons are fixed, so multiplicity and distribution come from runtimes. **A runtime can spawn another runtime and
integrate with it directly through elektro-Q**:

- **The handle is compile-time.** A parent names the child by its generated wiring class, so what can be spawned
  is known when the parent is built, like everything else here.
- **The link is elektro-Q.** The parent's and child's buses are bridged by an `ElektroBridge`
  (`vexelray-gui/docs/plans/architecture-proof.md` M3) over the in-VM transport when both share a JVM, and over
  `uds` or `tcp` when the child is another process or on another machine. Payloads are already intrabuf records
  (§2.5), so a topic crosses as it is, with no DTO layer.
- **The fast path stays fast.** As in architecture-proof §2 rule 3, the runtime's core never references
  elektro-Q. The link lives in its own module (`-link`, §3.1), and a runtime that spawns nothing never loads it.
- **Multiplicity:** three shards are three runtimes of one definition, each with its own daemons and its own
  watchdog, not one runtime with keyed daemons.

Whether a spawn is in-JVM, a child process, or both is Q12. Whether one binary can hold several runtime
definitions and choose one at launch is Q13. How a child's failure reaches its parent is Q14.

### 2.7 The knowledge engine as a network of portable daemons

For `vexelray-knowledge-engine` (`architecture.md` §11 distribution, §13 deployment modes, §14 modules), each
node role is a headless runtime, and the cluster is runtimes linked by elektro-Q:

| Runtime | Daemons (each with its hooks) | Linked to |
|---|---|---|
| **shard replica** (§11.1) | storage: log, version store, segments (*started* opens and replays the log, *stopping* flushes); txn: validation and ranking (§6–7); local indexes (§10); clock (HLC, §6.2) | the shard's other replicas (Raft), gateways, index nodes via the changefeed |
| **index node** (§11.3) | remote index maintenance, fed by changefeeds, serving reads at its watermark | shard replicas |
| **gateway** | planner (§3.1, §11.5), routing, scatter-gather (§11.2), auth (§12); its listener is a `@Source`/`@Destination` per client connection, and request handling is a `@View` on the headless per-request binding (§2.3) | clients, shard replicas |
| **embedded** (§13) | the same daemons, in a runtime spawned in the host application's JVM, linked over the in-VM transport | the host application's runtime (Q4) |

A single server is one process holding the gateway, one shard replica and its indexes: three runtimes spawned
in-JVM, or one runtime whose definition lists all three sets of daemons (Q13). §10's *"Plugins are registered by
explicit code at startup (a generated or hand-written registry)"* is `All<IndexPlugin>` provided by starters, so
the registry is generated, not hand-written. The engine needs no profile of its own unless it wants roots the
headless host doesn't have, and none is in sight.

---

## 3. The new repository

### 3.1 Name, coordinates, packages

**`vexelray-runtime`**, groupId `dev.vexelray.runtime`, packages under `dev.vexelray.runtime`.

| Module | Package | Contents | Depends on |
|---|---|---|---|
| `vexelray-runtime-api` | `dev.vexelray.runtime.api` | The vocabulary in §1.1's R rows: `@Daemon`, `Superseded`, the lifecycle hooks, the headless entry (Q10), and the portability markers (Q16) | JDK only |
| `vexelray-runtime-core` | `dev.vexelray.runtime.core` | `Daemons`, `Lanes` (with named job lanes), the tick (`Logic`, `Crew`, `Inboxes`), `Jobs`, `Sources`, `Destinations`, `Senders`, `Watchdog`, `MainThreadBeat`, `Timer`, `Disposer`, `Launch`, `Absences`, `Faults`, the `Wiring` base | `-api`, `atchung-core`, `atchung-probe` |
| `vexelray-runtime-spi` | `dev.vexelray.runtime.spi` | `Profile`, `Root`, `HandBack`, `Check`, `ViewBinding`, … | JDK + `java.compiler` |
| `vexelray-runtime-processor` | `dev.vexelray.runtime.processor` | The generic processor (§1.4 R rows), the portability rules (§2.5), and the built-in headless profile. Loads other profiles with `ServiceLoader` | `-api`, `-spi` |
| `vexelray-runtime-host` | `dev.vexelray.runtime.host` | The headless host: runs a wiring, owns the main thread and hands it to a `@MainThread` daemon if there is one, signals → orderly close | `-core` |
| `vexelray-runtime-link` | `dev.vexelray.runtime.link` | Spawning a runtime and linking its bus to the parent's over elektro-Q (in-VM, `uds`, `tcp`); a child's failure reported to its parent (Q14) | `-core`, `elektroq-*`, `atchung-elektroq`, `intrabuf-runtime` |
| `vexelray-runtime-testing` | `dev.vexelray.runtime.testing` | Compile-a-source-and-read-the-diagnostics harness (today in `VexelProcessorTest`), a golden-wiring assertion and the root-table test base, for profile authors; a two-runtime harness over the in-VM transport for daemon authors | `-processor`, `-link`, JUnit |
| `vexelray-runtime-architecture` | (test only) | Guards, by constant pool (as `vexelray-gui-architecture` does): no `dev.vexelray.gui`, `dev.vexelray.engine`, `dev.vexelray.framework` or `tactroller` name in any main class, and no elektro-Q name outside `-link` | test scope only |
| `vexelray-runtime-acceptance` | (profile) | Sample headless runtimes built and run, JVM and `-Pnative` (R3–R5) | everything above |

The `-api`/`-core` split keeps the rule this repo already holds: `-api` stays JDK-only, and `-core` takes the JDK
and atchung. The headless profile lives in `-processor`, on the processor path, and its host in `-host`, on the
runtime path, so no processor-path code reaches the binary. The wire is confined to `-link`.

### 3.2 What `vexelray-framework` becomes, later

The target for the later migration (§4.2), not work in this plan:

| Module | After |
|---|---|
| `vexelray-framework-api` | `@VexelApp` (the windowed runtime's entry, §2.4), `@Draw`, `@BeforeFrame`, `FrameStage`, and the GUI's view annotation if Q3 lands that way |
| `vexelray-framework-core` | The frame loop and window messages (§1.2 G rows) |
| `vexelray-framework-shell` | `Shell`, `VexelApplication`, `Views`, the GUI host: the host that gives the main thread to the window |
| `vexelray-framework-profile` (new) | The GUI `Profile`: today's `Framework` table, `SpiBoundary`, `TechniqueCalls`, the `@Draw` checks, GUI shareable types |
| `vexelray-framework-processor` | **Kept as an artifact**, emptied to a dependency on `vexelray-runtime-processor` + `-profile`, so every application's `<annotationProcessorPaths>` keeps working unchanged |

### 3.3 Run modes

`@OnMode(RunMode[])` takes enum constants, and an annotation can't take a value from an enum a profile defines.
There are three ways out:

- (a) `@OnMode(String[])`, checked against `Profile.modes()` at compile time. A misspelt mode is still a compile
  error, but now through the processor, not javac.
- (b) Each profile ships its own mode enum and its own `@OnMode`-shaped annotation.
- (c) The runtime fixes a small universal set (`NORMAL`, `HEADLESS`, `FIXED`) that profiles map.

(a) is recommended. It keeps one annotation and one rule, and the house rule (*a compile error beats a startup
error*) still holds. The GUI profile's `modes()` is `WINDOWED, FRAMES`.

---

## 4. Milestones

### 4.1 Building `vexelray-runtime`

The order is **copy, design, build**. What already works is copied in first, so the new repository starts with
tested code. The API is then designed on a clean desk, with nothing to stay compatible with. Then the new parts
(profiles, daemons, links) are built against that API. `vexelray-framework` and its applications are untouched
throughout, so they keep building at every step by construction.

**The runtime's bar**, kept green from R0 on:

1. `mvn verify` in `vexelray-runtime`, on `develop`.
2. **Native from day one:** `-Pnative verify` builds the acceptance binary with **empty** reachability config,
   from R0's trivial runtime to R5's linked ones.
3. The architecture guards (§3.1) are green, and each was *demonstrated red* when it was written.
4. Nothing in `vexelray-framework` or its consumers changed. A diff of their trees across the milestone is empty.

#### R0: Skeleton

- **Deliverable:** the repository, on `develop`, with the modules of §3.1 (empty where empty), the parent POM,
  `vexelray-build` stamping, a `vexelray-bom` entry, `bootstrap.sh`/`tools` aware of the new sibling, and the
  architecture guards. A trivial native build: a main that starts and exits.
- **Acceptance:** the bar. Each guard reports a planted violation.

#### R1: Copy what translates verbatim

- **Deliverable:** the R rows of §1.2–§1.4, copied and repackaged under `dev.vexelray.runtime`, with their tests.
  - In `-core`: `Lanes`, `Watchdog`, `MainThreadBeat`, `Timer`, `Disposer`, `Stalled`, `Logic`, `Crew`,
    `Inboxes` (without the close hearers), `Jobs`, `Sources`, `Destinations`, `Senders`, `Absences`, `Faults`,
    `Heard`, `UncleanExits`, `Ask`/`Answer`.
  - In `-processor`, as internal code with no public API yet: `Graph`, `Declarations`, `Channels`, `Topics`,
    `Mirrors`, `Report`, `Unresolved`, `RecordWithers`, `BuildBill`, and `StepCheck`'s and `Shareable`'s generic
    halves.
  - What carries a GUI constant (§1.4's R/split rows) has it cut out on the way in, not abstracted.
  - Each copied file's header names the `vexelray-framework` commit it came from, and `docs/copied.md` lists
    them. The later migration (§4.2) then has a diff to start from, and a fix made in the framework meanwhile is
    found, not lost.
- **Acceptance:** the bar, and the copied tests pass. Their processor tests read their vocabulary from a
  throwaway test-only copy of today's annotations until R2 replaces it.

#### R2: Design the API, clean slate

- **Deliverable:** `dev.vexelray.runtime.api`, designed fresh, with today's `-api` as a reference and not a
  constraint. It is written as a design document plus javadoc'd declarations, and ruled by the author before any
  processor code is written against it. In scope:
  - `@Daemon` as the unit (§2.4).
  - The headless entry (Q10).
  - The lifecycle hooks (Q11).
  - The portability markers (§2.5, Q16).
  - Run modes (§3.3).
  - `@View` without the window (§2.3, Q3).
  - `@MainThread` with the profile flag.
  - Where `@Component` and `@Job` stand beside the daemon (Q15).
  - What every surviving annotation from `vexelray-framework-api` becomes, if anything: `@Configuration`,
    `@Provides`, `@Default`, `@ConditionalOnType`, `All`, `@Setting`, `@Withers`, `@Subscribe`, `@Hears`,
    `@Publishes`, `@Job`, `@Source`, `@Destination`, `Closer`, `@OnMode`, `@Stability`.

  Each annotation's page states what it promises and whether the processor checks it, as `-api`'s package-info
  does today.
- **Acceptance:** the author's ruling, recorded in the design document. The declarations compile, and the
  `@Stability` level of each is stated. No compatibility with `dev.vexelray.framework.api` is a requirement.
  Where a name or shape carries over, it is because it is right, not because it existed.

#### R3: The processor, the profile SPI, the headless runtime

- **Deliverable:** the profile SPI (§2.2) and the processor against R2's API. The copied checks are adapted to
  the new vocabulary, and `Generator` is rewritten (it is where the old shape is most baked in). The built-in
  headless profile, `-host`, and the portability rules (§2.5). `-testing`'s compile harness and golden-wiring
  assertion.
- **Acceptance:** the bar. A headless sample with providers, `@Setting`, `All<T>`, `@OnMode`, a component and a
  job runs as a JVM program and as a native binary. Its generated wiring is checked in as a golden file. The
  processor's tests refuse each portability violation of §2.5.

#### R4: Daemons

- **Deliverable:**
  - `@Daemon` as the runtime's unit, with components.md §14's mechanics: atchung's ordered `Pump` and the per-key
    question (in atchung, additively, checked against tactroller's suite), `Superseded`, `Daemons` in `-core`,
    named job lanes, the processor's checks and graph node, and `DaemonsTest`.
  - The lifecycle hooks, and a `@MainThread` daemon on the host's main thread.
  - The acceptance sample grows to two daemons, one opening a file in its started hook and flushing it while
    stopping, and one bound to a loopback socket that takes what the first is built from.
- **Acceptance:** `mvn -Pnative verify` produces a binary with empty reachability config. It starts its daemons in
  order, answers on its socket, logs the watchdog's heartbeat, and runs the stopping hooks in reverse order on
  SIGTERM (and Ctrl+C on Windows), all checked by the test, not by eye.

#### R5: Runtimes spawning runtimes

- **Deliverable:** `-link`. A parent spawns a child by its wiring class, and their buses are bridged by
  `ElektroBridge` over the in-VM transport, and over `uds`/`tcp` for a child process (per Q12). The child's
  failure reaches its parent (per Q14). `-testing` gains the two-runtime harness.
- **Acceptance:**
  - **One conversation, every transport.** The same conversation test between R4's daemons passes in one
    runtime, in two runtimes in one JVM over the in-VM transport, and in two processes over `uds` and `tcp`
    loopback.
  - **Robust under loss.** It passes again under `SimTransport` loss and reordering, where idempotency keys and
    deadlines (§2.5) keep it correct.
  - **Native.** A native parent spawns a native child (per Q13 if they are one binary), with empty reachability
    config.
  - **Failure is not silent.** Killing the child is reported to the parent as Q14 settles, never as silence.
  - **The fast path stays clean.** The architecture guard shows no elektro-Q name outside `-link`.

#### R6: The knowledge engine adopts it

- **Deliverable:** in `vexelray-knowledge-engine`, the node roles of §2.7 as headless runtimes of portable
  daemons, wired by the processor. `knowledge-server` spawns its runtimes and `knowledge-embedded` is a runtime
  spawned in its host's JVM. The index registry is `All<IndexPlugin>`. The engine has no profile of its own.
- **Acceptance:**
  - The knowledge engine's own M0 native check (its architecture.md §0) is green with the runtime in it.
  - A three-node cluster on loopback, three replica runtimes and a gateway, passes the engine's first replication
    test.
  - An architecture test there shows that only the deployables and the daemon modules depend on
    `vexelray-runtime-core`, not `knowledge-api`.

The knowledge engine is the runtime's first real user. It can start as early as R3 (headless, no daemons yet) if
its own milestones want it, since nothing in the runtime depends on it.

### 4.2 Later, and separately: `vexelray-framework` onto the runtime

Not planned here, on purpose. It gets its own plan once R2's API is ruled and R4 has shown daemons work, so that
the migration is designed against a finished API and not alongside one. What that plan inherits from this one:

- **The target**, §3.2. The GUI profile implements §2.2's SPI: today's `Framework` table becomes its roots,
  hand-backs and refusals, and `SpiBoundary`, `TechniqueCalls` and `StepCheck`'s draw rules become its checks.
  `vexelray-framework-processor` stays as an artifact that pulls in the runtime's processor and the GUI profile,
  so applications' processor paths don't change.
- **The bar it should hold itself to**, found while writing this plan:
  - **Golden wirings.** Capture every consumer's generated `*Wiring.java` and bus graph before starting, and
    allow only diffs the plan names.
  - **Every consumer compiles.** That is every POM naming `vexelray-framework-processor`, which on 2026-11-17 was
    `mainframe`, `calculator-vexel-demo`, `text-editor-vexel-demo`, `vexelray-scheduler`, `vexelray-inspector`,
    `vexelray-mini`, `vexplore`, `Pix`, `phasearray`, `vexelray-game-engine`, `vexelray-codec`/`-feeds`,
    `vexelray-maistro`, `vex-4mat` and `vexelray-sim-*-demo`.
  - **Native unchanged.** The acceptance application and one consumer still build native with no hand-written
    config.
- **The copies to reconcile.** `docs/copied.md` (R1) says which framework commit each runtime file came from, so
  the migration starts by diffing forward and porting anything fixed in the framework since.
- **Imports, not semantics, where possible.** Where R2 kept an annotation's meaning, the consumer change is an
  import rewrite. Where it didn't, the migration plan has to say what each application writes instead.

---

## 5. Open questions

- **Q1: What is `@Daemon`? Closed.** [components.md](../components.md) §14 (ruled 2026-11-12) for its mechanics,
  and the author's ruling of 2026-11-17 for its standing: the runtime's basic unit, fixed at compile time, not a
  framework component kind (§2.4). §14's text should say its framing as a component kind is superseded, which is
  an edit to components.md and not part of this plan.
- **Q2: May application code own the main thread outside the GUI? Closed** (2026-11-17): a headless runtime gives
  the main thread to its host, and headless code may own it (§2.3). The shape, at most one `@MainThread` daemon run
  by the host, is this plan's proposal.
- **Q3: Where does `@View.window()` go?** It could go into a GUI annotation beside `@View` (for example
  `@InWindow("tools")`), into `@View` as a generic `String place()` that each binding interprets, or away into
  the view binding entirely.
- **Q4: Embedded mode.** §2.7 has `knowledge-embedded` as a runtime spawned in the host application's JVM and
  linked over the in-VM transport. Does the spawned runtime keep its own watchdog and its own `Lanes`, or share the
  host's?
- **Q5: A generic shutdown question.** `CloseRequested`/`CloseAnswer` asks each hearer before a window closes. A
  runtime wants the same for "shutdown requested; finish or refuse", and a spawned runtime for "your parent is
  stopping". Generalise it in the runtime, or leave it to each profile?
- **Q6: `vexelray-build` and `BuildBill`.** The generator reads the build bill. Does the knowledge engine build
  with `vexelray-build-maven-plugin`, or does the bill become optional for the runtime?
- **Q7: What a link carries.** When two runtimes are linked, does the bridge carry every topic both have, or only
  topics a runtime declares as exported? The second keeps a runtime's internal traffic internal and makes the
  link's surface something the processor can show.
- **Q8: `vexelray-framework-native`.** A second checkout of this repo, on branch `native-metadata`. Does its work
  land before R1 copies from it, so the copies start from the newer code?
- **Q9: One logic, two hosts.** This plan says one entry per compilation. A multi-module application whose `-core`
  is shared by a windowed runtime and a headless one would compile `-core` with no entry, and each host module with
  its own. Confirm that is the intended shape.
- **Q10: The headless runtime's entry.** It needs one (§2.4): something carries `name`, `formerNames` and
  `starters`, and is the handle a parent spawns. It can't be `@VexelApp` (the windowed runtime's) or `@Daemon`
  (the unit). Its name and shape are open. One option is an annotation of its own in `-runtime-api`. Another is
  for the entry to be the `@Configuration` that lists the runtime's daemons, with the identity members added there.
  The author's call.
- **Q11: Daemon lifecycle hooks.** §2.4 proposes *started* and *stopping* hooks (for example `@Started` and
  `@Stopping` methods). Still open: their names, whether a daemon may extend an abstract daemon from a library and
  keep its hooks, and whether start order by construction is enough, or a daemon may wait for another's *started*
  message before its first delivery.
- **Q12: How a runtime is spawned.** In the same JVM (in-VM transport, shared heap, cheap, fails together), as a
  child process (`uds`/`tcp`, isolated, can be restarted), or both, chosen at the spawn? Both is the natural
  reading of *"directly integrate with it via ElektroQ"*, but it doubles R5.
- **Q13: Several runtime definitions in one binary.** Can one jar or native binary contain several runtimes and
  pick one at launch (`--runtime=shard`)? That would let a parent spawn a child process from its own executable,
  and let one native-image build serve every node role, at the cost of a binary holding code each role doesn't use.
- **Q14: How a spawned runtime's failure reaches its parent.** Options: the link reports the child's exit or
  halt as a message on a topic the parent hears; the parent's watchdog treats a dead child as a stall; or a halt
  in the child halts the parent, as a halt in a lane does today. It depends on Q12, since a child process can be
  restarted and an in-JVM runtime that halts has probably taken the JVM with it.
- **Q15: Components and jobs under the daemon as the unit.** If the daemon is the runtime's basic unit, is a
  `@Component` a daemon that shares the logic lane's tick, and a `@Job` a daemon-owned pool? Or do they stay
  separate kinds beside it? R2 rules it.
- **Q16: Spelling the portability rules.** How a payload carries its deadline and its idempotency key (a typed
  record component the runtime defines, or an annotation on a component), and how a provided value is marked
  local to its runtime (an annotation on the provider, on the type, or a root's flag only).

---

## 6. Non-goals

- Changing `vexelray-framework` or any application on it. That is the later migration (§4.2), with its own plan.
- Runtime discovery of anything. Profiles are found on the processor path at compile time. The binary contains
  plain generated Java, as now.
- Keyed or dynamic daemons. A daemon is fixed at compile time. More of something is more runtimes (§2.6).
- Moving atchung, elektro-Q or intrabuf. The runtime depends on `atchung-core` as `-core` already does, and only
  `-link` depends on the wire.
- Designing the knowledge engine's protocol, storage or cluster beyond what §2.7 needs to show the runtime fits.

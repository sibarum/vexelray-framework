# Extracting the runtime model: `vexelray-runtime`

Status: **plan**. No code has moved. The repository name was chosen by the author on 2026-11-17.

The compile-time wiring in this repo, the `-api` vocabulary and the `-processor` that reads it, was written for a
desktop application with a window. Most of it isn't about windows. It describes how *any* VexelRay process is put
together and how it runs: parts built once in an inferred order, components that are actors on lanes, jobs,
sources and destinations, a watchdog, an orderly close. The first program that wants it without a window is
`vexelray-knowledge-engine`, a pure-Java distributed database. Its `docs/architecture.md` §0 asks for no
reflection, no runtime scanning and native-image builds with no hand-written reachability config, which is
exactly what this processor already provides.

This document plans how to move that model into a repository of its own, **`vexelray-runtime`**. Once it moves,
`vexelray-framework` becomes *the GUI profile of the runtime*: it supplies the window phases, the frame, the
shell's values and the GUI-only checks. A server supplies its own lifecycle in the same way.

**The scope is the runtime model, not just a DI library** (the author, 2026-11-17): *"It's the implementation of
my runtime model that would apply to any jar or native-image binary."* So the concurrency model, `@MainThread` and
`@View` move along with the injection. `@View` moves as a generic MVC presentation and `@MainThread` as a fact
about every process. A new `@Daemon` joins them. What stays here is what only a GUI has: the frame and its
stages, the window, and the binding that drains views inside a frame.

---

## 1. What is generic, what is the bus, what is the GUI

The classification below comes from the code as of `develop` on 2026-11-17, and each row gives its evidence.
There are three columns of destination:

- **R**: moves to `vexelray-runtime` (wiring or concurrency model).
- **R/split**: generic in concept, but today's declaration carries a GUI detail that has to come out first.
- **G**: stays in `vexelray-framework` as the GUI profile.

### 1.1 `vexelray-framework-api` (21 annotations and types, 1.4k lines)

| Type | Dest. | Evidence |
|---|---|---|
| `@Configuration` | R | Holds `@Provides`. Its `components`/`jobs`/`sources`/`destinations` members list runtime parts, and none is GUI-specific. |
| `@Provides` | R | Factory method, called once, with null meaning absent. Its javadoc mentions `Tactroller`/`Clipboard`, but only as examples. |
| `@Default` | R | Back-off rule, decided per mode (§3.3 for modes). |
| `@ConditionalOnType` | R | `Elements.getTypeElement` at compile time. Pure DI. |
| `All<T>` | R | Set-valued injection (regions.md). The processor names it by string in `Graph.ALL`. |
| `@Setting` | R/split | Concept is generic (key, default, typed accessor, one precedence order). But `Generator.setting` emits `shell.setting(k, def)`, so the settings source is the GUI `Shell` → it becomes a profile root (§2.2). |
| `@Withers` | R | Writes a record's withers. `RecordWithers` touches nothing else. |
| `@Stability` | R | A marker that nothing reads. Every moved type carries it. |
| `@Component` | R | An actor on the logic lane (T1.6). `lane = ...` names a `Crew` thread. No GUI type is involved. |
| `@Subscribe` | R | A mailbox on a component *or a view*. The view half's drain is a binding (§2.3). |
| `@Hears`, `@Publishes` | R | Declared ends of atchung channels. They are needed for the message graph and `StepCheck`'s ledger rule. |
| `@Job` | R | Request → job lane → reply. Runtime half is `Jobs` (atchung + probe only). |
| `@Source`, `@Destination`, `Closer` | R | Producers and writers outside the process. Runtime halves are `Sources`/`Destinations`, which import no GUI type. |
| `@MainThread` | R | The author: *"Everything that can run has a @MainThread."* Today it is refused in applications (T7.1), and the framework marks its own main-thread values in `Framework.Root.onMain` instead. The rule (a main-thread value may not reach a non-main part) is generic. Whether an application may claim the main thread is the profile's decision. |
| `@View` | R/split | The concept (presentation shows what the logic decided and reports what a person did, by messages, and decides nothing) works for an HTTP endpoint or a socket. Two details are GUI-specific: `window()`, and the javadoc's *"drained on the GUI thread in each frame's widget tick"*. Both move into the GUI profile's view binding. |
| `@OnMode` | R | A startup `if` on the mode. The mechanism is generic. |
| `RunMode` | R/split | Its values, `WINDOWED` and `FRAMES`, are GUI modes. The enum can't stay closed (§3.3). |
| `@VexelApp` | R/split | `name`, `formerNames` and `starters` are generic. `title`, `width`, `height` and `icon` are a window's. It splits into a generic entry and the GUI profile's entry (§2.4). |
| `@Draw` | G | A view's asked half, on every frame. Takes `Frame`/`Drawn`. |
| `@BeforeFrame`, `FrameStage` | G | Frame hooks. They have been refused since 0.2 and are kept only so the refusal can name what replaces them. |
| `@Daemon` | R (new) | Does not exist yet. Proposed in §2.4. Its meaning is open question Q1. |

### 1.2 `vexelray-framework-core` (26 types)

| Types | Dest. | Evidence |
|---|---|---|
| `Lanes`, `Watchdog`, `Timer`, `Disposer`, `Stalled`, `MainThreadBeat` | R | They import only `-api`'s `Stability`, `atchung` and `probe`. `MainThreadBeat` is the main thread's pulse for the watchdog. Its "parks when nothing is due" is GUI phrasing, but a server's main thread has the same three states. |
| `Launch` | R/split | Parses the generic `args`, but carries `RunMode` and `frames`. |
| `Phase` | → profile | `CONFIG, MODEL, GUI, TREE, WINDOW, ATTACH, RUN`. This is the GUI lifecycle. Each profile declares its own phases (§2.2). |
| `FrameHooks`, `Pacing`, `DeadlineSource`, `WakeSource` | G | The frame loop and its pacing. |
| `WindowCommand`, `WindowOpened`, `WindowClosed`, `WindowFullScreen`, `Modifiers`, `ThemeShifted`, `PickFile`/`Picked`, `Open`/`Opened` | G | Window and desktop messages. |
| `Ask`/`Answer` | R | A question from the logic to a person, answered by whichever view presents it: a dialog in a window, a prompt over HTTP or a socket. The records carry only an id, text, button labels and the asker's name, so they are MVC messages, not GUI ones. `Shell` answers them with native dialogs today, and that host side stays with the GUI profile. |
| `CloseRequested`/`CloseAnswer` | G for now | A window's close, asked of each hearer in turn. A server's equivalent is "shutdown requested; components may delay it", which is a generic cousin. That is open question Q5, not part of the first move. |

### 1.3 `vexelray-framework-shell` (53 types, the runtime half of the wiring)

GUI-free today: `Logic` (the tick), `Crew`, `Inboxes`, `Jobs`, `Sources`, `Destinations`, `Senders`, `Absences`,
`Faults`, `Heard`, `UncleanExits` and the `Wiring` base class (0 GUI imports each). They move with the runtime
model. `Inboxes` names `Answer` (which moves with it) and `CloseRequested` (which doesn't, until Q5) by type
for the hearers, and the second dependency is cut along with Q5. `Views` (imports `Gui`), `Shell` (8 GUI imports), `VexelApplication` (12) and the rest are the GUI
profile's host and stay. `Stalls` and `Closes` have one GUI import each and need a look when M3 reaches them.

### 1.4 `vexelray-framework-processor` (15 classes, 7.2k lines)

The processor depends on `-api` and on `-core`, the latter for `Phase` alone. It names `-shell` and
`vexelray-gui` types only by string, so that the graphics stack stays off the processor path. That is why the
GUI coupling is spread through the processor as string constants, not imports.

| Class | Lines | Dest. | Evidence |
|---|---|---|---|
| `Graph` | 803 | R | Whole-program resolution, `@Default`/`@OnMode`/`@ConditionalOnType`/`All`/`Optional`, the colour rules (T2.2/T2.3/T3.7) and executors refused. It reads `View`, `MainThread`, `Hears` and `Subscribe` too, but each is runtime vocabulary once §1.1 holds. Main-thread-ness comes from `Framework.Root.onMain`, which becomes a profile root's flag. |
| `Declarations` | 1066 | R | Shape checks (one constructor, `@Provides` in a configuration, `@Setting` types, …). It checks `@View` shapes, which are generic by §1.1. |
| `Generator` | 1331 | R/split | Phase inference (*"the latest phase of anything a part takes"*), mode narrowing, `if`s per mode and reverse close are generic. GUI parts: `Phase` constants, the floor at `Phase.TREE` for a `@View` (line 659), `Framework.method(phase)` naming `Wiring`'s six methods, `shell.setting(...)`, the frame-stage array, `@VexelApp`'s window facts and the hand-backs. All of those become profile data (§2.2). |
| `Channels` | 775 | R | The message graph and T4.1/T2.4 on payloads. Atchung is part of the runtime model. `GUI_PACKAGE = "dev.vexelray.gui."` (widget messages heard by a view aren't crossings) becomes a profile hook. `TIMER` moves with `Timer`. |
| `Topics` | 171 | R | `Topic.edge`/`Topic.state` by payload. Atchung only. |
| `StepCheck` | 1332 | R/split | Ledger steps (captures, statics, locks, effects, `@Publishes`) and deliveries that block (T5.8) are generic. `@Draw` bodies (no allocation, no log, no bus), display functions handed to a widget, `Gui.hear/mailbox/batch` and the widget-package constants are GUI. The reader and the effect table are shared, and the GUI rules plug in as a contributed check (§2.2). |
| `Shareable` | 297 | R/split | Deep immutability is generic. `Feed`, `ImageSdg`, `Node`, `TechniqueView`, `Paint`/`Role`/`Ink` are GUI value types accepted by contract. They become a profile's "trusted value types". |
| `Unresolved`, `Mirrors`, `Report` | 337 | R | Reading the vocabulary, counting errors, missing types (FN-30). |
| `RecordWithers` | 174 | R | |
| `BuildBill` | 82 | R | Reads `vexelray-build`'s bill. This is specific to the stack, not to the GUI. The knowledge engine is built with `vexelray-build` too (open question Q6). |
| `Framework` | 222 | G | The table: ~40 `Shell`/GUI type names as roots in `CONFIG`/`TREE`/`WINDOW`/`ATTACH`, hand-backs, `REFUSED` (raw bus, `Senders`, `Lanes`, `FrameHooks`, `Disposer`) and `SHELL_REFUSED`. *Its shape* (roots, hand-backs, refusals) is the SPI. *Its contents* are the GUI profile. `REFUSED` splits: the bus, `Senders`, `Lanes` and `Disposer` refusals are runtime rules, and `FrameHooks` is the GUI's. |
| `SpiBoundary` | 82 | G | A `@View` may not name `dev.vexelray.gui.core.spi`. |
| `TechniqueCalls` | 120 | G | `RenderTechnique.realize/record/close` are called only by the host. |
| `VexelProcessor` | 405 | R/split | The entry point and round logic. Its `VOCABULARY` list mixes all three columns. `@BeforeFrame`/`@Draw`/`@VexelApp` become a profile's extra annotations. |

Rough proportions: about 5.5k of the 7.2k lines are generic once their GUI constants are lifted out. About 0.4k
are GUI outright (`Framework`, `SpiBoundary`, `TechniqueCalls`). The remaining ~1.3k are GUI rules inside shared
classes (`StepCheck`'s draw rules, `Generator`'s GUI emission and `Shareable`'s GUI types), and they move out
behind the SPI.

---

## 2. The profile SPI

### 2.1 The idea

The processor today is one environment hard-coded: the roots, the phases, the wiring base class, the extra
annotations and the extra checks are all GUI. A **profile** is that environment written as data plus a few hooks,
loaded by the generic processor. `vexelray-framework` ships the GUI profile, and a server ships its own.

The profile lives **on the processor path**, not the runtime path. It is compile-time code like the processor, so
loading it with `ServiceLoader` from the processor path is fine. The application's binary never sees it, so
nothing about it reaches native-image. The ban on runtime discovery (`-api`'s package-info: *"Nothing here is
discovered"*) is about the application's classpath at run time. The processor path is javac's.

**Choosing the profile:** each profile declares its **entry annotation**, and the processor runs the profile
whose entry annotation the compilation uses. `@VexelApp` selects the GUI profile and `@Daemon` selects the
daemon profile (§2.4). If a compilation has none (a library or a starter compiled alone), only the generic checks
run, as `Graph` already allows today (*"A starter compiled on its own is allowed to need things"*). Two entries,
or an entry whose profile is not on the processor path, is a compile error naming the missing artifact.

### 2.2 What a profile contributes

A sketch, not a final signature. Its first draft is M1, done *inside* this repo, where the GUI profile is the
only implementation and the golden wirings keep it honest.

```java
package dev.vexelray.runtime.spi;

/** One environment a VexelRay process can run in: its lifecycle, what the host hands out, and its own rules. */
public interface Profile {

    /** Canonical name of the entry annotation that selects this profile ("dev.vexelray.framework.api.VexelApp"). */
    String entry();

    /** The construction phases, in order. A part is built in the latest phase of anything it takes. */
    List<String> phases();                       // GUI: config, model, gui, tree, window, attach

    /** The generated class's base, and the method each phase's construction goes in. */
    String wiringBase();                         // GUI: dev.vexelray.framework.shell.Wiring
    String phaseMethod(String phase);

    /** What the host owns and hands out: type, the expression that reaches it, its first phase, main thread or not. */
    List<Root> roots();                          // today's Framework.ROOTS
    List<HandBack> handBacks();                  // today's Framework.HAND_BACKS
    Map<String, String> refused();               // added to the runtime's own refusals, each with its reason

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
  `Shell`. Each profile ships the same test, and the runtime provides it as a reusable base (M2).
- **`Check` gets the model, not javac.** A check sees the resolved graph (bindings, channels, roots) plus the
  `Trees` reader `StepCheck` already uses, so `@Draw`'s rules move out as a check without a second tree walk.
- **The runtime's own refusals are not the profile's to lift.** The raw bus, `Senders`, `Lanes`, executors and
  `Disposer` are refused by the runtime for every profile. A profile can only add refusals.

### 2.3 Views and the main thread, generically

- **`@MainThread`**: a profile's roots carry `onMain`, as today. The runtime keeps the one-direction rule:
  nothing that isn't main-thread may take a main-thread value, except a view whose binding drains on the main
  thread. Whether application code may *declare* `@MainThread` is a profile flag. The GUI profile keeps refusing
  it (T7.1). A daemon profile might allow it for, say, a signal handler. That is open question Q2.
- **`@View`**: the runtime owns the rules that make it presentation: decides nothing, takes no job, no inbox, no
  component on another lane, and nothing that isn't a view takes it. The profile's `ViewBinding` owns *when and
  where it runs*. For the GUI that means in each frame's widget tick, from `TREE`, may take the `Gui`, with
  `window()`. For a server it could mean per request on a lane of its own, from a `listening` phase, and may take
  the request's channel. `@View.window()` moves into a GUI-profile annotation, or into the binding (Q3).

### 2.4 Entries: `@VexelApp` and `@Daemon`

The generic runtime needs an entry with `name`, `formerNames` and `starters`. The window facts (`title`,
`width`, `height`, `icon`) are the GUI's. The proposal:

- `@VexelApp` stays where it is, as **the GUI profile's entry**. It keeps its window members, and the processor
  reads its generic members by name.
- `@Daemon` is **the entry of a process with no window**: the runtime's own headless profile. Its lifecycle is
  `config → services → run`, with the close in reverse as always. The knowledge engine either uses it or ships a
  profile of its own that extends it (§5, Q1).

That reading of `@Daemon` is this plan's guess. The author named it, and nothing in the code defines it yet.

### 2.5 What a server's lifecycle looks like

For the knowledge engine (`architecture.md` §13 deployment modes, §14 modules), a profile could be:

| Phase | Built here | Roots from the host |
|---|---|---|
| `config` | settings, launch, node identity | `Launch`, settings source, `Timer` |
| `storage` | log, version store, segment files (§9) | data directory, clock (HLC, §6.2) |
| `services` | txn manager, index registry (§10), auth (§12), planner | the storage roots |
| `listening` | wire endpoints on elektro-Q (§13), changefeeds; views here are per-request handlers | transport, bound addresses |
| `run` | nothing is built; components tick, the watchdog watches | |

The close runs in reverse: stop listening, drain, flush storage. §10's *"Plugins are registered by explicit code
at startup (a generated or hand-written registry)"* is `All<IndexPlugin>` provided by starters, so the registry is
generated, not hand-written.

---

## 3. The new repository

### 3.1 Name, coordinates, packages

**`vexelray-runtime`**, groupId `dev.vexelray.runtime`, packages under `dev.vexelray.runtime`.

| Module | Package | Contents | Depends on |
|---|---|---|---|
| `vexelray-runtime-api` | `dev.vexelray.runtime.api` | The vocabulary in §1.1's R rows, plus `@Daemon` | JDK only |
| `vexelray-runtime-core` | `dev.vexelray.runtime.core` | `Lanes`, the tick (`Logic`, `Crew`, `Inboxes`), `Jobs`, `Sources`, `Destinations`, `Senders`, `Watchdog`, `MainThreadBeat`, `Timer`, `Disposer`, `Launch`, `Absences`, `Faults`, the `Wiring` base | `-api`, `atchung-core`, `atchung-probe` |
| `vexelray-runtime-spi` | `dev.vexelray.runtime.spi` | `Profile`, `Root`, `HandBack`, `Check`, `ViewBinding`, … | JDK + `java.compiler` |
| `vexelray-runtime-processor` | `dev.vexelray.runtime.processor` | The generic processor (§1.4 R rows). Loads profiles with `ServiceLoader` | `-api`, `-spi` |
| `vexelray-runtime-daemon` | `dev.vexelray.runtime.daemon` | The headless host: `Daemon.run(wiring, args)`, signals → orderly close | `-core` |
| `vexelray-runtime-daemon-profile` | `dev.vexelray.runtime.daemon.profile` | The `@Daemon` profile (processor path) | `-spi` |
| `vexelray-runtime-testing` | `dev.vexelray.runtime.testing` | Compile-a-source-and-read-the-diagnostics harness (today in `VexelProcessorTest`), a golden-wiring assertion and the root-table test base, for profile authors | `-processor`, JUnit |
| `vexelray-runtime-architecture` | (test only) | Guard: no `dev.vexelray.gui`, `dev.vexelray.engine`, `dev.vexelray.framework` or `tactroller` name in any main class, by constant pool (as `vexelray-gui-architecture` does) | test scope only |
| `vexelray-runtime-acceptance` | (profile) | A sample daemon built and run, JVM and `-Pnative` | everything above |

The `-api`/`-core` split keeps the rule this repo already holds: `-api` stays JDK-only, and `-core` takes the JDK
and atchung. Splitting the profile from its host (`-daemon` vs `-daemon-profile`) keeps processor-path code out
of the binary.

### 3.2 What `vexelray-framework` becomes

| Module | After |
|---|---|
| `vexelray-framework-api` | `@VexelApp`, `@Draw`, `@BeforeFrame`, `FrameStage`, and the GUI's view annotation if Q3 lands that way |
| `vexelray-framework-core` | The frame loop and window messages (§1.2 G rows) |
| `vexelray-framework-shell` | `Shell`, `VexelApplication`, `Views`, the GUI host |
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

The order is **seam first, move last**. The profile boundary is drawn and proven *inside this repo*, where one
reactor builds everything and a mistake is a red test, not a broken downstream. Lifting it into a new repository
comes only after the boundary has held for a few milestones, and at that point the lift is a file move.

Each milestone leaves these green, called **the bar**:

1. `mvn verify` in `vexelray-framework`, and `-Pacceptance`.
2. **Golden wirings**: the generated `*Wiring.java` and the bus graph for the template's sample and for each
   consumer application are byte-identical to the M0 capture, unless the milestone says which diff it means to
   make. This is the strongest available proof that "nothing changed" for an application.
3. The consumer applications compile: `mainframe`, `calculator-vexel-demo`, `text-editor-vexel-demo`,
   `vexelray-scheduler`, `vexelray-inspector`, `vexelray-mini`, `vexplore`, `Pix`, `phasearray`,
   `vexelray-game-engine`, `vexelray-codec`/`-feeds`, `vexelray-maistro`, `vex-4mat`, `vexelray-sim-*-demo`
   (every POM naming `vexelray-framework-processor` on 2026-11-17). That list is re-taken at M0.
4. **Native**: the acceptance application and one consumer build with `native-image` with no hand-written
   reachability config, as they do now (or, if they don't yet, no worse than the M0 record).

### M0: Baseline

- **Deliverable:** a `golden` capture of every generated source above. A script that rebuilds the consumers
  against the local reactor and diffs against the capture. A record of which native builds are green today.
- **Acceptance:** the diff script runs clean on an untouched tree, and *demonstrably red* when one `@Provides` is
  renamed in the sample, so the guard is known not to be vacuous.

### M1: The seam, in place

- **Deliverable:** `Profile` and friends as package-private types in `-processor`. `Framework`'s table becomes
  `GuiProfile implements Profile`. `Phase` becomes names, and `-processor` drops its `-core` dependency.
  `shell.setting`, the `TREE` floor for views, the frame-stage array, the GUI types in `Shareable` and
  `Channels.GUI_PACKAGE` are read from the profile. `StepCheck`'s draw and display rules, `SpiBoundary` and
  `TechniqueCalls` run as the profile's `checks()`.
- **Acceptance:** the bar, with **zero** golden diff. Nothing should change for an application, and nothing does.

### M2: Two processor modules, one repo

- **Deliverable:** split `-processor` into `-processor-runtime` (generic, public `spi` package) and
  `-profile` (GUI). `ServiceLoader` loads the profile. `-processor` becomes the aggregate (§3.2). The
  `vexelray-runtime-architecture` guard is written now and pointed at `-processor-runtime`.
  `FrameworkTableTest` is rebased on the reusable root-table test.
- **Acceptance:** the bar with zero golden diff. The guard is green and *demonstrated red* by putting
  `"dev.vexelray.gui.core.Gui"` back into a runtime class.

### M3: The runtime half, in place

- **Deliverable:** a `-runtime-core` module in this repo taking §1.3's GUI-free shell classes and §1.2's R rows.
  `Shell` delegates to it. `Inboxes`' close hearers go through a profile-supplied list, so `-runtime-core` names no
  close or answer message. The guard covers it.
- **Acceptance:** the bar. The golden diff is limited to fully qualified names in generated code, and is reviewed
  and re-captured once.

### M4: The vocabulary, split and generalised

- **Deliverable:**
  - The R annotations move to `dev.vexelray.runtime.api` in a `-runtime-api` module. **The processor accepts both
    the old and the new canonical names** for one release (`Mirrors` already matches by canonical name, so this
    is a two-entry table), and an old name is a warning that says what to import instead.
  - `@VexelApp` keeps its window members, and the processor reads its generic members through the profile's entry.
  - `RunMode`/`@OnMode` get §3.3's resolution.
  - `@View` loses `window()` to the GUI side, per Q3.
  - `@MainThread`'s rule moves to the runtime, with the profile flag. The GUI profile still refuses it.
- **Acceptance:** the bar, with consumers still on the **old** imports (compatibility proven), and the template's
  sample switched to the **new** imports with zero golden diff (equivalence proven).

### M5: Lift into `vexelray-runtime`

- **Deliverable:** the `-runtime-*` modules and their history move to the new repository (`git filter-repo` on a
  clone, per path). The new repo works on `develop`, as every repo does. `vexelray-framework` depends on its
  installed artifacts. `vexelray-bom` lists both. `vexelray-build` stamps the new modules.
  `bootstrap.sh`/`tools` learn the new sibling.
- **Acceptance:** the bar, from a clean `~/.m2` built through `bootstrap.sh`. **Both** native checks pass: the
  framework's, and the new repo's own (M6).

### M6: The daemon profile, and native proof without a window

- **Deliverable:** `-daemon`, `-daemon-profile`, `@Daemon`, and `vexelray-runtime-acceptance`: a sample daemon
  with a component on a declared lane, a job, a source on a loopback socket, a `@Setting`, `All<T>` from two
  starters, `@OnMode`, and a clean shutdown on a signal.
- **Acceptance:** `mvn -Pnative verify` in `vexelray-runtime` produces a binary with **empty** reachability
  config. It starts, answers on its socket, logs the watchdog's heartbeat, and closes in reverse order on SIGTERM
  (and Ctrl+C on Windows), all checked by the test, not by eye. This is the claim the knowledge engine needs,
  proven before it depends on it.

### M7: Consumers move to the new names

- **Deliverable:** each consumer's imports are rewritten (mechanical, one commit per repo on its `develop`).
  After all of them, the old names are removed from the processor's table.
- **Acceptance:** the bar for each consumer, and then for all of them once the old names are gone.

### M8: The knowledge engine adopts it

- **Deliverable:** in `vexelray-knowledge-engine`, a `knowledge-profile` (or `@Daemon` use, per Q1) with §2.5's
  phases. `knowledge-server` and `knowledge-embedded` are wired by the processor, and the index registry is
  `All<IndexPlugin>`.
- **Acceptance:** the knowledge engine's own M0 native check (its architecture.md §0) is green with the runtime in
  it. An architecture test there shows that only the deployables, not `knowledge-api`/`-storage`/`-txn`, depend on
  `vexelray-runtime-core` (Q4).

---

## 5. Open questions

- **Q1: What is `@Daemon`?** §2.4 reads it as the headless entry annotation, the sibling of `@VexelApp`, with the
  runtime shipping a small default profile. Other readings: a *part* kind (a long-lived background service inside
  any application, between a `@Component` and a `@Source`), or a profile the knowledge engine defines itself. The
  author's call.
- **Q2: May application code claim the main thread outside the GUI?** T7.1 refuses it for the GUI. A daemon's
  main thread has little to do. Is it the watchdog's and the signal handler's, or may a part ask for it?
- **Q3: Where does `@View.window()` go?** It could go into a GUI annotation beside `@View` (for example
  `@InWindow("tools")`), into `@View` as a generic `String place()` that each binding interprets, or away into
  the view binding entirely.
- **Q4: Embedded mode.** In `knowledge-embedded` the engine runs inside someone else's process, which may be a
  VexelRay GUI application. Is the engine then a set of starters for the host's wiring, so its components join the
  host's tick? Or does it bring a runtime of its own? The first is simpler and keeps one watchdog per process.
- **Q5: A generic shutdown question.** `CloseRequested`/`CloseAnswer` asks each hearer before a window closes. A
  server wants the same for "shutdown requested; finish or refuse". Generalise it in the runtime, or leave it to
  each profile?
- **Q6: `vexelray-build` and `BuildBill`.** The generator reads the build bill. Does the knowledge engine build
  with `vexelray-build-maven-plugin`, or does the bill become optional for the runtime?
- **Q7: The intrabuf/elektro-Q boundary.** The knowledge engine's protocol messages are intrabuf records over
  elektro-Q (§13). Do they appear as atchung topics inside the runtime (bridged by `ElektroBridge`, as
  `architecture-proof.md` plans for the GUI), or does the wire stay outside the runtime model?
- **Q8: `vexelray-framework-native`.** A second checkout of this repo, on branch `native-metadata`. Does its work
  land before M1, so the seam isn't drawn twice?
- **Q9: How many profiles may one compilation see?** This plan says exactly one entry per compilation. A
  multi-module application whose `-core` is shared by a GUI and a daemon build (one logic, two hosts) would
  compile `-core` with no entry and each host module with its own. Confirm that is the intended shape.

---

## 6. Non-goals

- Changing any rule an application meets today. M1–M3 are pure refactors, held to zero golden diff.
- Runtime discovery of anything. Profiles are found on the processor path at compile time. The binary contains
  plain generated Java, as now.
- Moving atchung or elektro-Q. The runtime depends on `atchung-core` as `-core` already does.
- Designing the knowledge engine's protocol, storage or cluster wiring beyond what §2.5 needs to show the profile
  fits.

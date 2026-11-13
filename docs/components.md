# The component model

What a component is, what the framework owes it, what exists, and what is still to be decided. This is the
spec the declaration seam is built against. It does not repeat the other three documents it summarises:

- [architecture.md](architecture.md#the-concurrency-model) is the **reasoning** (why one thread, why static placement).
- [threading.md](threading.md) is the **rules**, each with what enforces it.
- [v1.md](v1.md#the-liveness-guarantee) is the **contract** v1 freezes, including the liveness guarantee.

Where this page and one of those disagree, that is a bug in one of them; say which in the commit.

**Status of this page, 2026-10-02.** The nine rulings below were put to the user together on 2026-10-01 and
accepted as a batch, so most are labelled *Accepted as recommended*: adopted from a recommendation without
discussion, valid but unexamined, to be re-checked when one matters. *Ruled* is kept for what the user decided
with a reason of their own: that a full FAIL mailbox halts the process (ruling 5), that `superseded()` is deleted,
and that the designer is not ported. Every ruling states its reason, and a new one must; see TODO.md, *A ruling
carries its reason*. Rulings 1 and 2 are complete.

> **0.2, 2026-10-26: the message model.** [threading.md](threading.md) was rewritten around six rulings (its §0),
> and this page has not caught up beyond the notes marked *0.2* below. Read threading.md first where they
> disagree. What changes here, in summary:
>
> - A component is **a mailbox and private state**, not a thread. Components that declare no lane share one
>   **logic lane** (T1.6), which is what `Component.DEFAULT_LANE` becomes. They drain once per tick, not
>   continuously (T5.5).
> - Every part with behaviour is a component, including what 0.1 left as plain provided objects (`Ui`, `Actions`,
>   `Model`), so ruling 9's answer covers them too. A component never holds another, on any lane (T2.3).
> - Sends are constructor parameters, and `@Publishes` goes (T4.5). Each topic is state or edge (T4.1).
> - The table under *What a component is owed* was rewritten in phase 4, when the tick replaced `Placement`.
> - `@Subscribe` has no loss policy: the topic's kind is the policy (T4.2), and `Overflow` is gone. Ruling 5 below
>   is 0.1's.

## What a component is

A `@Component` is **a thread and a mailbox**: a class constructed once, before the loop runs, by its one
constructor, that runs on a *lane* and is reached by publishing, never by holding. A value that needs only a
lifetime and swappable behaviour is a `@Provides`; a value is neither. The main thread is not a lane and a
component never runs there: no application code does (T7.1).

## What a component is owed

*Rewritten for 0.2 in phase 4, 2026-10-29, when the tick replaced `Placement`.*

| The framework owes it | Held by | Rule |
| --- | --- | --- |
| A place on the logic lane's tick, delivered one message at a time and never beside another component | `Logic`, `LogicTest` | T1.6 |
| A thread of its own, `vexel-component-<lane>`, never virtual, when it declares a lane, meeting the rest at every round's end | `Crew`, `LanesTest`, `LogicTest` | T1.2, T1.4, T5.6 |
| The same deliveries in the same order whether the ticks run on their own thread or the main thread | `LogicTest`'s witness | T1.7, T5.6 |
| A mailbox per `@Subscribe`, on the topic its payload declares, with its kind and an edge bound | processor, `Inboxes` | T4.1, T4.2, T4.3 |
| A key filter on a state topic, done by the wiring | processor, `Inboxes` | T4.8 |
| A sender per payload it sends, on that payload's declared topic, ordered by the tick | processor, `Inboxes.sender` | T4.5 |
| Construction before the loop; the first tick only after everything is built | `LogicTest` | T6.1 |
| A wake of the frame loop after a tick that delivered, with nothing to call | `LogicTest` | T5.3 |
| The last tick at shutdown, inside a total bound after which the process halts | `LogicTest`, `Disposer.beforeClose` | T6.2, liveness |
| Its lane's own `ThreadGroup` and gauge, so a wedged lane is reported and interrupted by name | `Lanes`, `Watchdog`, `LivenessTest` | T6.5, liveness |
| That no component and no provider holds it | processor | T2.3, T3.7 |
| That a value crossing into it shares nothing with the sender's heap, and a value provided to it is shareable | processor (`Shareable`) | T2.4, T2.6 |
| That nothing in it asks for synchronisation | processor | T2.7 |

And what it owes the application: it never blocks (a `@Job` does), never touches the timeline (T3.2), and returns
work as a message (T5.1). A wedged component holds the logic lane, or its own declared lane, and the window keeps
painting in the default mode (T6.5); a component that throws ends the application, because nothing declared that
failure (section 10).

## What exists, what is decided, what is not

| | Built | Decided, unbuilt | Undecided |
| --- | --- | --- | --- |
| Threads | `Lanes`, `Placement`, default lane, platform-only, bounded offload | handler lane bound (waits on a census) | |
| Declaration | `@Component(lane)`, `@Subscribe`, `@Publishes` | what the nine rulings below (accepted 2026-10-01) ordered and is not built: the capture rule | |
| Colour | T2.1 to T2.4 (T2.4 as a check: payloads must be deeply immutable), T3.1, T3.7 | the copier's pattern (annotate a record, processor emits the copy) | its annotation's name; the capture check's mechanism |
| Channels | T4.1, T4.3, T4.4, T4.7 for declared sends | | the graph as generated data (additive) |
| Completion | `@Job` with its reply topic (section 10), `Lanes.offload()`, `app.post` | two doors; the executors withdrawn from application parts | `kronometer` `offload` and the timeline door unbuilt |
| Liveness | watchdog, busy gauge, shutdown bound, policy seam | main-thread heartbeat that knows a dialog is up | |
| Tree | | T3.5: the tree does not restrict messaging | **T3.4, T3.6, T6.3** |

## The rulings owed

Eight were named in [TODO.md](TODO.md) when the component model was ruled frozen at v1; a ninth turned up in
drafting this page. They are ordered by how much each one blocks, not by importance.

### 1 and 2. The imperative path, and a public `Shell.place` — one change

`Placement.subscribe(...)` and `Shell.place(name)` are the original, imperative way to make a mailbox and a
placement from inside a wiring method body. `@Subscribe` and `@Component(lane)` replaced them for everything a
declaration can say. Kept beside them, the imperative form is a second way to do one thing
([v1.md](v1.md#what-must-not-remain)), and `Shell.place` is a public call any part taking the `Shell` can make
at any time, which is exactly what T1.3 (*placement is decided in the wiring and never at runtime*) says
nothing may do. T1.3 is half held for that reason alone.

**Accepted as recommended 2026-10-01, and carried out at the user's direction (steps a to g): remove both from the surface an application sees.** `@Subscribe` is the one way. The generated
wiring still has to call something, so what survives is one entry point that generated code uses and
application code has no reason to; Java has no friend visibility, so say so in its Javadoc and keep it off
`Shell`. This makes T1.3 held rather than half held, and it deletes the cheapest way to build a cross-lane
reference by accident.

**What it costs and what to check first.** `@Subscribe` cannot express everything the imperative form can: its
payload is one class or interface with *no type arguments*, and a mailbox is one per method. Before removal,
list what the designer, W2 and the test fixtures subscribe with by hand, and either widen `@Subscribe` or
decide the missing shape is not a component. `VexelProcessorTest` fakes both calls, so it moves with this.

**Inventory, taken 2026-10-01.** Every use of `Shell.place`, `Placement.subscribe` and `Placement` across this
repo and the designer. Nothing in `vexelray-gui`, `mainframe`, `vexplore` or the template names any of them.

*Generated code, which stays and is the one residual entry point:*
- `Generator.java:713` and `:753` emit `shell.place(<lane>)`; `:755` emits `<lane>.subscribe(Topic.of(..), ..)`
  for each `@Subscribe`. The `Fold` overload is not emitted by anything.
- `ComponentsWitnessTest` (lines 79 to 82) and `LongRunWitnessTest` (81) assert the generated text contains
  `shell.place(...)`, so they move with whatever the generated call becomes.

*A third path the rulings did not name, and the one that changes the answer.* **A `@Component` constructor may
take a `Placement`**, handed its own lane's (`Framework.PLACEMENT`, `LaneArg`, `Graph.java:258`), and subscribe
on it by hand. `Component`'s Javadoc (line 53) documents it, and the processor test's `Worker`
(`VexelProcessorTest:812`) uses it. That is the imperative path reached without `Shell.place` at all, so
deleting `Shell.place` alone leaves it open. **Ruling 1 therefore has to cover it:** an application component
takes no `Placement` for subscribing, and the injectable-root table loses the entry or narrows it.

*What `Placement` was for besides subscribing: `superseded()`, now deleted (ruled 2026-10-01).* It asked "is my
current message obsolete?" and answered "is anything queued on this lane?", which agree only when every message
replaces the last outright and the lane carries nothing else, and nothing said or checked either. **The framework
does not pretend to know more than it does:** what a message means to the next one is the component's to handle
while it drains, by draining more often and by raising `@Subscribe(capacity)`. Its one caller was the untracked
designer (`Viewport:331`). With it gone nothing component-facing is left on `Placement`, so (c) is unblocked.

*Test seams in the public surface* (a legacy-sweep entry in its own right):
- `new Placement(name, bus, lanes)` is public so the designer's `TheLastEditIsTheOneOnScreenTest` (lines 101
  and 124) and `Viewport`'s constructor (221) can build one without a container. It is documented as a test seam.
- `Placement.start()` and `onWake(..)` are public for the same reason and are documented as not the application's.

*Callers by hand:*
- **This repo:** `PlacementTest` (eight sites, lines 49 to 202) and `LivenessTest` (55, 88, 122) call
  `shell.place(..).subscribe(..)`. These test the mechanism, not an application, so they move to whatever the
  residual entry point is.
- **`vexelray-designer`**, which `CLAUDE.md` says is untracked and nothing here is shaped to keep compiling:
  `DesignerWiring:162` (`shell.place("compose")`), `Viewport:182, 221, 257` (holds a `Placement`, subscribes
  `EDITS` with `COALESCE_LATEST` at capacity one, which `@Subscribe` can express). It breaks on removal; the
  call is to leave it broken or port it. No ruling on that yet.
- **No hand-written use exists in the template, `mainframe`, `vexplore` or either witness.**

*Docs that describe the imperative path as supported:* `Shell.place`'s and `Placement`'s own Javadoc,
`Component` (18 to 20 and 52 to 55), `Subscribe` (13), `Channels` (29), `Generator` (542), and threading.md
T1.3 and §5.3.

*Progress, 2026-10-01:* **(a) done.** The residual entry point is `Placements` in `-shell` (`of(shell, lane)`
and `mailbox(placement, topic, subscriber, capacity, policy)`), public because the generated wiring lives in the
application's package, and said in its Javadoc to be generated code's. The generator emits it, the processor
test's fakes and the two witness assertions follow, and `Shell.place` is now package-private, which is ruling 2.
Unit tests pass (278); the acceptance run is the check that a generated project compiles against it.
**(b) and (c) done.** `superseded()` is deleted, and a component constructor taking a `Placement` is now a compile
error (`Graph.componentParameter`), the generator no longer resolves one, and the processor test's `Worker` declares
a `@Subscribe` instead. **Still to do:** (e) to (g) below. (d) is done: `Placement`'s constructor, `start` and both `subscribe` overloads are package-private, so only
`Shell`, `Placements` and the in-package tests reach them. `onWake` stays public because `WakeSource` requires it, and
`name()` and `close()` are public as before.

*Order, smallest first:* (a) move the generated call and the two witness assertions to the residual entry
point; (b) delete `superseded()` (**done**); (c) stop injecting `Placement` into components (**done**); (d) make `Placement`'s constructor, `start` and the `subscribe` overloads
non-public to an application (**done**); (e) the designer (**not ported, ruled 2026-10-01**: the up-to-date witnesses are MainFrame, Vexplore and the fluid simulator, and none of them uses `Placement`, `Shell.place` or `superseded()`); (f) move `PlacementTest` and `LivenessTest` (**nothing to move**: they are in the package and pass);
(g) rewrite the prose, then flip T1.3 to held in threading.md (**done**, held by
`PlacementTest.anApplicationCannotPlaceAComponentOrAddAMailboxByHand`). **Rulings 1 and 2 are complete.**

### 3. `lane`

Ruled already: an optional string, defaulting to `Component.DEFAULT_LANE`, whose grammar
(`[A-Za-z][A-Za-z0-9._-]*`) excludes the default by construction. **Accepted as recommended 2026-10-01: confirm and freeze, and
the grammar is contract**, because a lane's name is also its thread's name and what a stall report calls
it. The main thread stays a separate colour for the reason architecture.md gives. Changes nothing.

### 4. Group = lane

T3.6 says a subtree is the default thread grouping, so that *which grouping is late* is answerable. With no
tree (ruling 8) there is nothing else for a group to be. **Accepted as recommended 2026-10-01: for v1 a group is a lane, and
supervision, `Overrun` and reports are per lane.** A separate group concept, if a tree later wants one, is an
optional attribute with a default, which is additive. Changes nothing today; it stops T3.6 being read as a
promise of a second axis.

### 5. The `@Subscribe` defaults

`overflow = FAIL`, `capacity = 64`, registered at construction and started with the lane. FAIL is T4.2, held,
and the stack's own precedent. **Accepted as recommended 2026-10-01: freeze all three, and write capacity into the contract.**
Capacity looks arbitrary, but a full FAIL mailbox resolves through `Fatal` (T6.2's note says exactly that),
which ends the process, so changing 64 later changes whether an application dies under a burst, with no
compile error.

**Checked, then ruled 2026-10-01: a full FAIL mailbox halts the process, and that is the contract.** Checked:
`PumpedReg.doDeliver` (atchung) runs the process-wide `Fatal` policy when a FAIL mailbox is full, and `Faults`
installs `Fatal.HALT` (exit 70, no shutdown hooks, no save), from the publisher's thread, before the 10 s
watchdog threshold could matter. On the shared default lane it is reachable from a *neighbour*: a slow component
holds the lane and a fast one's mailbox on it overflows. Ruled: **it should fail and it should halt**, because a
single missed message is potential data corruption and must never happen silently. A developer who knows a channel can lose a message
says so per mailbox, with `overflow = DROP_OLDEST`, `DROP_NEWEST` or `COALESCE_LATEST`. So the sentence in
TODO.md, *"a component's failure never stops the others"*, is corrected: **a component that
cannot keep up with an edge channel ends the application.** What stays true is that a wedged component never
freezes the window, since this is a halt and not a hang. The default lane's cost is stated as exactly this.

**Scope, decided 2026-10-01: nothing more is built for this.** Two things are wanted and both exist. *It crashes
when a mailbox overflows*: a full FAIL mailbox halts the process (above). *Depth can be increased*:
`@Subscribe(capacity = ...)` sets it per mailbox, and the generator passes it through. A mailbox dump and a
per-mailbox overflow handler were designed and **dropped as more engineering than this is worth**; the dump is
kept as a post-v1 TODO entry because it is additive and its format would not be frozen, and the handler is not
kept, since a developer who can tolerate loss already has `DROP_OLDEST`, `DROP_NEWEST` and `COALESCE_LATEST` on
the same attribute. The advice that survives is the cheap half: a sample belongs on `COALESCE_LATEST` (T4.1),
and a bound that is simply too small should be raised.

**0.2, revisited 2026-10-26.** The ruling that a lost edge must never be silent stands, and the message model
makes it stricter. An edge topic delivers every message (threading.md T4.1, ruling 1 of 0.2), so `DROP_OLDEST` and
`DROP_NEWEST` are no longer offered: a channel that can lose a message is a **state** topic, which keeps the last
write per key and cannot overflow. `BLOCK` goes too, because under the tick no publish waits (T4.4, withdrawn). An
edge mailbox that overflows still halts, naming the topic (T4.2). Two parts of this ruling are reopened, for phase 2
to settle: the default capacity, because 64 was sized for a mailbox drained continuously and a tick-drained one
holds everything that arrived since the last tick; and whether capacity stays a per-subscription attribute, because
the template already needed 4096 to survive closing every tab, and immutable lists in payloads (ruling 6 of 0.2) are
expected to remove that burst.

### 6. Supervision

Ruled: report only, and a policy the application can override. The three open points have answers in code
already. **Accepted as recommended 2026-10-01: record them.** The threshold lives in `LivenessPolicy` (10 s by default, the policy's
to override). Lanes an application names are supervised the same way as the default lane, because the
mechanism is per lane. A report is what `Context` is handed (`exit`, `interrupt`, later `restart`) and never an
enum return. What is *not* done and is not a ruling: the main thread's heartbeat, and surfacing `Overrun` per
lane. Both are build items.

### 7. The landing rule for processor checks

[v1.md](v1.md#what-counts-as-breaking) already says a new compile error is breaking, and may land after v1
only if it rejects something that was already a bug. **Accepted as recommended 2026-10-01: confirmed, and made operational
in two sentences.** *Before v1, every planned check lands enabled* (T2.4 and T2.5's, T4.4, T4.5), which is the
reason to build them first. *After v1, a check lands without an opt-in only if what it rejects is a race, a
deadlock or a dropped message at runtime; anything stricter than that is behind a processor option and listed
in the release notes.* Applies the existing rule; changes no code.

### 8. The tree

T3.4 (*components form a tree*) is still `open`, and T3.6 and T6.3 hang off it. Its argument was that
supervision needs a structure to live in. Lanes turned out to be that structure: the watchdog supervises a
lane and names it, and nothing in the built model needed a parent.

**Accepted as recommended 2026-10-01: v1 ships a flat set of components under an implicit root, and the tree is additive.** The
extension test passes if the tree arrives as an optional attribute (`parent`, defaulting to the root) with
two consequences that apply only where it is used: a child shares its parent's lane unless it names one, and
shutdown is child before parent (T6.3). A flat application's behaviour under that default is today's, so
nothing that compiled changes. **Spiked 2026-10-02, and the count is zero** (TODO, *Spike the extension test*): a throwaway `parent` changed no
existing signature and none of the existing processor tests, so the tree stays additive. With the recommendation accepted, threading.md's own note applies: re-argue
§3.4 rather than keep it, and move T3.4, T3.6 and T6.3 to *after v1*. **Not done yet:** threading.md still lists them as before.

### 9. Is a `@Subscribe` off a component a thing?

The TODO lists *"a `@Subscribe` that is not on a component (an extension or a macro, which is the whole point
of the entry)"* as unbuilt. It has an unanswered question under it: a mailbox is drained on a lane, and an
extension declares none. **Accepted as recommended 2026-10-01: it is not a thing.** An extension is a `@Component`, with a lane like
any other, and the extension API is `@Subscribe` on it. That keeps *the container is the only source of a
reference* (T3.7) true for extensions as well, and it removes an item from the build list rather than adding
one. Reactive *automation* (a user script that reacts rather than drives) is a different shape, because that
script is not compiled into the application: it is the wire protocol growing an event stream, which is
additive. Say so, so the two are not built as one seam.

**0.2, revisited 2026-10-26: still not a thing, and now it matters more.** The audit found the cost of the other
half of this ruling: in 0.1, an application's `Ui`, `Actions` and `Model` were provided objects, could not subscribe,
and so took callbacks instead. 0.2 does not give a provided object a mailbox. It makes those parts components, which
under one logic lane (T1.6) costs no thread. A provided value is then what its name says, a value: shareable or a
framework service (T2.6), with no behaviour that reacts to anything.

### 10. Offload as a request with a reply-to (proposed 2026-10-18, ruled and built 2026-10-26)

**Ruled 2026-10-26: `@Job` is the offload API, and it is built.** The user's reason: the application's parts should
talk by message passing as far as it reaches, and slow work done as a lambda handed to an executor is the one place
the framework still asked for a closure instead of a message. The template's split into a `-core` controller and a
`-gui` view was the occasion: its disk work moved from `gui.offload()` lambdas to four jobs. What was built is
`dev.vexelray.framework.api.Job` (topic and reply), the processor's checks (below, all of them, from declarations),
`Jobs` in `-shell`, which the generated wiring calls to subscribe each job on the offload lane, and `JobsTest`. The
half that withdraws the raw executors from application parts is **not** done: see *What it does not settle*, and
TODO.md.

**And the same day: a configuration lists the components and jobs of its module.** Splitting the template found that a
`@Component` compiled in another module than the `@VexelApp` is a class file to the processor, which reads only the
round's sources, so nothing constructed it and nothing said so. `@Configuration(components = ..., jobs = ...)` lists
them, and the application names that configuration among its starters, for the reason `@VexelApp(starters)` gives:
listed, not discovered. The processor reads a listed component's `@Subscribe` and `@Publishes` off its class file, so
every channel check covers it as it covers the application's own (`VexelProcessorTest`,
`aComponentAndAJobFromAnotherModuleAreWiredThroughTheConfigurationThatListsThem`).

**Where it came from.** The capture rule (T2.5) was stuck between a heavy check and no check (build order, step 4),
because offload today is callbacks: `GuiApp.offload(work, landed, failed)` takes three lambdas, and `Lanes.offload()`
and `Gui.offload()` are `Executor`s taking a `Runnable`. A lambda can close over a component's mutable fields, and Java
has no type that refuses one that does. The user asked why this is not a reply-to queue. It can be, and then there
is nothing left to capture: the question goes away rather than being checked.

**The shape.** Slow work is a *declared job*, not a lambda, and its result is a *message*, not a callback.

```java
@Job
final class ReadFile {                                  // no mutable state: checked
    ReadFile(FileSystem files) { ... }                  // injected like any part; must be Shareable or a declared service
    Opened run(OpenFile request) { ... }                // runs on the job lane; its types name both channels
}
record OpenFile(Path path) {                            // request: deeply immutable, as any payload
    static final Topic<OpenFile> TOPIC = Topic.edge(OpenFile.class);
}
record Opened(Path path, String text) {                 // reply: deeply immutable, carries what the caller needs
    static final Topic<Opened> TOPIC = Topic.edge(Opened.class);
}

@Component
final class Documents {
    Documents(Sender<OpenFile> open) { ... }            // asks: a declared send, by its payload
    @Subscribe                                          // hears back, on the logic lane, in order
    void opened(Opened o) { ... }
}
```

- **The request is published** on the job's topic like any message, so it is a declared send and in the channel
  graph. The job runs on the bounded offload pool (`Lanes.offload()`), not on a lane: it has no state to confine
  and no order to keep, which is why a pool is right for it (the reasoning under *Nothing in the model covers work
  that outlasts a frame*).
- **The reply is the method's return value**, published on `reply`. Whoever subscribes receives it on *their own*
  lane, so the "`landed` always runs on the main thread, and a callback written on the handler lane races" gap
  closes too. The reply carries what the caller needs to correlate it (above, the path), so there are no request
  ids.
- **A main-thread consumer** hears back through the timeline door: the reply topic folded into a `Cell` by
  `KronBridge`, which is build step 6 and which `kronometer` now tests (`KronBridgeTest.topicDrivesACellInADrivenLoop`).

**What the processor checks, all of it declarations** (so *the processor reads declarations* stays true, and no
Trees API is needed):
- the request and reply types are deeply immutable (`Shareable`, the check `@Subscribe` payloads already get);
- the job class holds no mutable state: final fields only, each `Shareable` or a part the wiring provides;
- `topic` has one job, `reply` has at least one subscriber (or the job is for its effect; see below);
- the job's reply is a declared send, so the blocking-cycle check (T4.4) sees it, which it cannot do for a lambda
  today (TODO, *a send from `work` is invisible to T4.4*).

**What it settles.** T2.5 is held by construction on the framework's path: a job cannot capture, because it is not a
closure. The completion path (step 6) gets its API: the reply topic. Failure has one route, below, instead of a
`failed` callback.

**What it does not settle, and the proposal says so.**
- **The raw executors stay reachable.** `Gui.offload()` and `GuiApp.offload(...)` are `vexelray-gui`'s, used below
  the framework (widgets, MainFrame, which is hand-wired), and an application holding the `Gui` can still call them.
  The processor cannot see a call. So they become the documented, unchecked escape hatch, and the framework stops
  handing `Lanes.offload()` to application parts. Whether `Shell`'s `Gui` should hide `offload` is the *what `Shell`
  hands over* decision, per accessor.
- **Failure. Ruled 2026-10-18: every failure is either declared and handled, or it ends the application.** The
  user's words, and the reason is ruling 5's: a failure nobody handles is a lost result, and a lost result that the
  application runs on past is the silent corruption a full mailbox already halts for. So there is no framework
  failure type and no `failed` topic. A failure a job expects is part of its reply, a type the application declares
  (`record Opened(Path path, String text, Problem problem)`), so the compiler makes the subscriber look at it, and
  `run` declares no `throws` (checked by the processor), so javac makes the job catch what it expects. Anything else
  that escapes `run`, an `Error` included, goes to the process-wide fault policy (`Atchung.fatal()`), which halts.
  **Already applied to components (2026-10-18):** a `@Subscribe` that throws halts the same way rather than being
  logged and drained past, except while its lane is stopping, when the throw is the framework's interrupt. The
  `GuiApp.offload` callbacks are `vexelray-gui`'s escape hatch and keep their `failed` route.
- **Cancellation** (a newer request makes an older one pointless) is the caller's: it drops a stale reply while it
  drains, as `superseded()`'s deletion already ruled. A cancellation token is additive.
- **A job for its effect** (write a file, reply nothing) is a `void run`, and wants the same answer as *a recipe
  run for its effect*.

**Why now and not after v1.** Under ruling 7 a check that rejects code v1 accepted is breaking, and applications
written against v1 would write offload as lambdas into `Lanes.offload()`. Moving them later is the refactor v1 rules
out. Adding `@Job` alone is additive; withdrawing the executors from application parts is not, so that half is the
part to rule on before the freeze.

**Ruled (above):** `@Job` and the reply topic are the v1 offload API. Failure's shape is ruled (above). Still to do
before the freeze: withdraw the executors from application parts, which is the breaking half.

### 11. Sources: what keeps producing from outside (ruled and built 2026-10-30)

**Ruled by the author 2026-10-30: sources are a kind of part in the framework.** A source keeps producing messages
from outside the application for as long as it runs: a socket, a stream, a child process, a device. **Why:** it is
the right shape, and netcode and much else will need it, so it belongs in the framework rather than worked around in
each application. The occasion was the Inspector (ROADMAP phase 7½), which reads a running application's rows over a
link for as long as it is attached, a recording at its recorded pace, and a launched child's output. Its 0.1 form made
three hand-made threads for them (its FN-1). *Rejected:* a loop of short jobs, each waiting a bounded time for what
arrived and asked again at once. That needs nothing new, but every client of a stream would write the same loop, and a
connection's life would be a chain of requests rather than one thing the wiring can name.

**The shape: a job that sends while it runs, on a thread of its own.** Everything a job is, a source is, with two
differences: `run` may send as it goes, and it lasts as long as what it reads from.

```java
@Source
final class LinkRows {                                    // final fields only, as a job's
    private final Sender<RowBatch> rows;
    LinkRows(Sender<RowBatch> rows) { this.rows = rows; } // declared sends, by construction (T4.5)

    Detached run(Attach request) {                        // one run per request, on vexel-source-link-rows
        try (LinkClient link = LinkClient.open(request.discovery())) {
            for (RowBatch b; (b = link.next()) != null; ) {
                rows.send(b);                             // as it goes
            }
            return new Detached(request.run(), Detached.Why.WENT_AWAY);
        } catch (ClosedByInterruptException stopped) {
            return new Detached(request.run(), Detached.Why.STOPPED);
        } catch (IOException e) {
            return new Detached(request.run(), Detached.Why.failed(e));
        }
    }
}
```

- **A run starts on a request**, published on the request's topic like any message, so it is a declared send and in
  the channel graph. **It ends by returning**, and the return value is published as a job's reply is: why it ended,
  stopped, went away or failed, carrying what the caller needs to tell which run it was. A failure it expects is part
  of that reply; anything else that escapes `run` halts, as a job's does (section 10).
- **Per-run state is local to `run`.** The source's fields are final, as a job's are (only senders, `Shareable`
  values and declared services), so nothing is shared between runs or with anything else, and there is nothing to
  check beyond what a job already gets.
- **A thread of its own per run**, a platform thread named `vexel-source-<name>` (T1.2, T1.4), started when the run
  starts and gone when it returns. Not the job lane: a run that blocks for an hour would hold one of the job lane's
  bounded threads for good (T1.5), which is exactly what the job lane is bounded to refuse.
- **One run per key of the request, as many keys as there are.** The request's topic decides: on a keyed topic
  (`Topic.edge(Attach.class, a -> a.target())`) each key has a run of its own, side by side, one connection per peer;
  an unkeyed topic is one key, so one run. A request for a key whose run is going stops that run, and the new one
  starts once it has returned, so a key's runs never overlap and its replies keep their order. That is attaching
  again and reconnecting. Every request gets one reply. *Ruled 2026-10-30, replacing "one run at a time per source":*
  the message system is transport-agnostic, so many live connections are the expected case, not a later one.
- **A stop is a message of the application's own**, named on the annotation (`@Source(stop = Detach.class)`) and
  keyed as the request is, so that it is a channel like any other: a type with a declared topic, which the graph can
  show. It stops the run for its key without starting another. (Proposed first as a `Stop` topic the framework
  declared per source; a topic is named by its payload's type, and one framework type cannot name a channel per
  source.)
- **Stopping interrupts the run's thread**, then closes what the run handed its `Closer`. A read on an
  `InterruptibleChannel` (a socket or file channel) answers the interrupt with `ClosedByInterruptException`; one that
  ignores it, a process's output, is ended by the close. `run` takes the `Closer` as an optional second parameter,
  since the resource belongs to the run and a source's fields are final. At shutdown every run is stopped and waited
  for, a bounded time, before the logic's last tick, so their end replies are delivered in it and nothing is sent into
  a component that is closing (T6.2).
- **What it sends arrives from outside**: hops zero, ordered by arrival, as input is (T5.6, T5.7). One run's messages
  keep their order, since one thread sends them. A source is therefore one of the things a replay must record (ROADMAP
  §5, item 5), with input.
- **Nothing changes between modes** (T1.7). A run's thread is outside the tick in both.
- **The watchdog does not watch a source.** A run blocked in a read is resting, not wedged; it holds no lane.
- **Listed like a job**: `@Configuration(sources = ...)` in the module that compiles it, for the reason section 10
  gives for jobs.

**What the processor checks**, all from declarations, as for a job: the request, the reply and every payload a sender
carries are `Shareable`; the class has final fields only, each `Shareable`, a sender or a provided part; no
`@Subscribe`, no `Inbox` (a source hears nothing but its request, which the framework delivers); `run` declares no
`throws`, takes nothing after its request but a `Closer`, and returns a reply; the stop message has a declared topic;
one part, job or source, per request topic; and nothing holds a source.

**Where it is built:** `@Source` and `Closer` in `-api`; `Sources` in `-shell` (the runs, by key); the processor's
`Declarations.source`, `Graph.source` and `Channels.stopType`; atchung's `Sender.outside` and `Topic.keyFor`. Held by
`SourcesTest` and `VexelProcessorTest`'s source cases.

**The open questions, as ruled by the author 2026-10-30:**

1. **Backpressure: nothing in the framework.** *Ruled:* it is not a problem to add code for in advance. A source that
   can outrun the logic buffers and batches what it sends (the Inspector's link reader sends rows by the batch), and
   one that overflows a mailbox all the same fails as any publisher does (T4.2), since nothing better can be done. A
   publish that waited for room was built in atchung and taken out again the same day.
2. **A read that ignores interrupts: a `Closer`, built.** A child process's output is an `InputStream`, which an
   interrupt does not unblock. The proposed `close()` on the source had nothing to close, since a source's fields are
   final and the resource belongs to one run; so the run hands it over, `run(Launch request, Closer closer)`, and
   stopping closes it from the stopping thread after the interrupt. *Ruled:* the second parameter.
3. **Several runs of one source: built, by key.** *Ruled:* the message system is transport-agnostic, so a source
   holds as many live connections as there are keys; see the run-per-key rule above.
4. **A cause of its own: built.** Each message a run sends begins a cause naming the source (atchung's
   `Sender.outside`), so a trace finds where it came from and the Inspector draws it as a source, not `uncaused`.
5. **The framework's own sources.** `Timer`, the automation sockets and tactroller's message pump are sources in
   all but name. Whether they become sources is decided after the first application one is built, not before.
   *Since 2026-11-12, section 14:* a source is an optimised case of a daemon, and `Timer`, which waits on nothing
   outside, is a daemon in all but name rather than a source.

### 12. Destinations: what writes outside, and a source that is one too (ruled 2026-11-01)

**Found porting MainFrame:** a terminal goes both ways. Its shell's output is a source's run, and the pseudo-terminal
it reads is local to that run, so the keys typed into the tab and the size it is laid out at had no way to reach the
pseudo-terminal: a source hears nothing but its request. A socket, a serial port and a child process's pipes are the
same: one connection, read and written.

**Ruled by the author 2026-11-01: a `@Destination` is a kind of part, the mirror of a source, and a part may be both.**
A destination takes messages and writes them outside the application: a pseudo-terminal's input, a socket's sending
half, a log file, a recording. Any application may declare one, as it declares a job or a source, listed with
`@Configuration(destinations = ...)` when another module compiles it. *Rejected:* a service of the application's own
holding the open connections by key, which a source fills and a job writes through. It needs no framework, and it is
state shared between threads in application code, which is what 0.2 removes.

**The shape: the framework holds the connection, and hands it to each half.**

```java
@Source(stop = CloseShell.class) @Destination
public final class Shell {
    private final Sender<Output> output;
    public Shell(Sender<Output> output) { this.output = output; }

    public Pty open(OpenShell request) { ... }               // the connection, made on the key's writer thread
    public ShellEnded run(OpenShell request, Pty pty) { ... } // the source half: reads, and sends as it goes
    public void write(Pty pty, Typed typed) { ... }          // the destination half, one method per message heard
    public void write(Pty pty, Resized resized) { ... }
    public void close(Pty pty) { ... }                       // once, after both halves are done with it
}
```

- **The connection is a value the framework keeps per key**, never a field, so a destination's fields are final as a
  job's are and nothing new is checked. It never crosses a lane: it is made, used and closed by the part's own
  methods, so it need not be shareable.
- **`open` makes it.** With the request as its one parameter, one connection per key of the request's topic, as a
  source has one run per key; a request for a key already open stops that one first, and the new one opens once it is
  closed. With no parameter, one connection, opened when the application starts (after `ATTACH`, as a part's
  constructor runs before it) and closed at shutdown (after the logic's last tick, as an `AutoCloseable` part is).
  Opening may block, so it runs on the key's writer thread.
- **`write` is the destination half**: one method per message type it hears, the connection first. Each heard type
  has a declared topic, and on a keyed connection a message goes to the connection its topic's key names; on an
  unkeyed one, to the one there is. Every message, in the order sent, on the key's writer thread, which is a thread
  of its own (`vexel-destination-<name>`) because a write may block: a pipe whose program reads nothing fills, and
  waits. Messages for a key whose connection is still opening wait for it. One for a key with no connection is
  dropped and counted, as a widget message for an address nobody answers to is. **T5.8 gains a third place that
  may block**, a destination's write, beside a job and a source's read.
- **`run` is the source half**, present when the part is also a `@Source`: it takes the request and the connection,
  sends as it goes, and returns why it ended, published as a source's reply is. A run that returns stops the key, so
  its connection is closed and its writes end.
- **`close` ends it**, once per connection, after the run is interrupted (or has returned) and the writer has
  stopped, on whichever thread stopped it. A stop message (`@Source(stop = ...)`, or `@Destination(stop = ...)` for a
  destination that is not a source) stops the key. Closing is what ends a read that ignores an interrupt, so a part
  with a connection needs no `Closer`.
- **Backpressure: nothing in the framework**, as for a source (section 11, question 1). A destination's writes are
  heard inline and queued for its writer; one that cannot keep up grows its queue.
- **Failure:** as a job's. What escapes `open`, `write`, `run` or `close` goes to the process-wide fault policy, which
  halts, except once the part is stopping for shutdown. An expected failure is the run's reply, or for a destination
  with no run, nothing: a write that cannot land is the connection's to report through what it reads.

**What the processor checks**, from declarations, as for a job or a source: a final class, one constructor, final
fields (senders only if it is also a source); exactly one `open`, taking nothing or one request, returning the
connection; at least one `write`, each `void`, taking the connection and one message whose type is shareable and has a
declared topic; exactly one `close`, `void`, taking the connection; with `@Source`, a `run` taking the request and the
connection and returning a reply (no `Closer`), and `open` taking that request; no `throws` anywhere; no `@Subscribe`
and no `Inbox`; nothing holds one. The messages a destination writes count as heard, so a sender of them is not
talking to nobody.

**Where it is built:** `@Destination` and `@Configuration(destinations = ...)` in `-api`; `Destinations` in `-shell`
(the connections, by key, with their writers and runs); the processor's `Declarations.destination`, `Graph`'s
destinations, `Channels.destination` and the generator's call. Held by `DestinationsTest` and `VexelProcessorTest`'s
destination cases.

### 13. Every provider of a type, or at most one (ruled and built 2026-11-03)

**A recipe may take `All<T>`, every provider of `T`, or `Optional<T>`, zero or one.** It is how a part takes pieces it
does not name: a window's owner declares what it holds as a type of its own, takes `All` of it, and lays the pieces
out, and leaving a piece out of the build leaves one fewer. The modular template needs it so that a module adds a
piece of a window without the base naming the module; the argument and the four rulings are in
[plans/regions.md](plans/regions.md).

**The rules, held by the processor:** the providers of a type taken as `All<T>` are exempt from *exactly one provider
wins*, so a type taken that way anywhere is taken that way everywhere (as a plain `T` or an `Optional<T>` it is
refused); a `@Default` provider of it is refused; only a recipe takes either shape, and a raw one is refused; the
pieces are held to what a parameter of `T` would be (no main-thread value, no component). `Optional<T>` with two
providers is the conflict it always was. The order of `All<T>` is the providers' qualified names, configuration and
then method, stable and meaning nothing; a mode that does not build a piece leaves it out, and `Optional<T>` is empty
there. Both exist in every mode, so neither narrows the modes of the part that takes it.

**Where it is built:** `All` in `-api`; `Graph`'s `gatheredUse` and the exemption in `conflicts`; the generator's
`AllArg` and `OptionalArg`, with a field of its own for each piece. Held by `VexelProcessorTest`'s *All<T> and
Optional<T>* cases.

### 14. Daemons: a component off the tick, talking with the application both ways for as long as it runs (ruled 2026-11-12, not built)

**Found designing vexelray-gui's `Progress` widget:** nothing in the framework runs a long computation that reports
as it goes. A `@Job` answers once and may not take a sender (`VexelProcessorTest.aJobAnswersWithItsReplyAndTakesNoSender`).
A `@Source` may send, but it is for what reads from outside: its run is a thread per request, which oversubscribes the
cores once several keys render at once, and what it sends counts as input. A declared lane is part of the tick, so a
thirty-second delivery holds every lane at the round's end and is reported as wedged (T1.6, T6.5). This is the open
half of [template-examples.md](plans/template-examples.md)'s A6, *a tiled render with progress*.

**Ruled by the author 2026-11-12, as the direction: long work gets a dedicated lane, and that lane is a long-lived
thread that waits for messages from the application, not a job that reports progress.** **Why:** in the author's words,
it is the most future-proof and re-usable of the shapes considered: one worker serves every request for as long as
the application runs, keeps what it learnt between them, and is stopped by a message like anything else, where a
job-with-progress answers only the case it was made for. *Rejected:*
- **A job that may send, on the job lane.** The job lane is `max(4, cores)` threads and also carries file opens and
  network waits, so a render that fills it makes the next open wait behind it, the stall the lane exists to absorb
  (`Lanes.JOB_THREADS`). And letting `@Job` take a sender loses the property that a job's one reply is its whole
  contract.
- **A third kind of run** (a source's run-per-key rules on a pool sized to the cores). It answers the progress bar and
  nothing else, needs a cancellation token or an interrupt protocol of its own, and keeps no state between runs.
- **`@Source` used for computation.** As above: a thread per run, and its sends counted as input.

**What a daemon is, ruled by the author 2026-11-12: a thread, and an ongoing two-way channel between it and the
application. Not a place for slow work, and not defined by keeping state.** It need not take minutes. **Why:** the
channel is the part no earlier kind of part gives, and the progress bar was only the case that found it. State is
left out of the definition because Java cannot guarantee purity at any level: a job's *no mutable fields* is a check
on fields, and a static or a provided service can still hold state, so "keeps state" or "keeps none" is not a line
the framework can draw. What it can promise is the thread and the channel.

| Kind | Hears | Sends | Runs |
| --- | --- | --- | --- |
| `@Job` | one request | one reply, at the end | on the job lane, then gone |
| `@Source`, `@Destination` | its request and stop; writes for a connection | as it goes | a thread per key, talking to the *outside* |
| `@Component` | everything it subscribes to | at the round's end | inside the tick: quick, replayed |
| `@Daemon` | everything it subscribes to, and asks mid-delivery whether it is superseded | as it goes | a thread of its own, for the application's life |

A component cannot send or hear while it works: its sends wait for the round's end. A job answers once. A daemon
talks with the application both ways, while it works and between, and may talk to the outside as well. A source and a
destination are daemons specialised for the commonest outside case, a connection per key (question 5). Slow work is
one use of a daemon; a cache the application asks and fills, a model
that a view queries while it is being edited, and a coordinator handing work to jobs are others.

What one thread still buys is confinement: whatever mutable fields a daemon has are touched by its thread alone,
which is the component rule and the reason for question 2's ruling, not part of the definition.

**The shape, a sketch.** A component in every respect but where it runs: one class, private mutable state confined to
its one thread, `@Subscribe` methods, senders as constructor parameters. What differs is that its lane is outside the
tick, so a delivery may take as long as its work does, and what it sends leaves as it goes.

```java
@Daemon                                                    // the name is ruled; see question 4
public final class Renderer {
    private final Sender<Rendered> rendered;               // a state topic: the view sees the newest
    private final TileCache cache = new TileCache();       // kept between requests, confined to this thread

    public Renderer(Sender<Rendered> rendered) { this.rendered = rendered; }

    @Subscribe(stop = StopRender.class)                    // Render is a state topic, keyed by document
    void render(Render request, Superseded superseded) {
        for (Tile tile : request.tiles()) {
            if (superseded.now()) {
                return;                                    // a newer Render or a StopRender for this document waits
            }
            rendered.send(new Rendered(request.id(), tile.index(), request.tiles().size(), cache.draw(tile)));
        }
    }
}
```

- **A thread of its own, for the life of the application** (question 2, ruled): a platform thread named
  `vexel-daemon-<name>` (T1.2, T1.4), started by the generated wiring after its constructor runs, with the daemon flag
  so it never holds the JVM open. It drains its own mailbox, one delivery at a time, in order, and parks when the
  mailbox is empty. Daemons are fixed at compile time: the processor sees every one, nothing creates one at run time,
  and work that varies (three documents to render) is messages in one daemon's mailbox, not more threads.
- **Its mailbox is atchung's `Pump`, not a tick `Inbox`.** An `Inbox` is touched by the coordinator between rounds
  and by its lane during one, never by a thread outside the tick. A `Pump` is made for one owner thread and
  publishers on any: `drain(timeout)` parks the daemon while nothing waits, `wake()` ends the park at shutdown, and a
  `Fold` lets a newer state message replace the one queued for its key. A daemon's pump is an *ordered* one
  (question 3, ruled), so it hears its messages in the order they arrived, across topics, as a component does (T5.6).
- **Cancelling is a message, by the rule a source's run is stopped by** (question 1, ruled). There is no interrupt
  and no token. A delivery is superseded when, for the same key, a newer message waits on its own topic and that
  topic is a state topic, or the stop message its `@Subscribe(stop = ...)` names waits. A running delivery notices at
  the next point it asks `superseded.now()` and returns; the newer message is then delivered as any other.
- **Shutdown is ordered even though the thread is a daemon.** At shutdown the daemon is asked to stop: the delivery
  in progress sees `superseded.now()` answer true, nothing further is delivered, and it is waited for a bounded time
  before the logic's last tick, then interrupted. As for a source (T6.2), so its last sends land in a tick, and a
  daemon cut off mid-write is the exception rather than the rule.
- **The watchdog does not watch it.** A delivery may last as long as its work does, so there is no threshold to hold
  it to. The cost is that a daemon truly stuck looks like a slow one; the only sign is that what it sends stops.
- **What it sends arrives from outside**, as a source's does (section 11): hops zero, a cause of its own naming the
  daemon, recorded for a replay, which does not run daemons and is told what each sent, and when. Its state is
  therefore not replayed, and need not be.
- **T5.8 gains a fourth place that may block**, beside a job, a source's read and a destination's write. T1.6's *a
  declared lane is not a way to block* stands: a daemon is not a declared lane.

**What the processor would check:** everything a component gets (state confined to the class, shareable payloads,
declared sends, nothing holds it, no main-thread value, no tree); a `Superseded` parameter only after a `@Subscribe`
method's message, and only in a daemon; a `stop` that names a type with a declared topic, keyed as the message it
stops, which the daemon then hears; and not `@Source` or `@Destination` on the same class (question 5).

**Question 1, the mid-run check. Accepted as recommended 2026-11-12:** a delivery asks *has this been superseded?*,
not *is anything waiting?*, and is superseded by a newer value of its own state topic for its key, or by its declared
stop for its key. **Why:** *is anything waiting* lets an unrelated message (a setting changed) abort a long run, and
lets a request for document B abort document A's, which is then never finished. A newer value of a state topic is by
definition what makes the old one stale (T4.1), where each message on an edge topic (*export this file*) is work of
its own, so an edge waits behind the run and only the stop cancels it. It is also the rule a source's run is stopped
by (section 11), so a reader learns one rule for both, and the stop is declared, so the processor checks it and the
graph shows it. The parameter is optional: a method without it runs to the end. *Rejected:* `Pump.hasPending()`
as it stands, for the reasons above, though it is what a first build could prove the thread with.

**What it costs:** a question per key on a pumped subscription, *is a message queued for this key?*, additive in
atchung, so its frozen behaviour is untouched (checked against tactroller's suite). One or two lock acquisitions per
ask and no allocation, written with indexed loops (the `Pump`'s `regs` is a `CopyOnWriteArrayList`, whose iterator
allocates); between tiles that is nothing. A volatile flag the publisher sets would make it lock-free, and waits
until something measures the need.

**Question 3, the mailbox's order and capacity. Ruled by the author 2026-11-12:** a daemon's pump stamps each message
with an arrival number as it is queued and delivers in stamp order across its mailboxes. Edge capacity stays the
component default, 64 per `@Subscribe`, and an overflow names the fix. **Why:** a plain `Pump` drains mailbox by
mailbox, so the order between topics is the order they were subscribed in: a `SetPalette` sent before a `Render` can
be delivered after it, and the picture is drawn with the old palette. A daemon is a component off the tick, so it
keeps T5.6. A stamp keeps the order where it is lost, at arrival, and in the author's words it is also something
convenient to log: predictable and auditable behaviour is a win. *Rejected:* a hook that reorders before the drain,
since the order across mailboxes was never recorded and a hook has nothing to sort by, and a hook taking any
comparator would make delivery order an application's choice, which T5.6 exists to forbid; and a new mailbox class
like the tick `Inbox`, which would rewrite the `Pump`'s park and wake, `Fold`, probe rows and overflow faults.

- **An ordered mode, not a change to `Pump`** (`Pump.ordered()`, say): the default pump keeps delivering mailbox by
  mailbox, so atchung's frozen behaviour is untouched (checked against tactroller's suite).
- **The stamp:** one counter per pump, one atomic increment per message queued, one field per queued message. A state
  value that replaces the queued one for its key takes the newer stamp, which is the `Inbox`'s *relinked at the
  back*. The stamp is written in the named mailbox's `enqueue` and `deliver` rows, so a log says which arrived first.
- **The drain:** the counter is read first, each mailbox's batch is taken under its own lock, and only messages stamped
  at or below what was read are delivered, merged by stamp with indices, allocating nothing. Without the cut-off, a
  message queued in one mailbox just after its batch was taken could be overtaken by a later one in a mailbox taken
  after it. This is the line a test has to pin down.
- **Capacity:** a state topic cannot overflow (one message per key), which covers the usual request. An edge topic is
  allowed, since a daemon working through a list of exports is a safe use, and keeps the default of 64 and T4.2's
  halt. For a daemon the fault says why and what to change: *a daemon reads its mailbox only between deliveries; make
  the topic a state topic, or raise `@Subscribe(capacity = ...)`.* *Rejected:* a larger default for daemons, which
  only delays the same failure and makes a default that differs between a daemon and a component.

**Question 2, one thread or a few. Accepted as recommended 2026-11-12, and confirmed by the author as the shape
wanted:** one thread per daemon, declared and fixed at compile time, started with the application and parked when
idle, never shared with another daemon. A daemon that wants every core gets them by sending work to jobs on a named
job lane. **Why:** a daemon's state is confined to its one thread, which is what makes it a component and what lets
application code hold no locks; a pool of its own would give that up. A parallel-for inside a delivery is a lambda
that can capture the daemon's state, the T2.5 problem `@Job` exists to remove (section 10). Two daemons sharing a
thread would let one's minute-long delivery hold up the other, so the daemon is its lane. Fixed at compile time is the
stack's way: what runs is known before it runs. In the author's words, it is the most generally useful version of the
idea it can be, without compromising the principles.

- **Parallelism is the daemon coordinating and jobs computing**, in waves:

  ```java
  @Subscribe(stop = StopRender.class)
  void render(Render request) {                     // short: hands out the first wave and returns
      current = request;
      for (int i = 0; i < WAVE && next < request.tiles().size(); i++) {
          tiles.send(new DrawTile(request.id(), request.tiles().get(next++)));
      }
  }

  @Subscribe
  void drawn(TileDrawn tile) {                      // a job's reply, on the daemon's own thread
      if (tile.render() != current.id()) return;    // stale: the caller drops it (section 10)
      cache.put(tile);
      rendered.send(progress());
      if (next < current.tiles().size()) tiles.send(new DrawTile(current.id(), current.tiles().get(next++)));
  }
  ```

  The cache stays confined to the daemon; the tile function is a stateless `@Job`. A new request leaves at most one
  wave running, and the rest are never sent. Long deliveries and `Superseded` stay for work that is single-threaded
  by nature.
- **It needs named job lanes**, `@Job(lane = "render")`: a bounded pool of its own, sized to the cores by default, so
  tiles do not make a file open wait behind a render on the shared job lane (`Lanes.JOB_THREADS`). Additive: a job
  that names no lane keeps the shared one. Its bound is fixed at compile time; whether its threads are kept alive as a
  daemon's is, or made on demand and let go after idling as the shared lane's are (`allowCoreThreadTimeOut`), is not
  yet ruled.
- **Where to doubt it:** waves and dropping stale replies by id are more application code than a parallel-for. If the
  A6 example shows it is painful, a declared parallel-for (a stateless tile class the daemon calls) can be added later
  without breaking an application. Each tile crosses the bus twice, which is noise for tiles worth a millisecond and
  not for thousands of tiny items, whose answer is bigger items.

**The questions, all ruled 2026-11-12:**

1. *Ruled, above.*
2. *Ruled, above.*
3. *Ruled, above.*
4. **The name. Ruled by the author 2026-11-12: `@Daemon`, an annotation of its own**, as recommended, for these
   reasons. The Unix sense is the two-way channel above: a
   process that lives as long as the system, waits, and is talked to, however long each answer takes. Java's daemon
   flag is no clash, since every framework lane is already a daemon thread (`Lanes.named`), so the flag singles
   nothing out. Javadoc's first line: *a component with a thread of its own, outside the tick, that the application
   talks with both ways for as long as it runs.* *Against:* `@Component(lane = ..., tick = false)`, a flag that would
   change the watchdog, replay, the mailbox and which parameters are legal, with `lane` meaning nothing since a daemon
   never shares its thread (each sibling kind is an annotation of its own: `@Job`, `@Source`, `@Destination`);
   `@Worker`, which was 0.1's handler lane; `@Service`, a stateless bean in a Spring-shaped framework and already
   section 11's word for a provided part; `@Actor`, which every component already is, and ROADMAP.md says this is not
   an actor system with a thread per component; `@Background`, which says where and not what. *Where to doubt it:* a
   Java reader may first think *dies with the JVM*; the Javadoc's first line and the thread name
   (`vexel-daemon-renderer`) answer that.
5. **Its relation to sources. Ruled by the author 2026-11-12: a daemon is the general mechanism, and `@Source` and
   `@Destination` are optimised, common cases of it, which stay.** Nothing stops a daemon talking to the outside
   world. **Why keep them:** in the author's words, for the same reason in-process threads do not pass messages over
   UDP. The specialisation is what makes the common case right without effort: a thread per key side by side, a
   stop that interrupts a blocked read and closes what ignores the interrupt (`Closer`), and a connection the
   framework holds per key. A daemon has none of that and should not grow it.
   - **Choosing:** a source or destination when the shape is *a connection per key, blocked in a read or a write for
     as long as it is open*; a daemon for anything else, outside I/O included.
   - **A daemon that talks to the outside** asks `superseded.now()` only between its own steps, so a read that blocks
     without bound cannot be superseded. It bounds its reads (a timeout) or leaves the waiting to a source and hears
     the source's messages. At shutdown it is interrupted after the bounded wait, as any daemon is, which ends an
     interruptible read. Its sends are already recorded for a replay as arriving from outside, so nothing about
     replay changes.
   - **One class is one kind:** `@Daemon` with `@Source` or `@Destination` on the same class is refused, naming the
     two, because the thread model would be ambiguous (one thread, or one per key). A source and a destination may
     still be one class (section 12), since they share the per-key model.
   - **The framework's own threads:** `Timer` is a daemon in all but name: it hears schedule and cancel messages,
     sends when a deadline passes, and waits on nothing outside, only a timed park (`Pump.drain(timeout)` with the
     next deadline). The automation sockets and tactroller's pump are sources. Whether any of them becomes one is
     decided after the first application daemon (A6) is built, as section 11 question 5 says.

**Where it would be built:** the annotation, `Superseded` and `@Job(lane = ...)` in `-api`; named job lanes in
`Lanes`; `Daemons` in `-shell` (the threads, their
pumps, the ordered stop); the ordered pump and the per-key question on a pumped subscription in atchung; the processor's declaration and graph node. Held, when built, by a
`DaemonsTest` and the processor's daemon cases, and shown by A6's example in the template.

## Build order once the rulings are taken

Each step decides what the next bakes in, as [v1.md](v1.md#how-to-get-there) orders the whole.

1. **Remove the imperative path** (rulings 1 and 2), and make T1.3 held. A deletion, so it comes first.
2. **The cycle check (T4.4) is done, 2026-10-02**, on the data `Channels` already had: nodes are components, an edge
   is a declared send to a mailbox, and a cycle with a blocking edge is a compile error. **The graph is exposed as
   generated data (the rest of T4.5), 2026-10-19**, now that something consumes it: the generated wiring's
   `BUS_GRAPH`, in vexelray-inspector's bus-graph text (version 2 since 2026-11-02, `docs/bus-graph.md`), which the
   Inspector draws as its graph. The stated limit stands
   and is on `@Publishes`: only a *declared* send is in the graph.
3. **The copier** (T2.4). **The check half is done, 2026-10-02** (`Shareable`): a `@Subscribe` payload must be
   deeply immutable, and anything else is refused, naming the first thing that stops it. **The generated copier is
   parked as additive.** It would annotate a record and have the processor emit the copy, so collections and
   mutable classes could cross; its annotation's name is a Maven-level public name and is undecided.
4. **The capture rule** (T2.5), which is the only one that wants a method body. **Not decided.** This page first
   said to make capture impossible by construction, with an offload API whose function cannot close over
   anything. That is wrong: offload is an `Executor` and a `Callable`, and Java has no type that refuses a capturing
   lambda. What is left is a Trees-based check that resolves each captured name (accurate, and the heaviest
   piece in the processor) or a rule the reader keeps. See TODO, *Decide now. Two promises need a method body*.
   **Proposed 2026-10-18 instead: section 10**, which makes slow work a declared job with a reply topic, so there is
   no lambda to capture with and the check needs only declarations. **Ruled and built 2026-10-26** for the
   framework's path; the raw executors are still reachable from an application part, so T2.5 is held only where an
   application uses `@Job`.
5. **Supervision's missing halves:** the main-thread heartbeat that knows a dialog is up, and `Overrun` per
   lane.
6. **The completion path:** route offload completions into the timeline through `KronBridge`.

Steps 2 to 4 are where new compile errors come from; all of them land before v1 under ruling 7.

## What this page deliberately does not decide

The handler lane's bound (it waits on a census of what still blocks on a handler), `kronometer`'s own `offload`,
and a restart policy beyond relaunching the process. None of them changes what a component is.

# The component model

What a component is, what the framework owes it, what exists, and what is still to be decided. This is the
spec the declaration seam is built against. It does not repeat the other three documents it summarises:

- [architecture.md](architecture.md#the-concurrency-model) is the **reasoning** (why one thread, why static placement).
- [threading.md](threading.md) is the **rules**, each with what enforces it.
- [v1.md](v1.md#the-liveness-guarantee) is the **contract** v1 freezes, including the liveness guarantee.

Where this page and one of those disagree, that is a bug in one of them; say which in the commit.

**Status of this page: all nine rulings taken on 2026-10-01, each as recommended** (the recommendation is kept
under each, now marked *Ruled*). Rulings 1 and 2 are being built (see the inventory under them); ruling 5 carries
one check that must pass before the freeze.

## What a component is

A `@Component` is **a thread and a mailbox**: a class constructed once, before the loop runs, by its one
constructor, that runs on a *lane* and is reached by publishing, never by holding. A value that needs only a
lifetime and swappable behaviour is a `@Provides`; a value is neither. The main thread is not a lane and a
component is never `@MainThread`.

## What a component is owed

| The framework owes it | Held by | Rule |
| --- | --- | --- |
| A platform thread of its lane, named `vexel-component-<lane>`, never virtual | `LanesTest` | T1.2, T1.4 |
| One shared default thread when it names no lane, so a plain service costs no thread | `Component.DEFAULT_LANE` | ruled 2026-09-29 |
| A mailbox per `@Subscribe`, with a declared loss policy and bound | processor, `Placement` | T4.1, T4.3 |
| Construction before the loop; start only after everything is built | `PlacementTest` | T6.1 |
| A wake of the frame loop after any delivery, with nothing to call | `PlacementTest` | T5.3 |
| Drain-then-stop at shutdown, inside a total bound after which the process halts | `PlacementTest`, `Disposer.beforeClose` | T6.2, liveness |
| Its own `ThreadGroup`, so the watchdog can interrupt everything the lane started | `Lanes.interruptLane` | liveness |
| A stall report naming its lane, and a policy the application can override | `Watchdog`, `LivenessPolicy` | T6.5 |
| That no other lane holds a reference to it, and no provider does | processor | T2.3, T3.7 |
| That a value crossing into it shares nothing with the sender's heap | **not built** | T2.4, T2.5 |
| That no blocking cycle runs through it | **not built** | T4.4, T4.5 |

And what it owes the application: it never blocks the main thread, never touches the timeline (T3.2), and
returns work through one of the two doors (T5.1). A wedged component loses its own lane and nothing else.

## What exists, what is decided, what is not

| | Built | Decided, unbuilt | Undecided |
| --- | --- | --- | --- |
| Threads | `Lanes`, `Placement`, default lane, platform-only, bounded offload | handler lane bound (waits on a census) | |
| Declaration | `@Component(lane)`, `@Subscribe`, `@Publishes` | | the eight rulings below |
| Colour | T2.1 to T2.3, T3.1, T3.7 | the copier's pattern (annotate a record, processor emits the copy) | its annotation's name; the capture check's mechanism |
| Channels | T4.1, T4.3, T4.7 for declared sends | the graph is declared | T4.4, T4.5 mechanics |
| Completion | `Lanes.offload()`, `app.post` | two doors | `kronometer` `offload` and the timeline door unbuilt |
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

**Ruled 2026-10-01: remove both from the surface an application sees.** `@Subscribe` is the one way. The generated
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
a `@Subscribe` instead. **Still to do:** (d) to (g) below. `Placement.subscribe`, the constructor, `start` and
`onWake` are still public, and only the designer's tests and `Placements` call them from outside the package.

*Order, smallest first:* (a) move the generated call and the two witness assertions to the residual entry
point; (b) delete `superseded()` (**done**); (c) stop injecting `Placement` into components (**done**); (d) make `Placement`'s constructor, `start`, `onWake` and the `subscribe` overloads
non-public to an application; (e) port or drop the designer; (f) move `PlacementTest` and `LivenessTest`;
(g) rewrite the prose, then flip T1.3 to held in threading.md.

### 3. `lane`

Ruled already: an optional string, defaulting to `Component.DEFAULT_LANE`, whose grammar
(`[A-Za-z][A-Za-z0-9._-]*`) excludes the default by construction. **Ruled 2026-10-01: confirm and freeze, and
the grammar is contract**, because a lane's name is also its thread's name and what a stall report calls
it. The main thread stays a separate colour for the reason architecture.md gives. Changes nothing.

### 4. Group = lane

T3.6 says a subtree is the default thread grouping, so that *which grouping is late* is answerable. With no
tree (ruling 8) there is nothing else for a group to be. **Ruled 2026-10-01: for v1 a group is a lane, and
supervision, `Overrun` and reports are per lane.** A separate group concept, if a tree later wants one, is an
optional attribute with a default, which is additive. Changes nothing today; it stops T3.6 being read as a
promise of a second axis.

### 5. The `@Subscribe` defaults

`overflow = FAIL`, `capacity = 64`, registered at construction and started with the lane. FAIL is T4.2, held,
and the stack's own precedent. **Ruled 2026-10-01: freeze all three, and write capacity into the contract.**
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

### 6. Supervision

Ruled: report only, and a policy the application can override. The three open points have answers in code
already. **Ruled 2026-10-01: record them.** The threshold lives in `LivenessPolicy` (10 s by default, the policy's
to override). Lanes an application names are supervised the same way as the default lane, because the
mechanism is per lane. A report is what `Context` is handed (`exit`, `interrupt`, later `restart`) and never an
enum return. What is *not* done and is not a ruling: the main thread's heartbeat, and surfacing `Overrun` per
lane. Both are build items.

### 7. The landing rule for processor checks

[v1.md](v1.md#what-counts-as-breaking) already says a new compile error is breaking, and may land after v1
only if it rejects something that was already a bug. **Ruled 2026-10-01: confirmed, and made operational
in two sentences.** *Before v1, every planned check lands enabled* (T2.4 and T2.5's, T4.4, T4.5), which is the
reason to build them first. *After v1, a check lands without an opt-in only if what it rejects is a race, a
deadlock or a dropped message at runtime; anything stricter than that is behind a processor option and listed
in the release notes.* Applies the existing rule; changes no code.

### 8. The tree

T3.4 (*components form a tree*) is still `open`, and T3.6 and T6.3 hang off it. Its argument was that
supervision needs a structure to live in. Lanes turned out to be that structure: the watchdog supervises a
lane and names it, and nothing in the built model needed a parent.

**Ruled 2026-10-01: v1 ships a flat set of components under an implicit root, and the tree is additive.** The
extension test passes if the tree arrives as an optional attribute (`parent`, defaulting to the root) with
two consequences that apply only where it is used: a child shares its parent's lane unless it names one, and
shutdown is child before parent (T6.3). A flat application's behaviour under that default is today's, so
nothing that compiled changes. **This is the ruling most worth spiking** (add it to *Spike the extension
test*): write the throwaway `parent` and count the existing signatures it forces to change. If the count is
not zero, the tree is a v1 item. If the recommendation is taken, threading.md's own note says to re-argue
§3.4 rather than keep it, and T3.4, T3.6 and T6.3 move to *after v1*.

### 9. Is a `@Subscribe` off a component a thing?

The TODO lists *"a `@Subscribe` that is not on a component (an extension or a macro, which is the whole point
of the entry)"* as unbuilt. It has an unanswered question under it: a mailbox is drained on a lane, and an
extension declares none. **Ruled 2026-10-01: it is not a thing.** An extension is a `@Component`, with a lane like
any other, and the extension API is `@Subscribe` on it. That keeps *the container is the only source of a
reference* (T3.7) true for extensions as well, and it removes an item from the build list rather than adding
one. Reactive *automation* (a user script that reacts rather than drives) is a different shape, because that
script is not compiled into the application: it is the wire protocol growing an event stream, which is
additive. Say so, so the two are not built as one seam.

## Build order once the rulings are taken

Each step decides what the next bakes in, as [v1.md](v1.md#how-to-get-there) orders the whole.

1. **Remove the imperative path** (rulings 1 and 2), and make T1.3 held. A deletion, so it comes first.
2. **The graph, declared** (T4.5). `@Subscribe` and `@Publishes` already put the ends on declarations; the
   generated wiring should expose the whole graph, components and topics with their loss classes. The cycle
   check (T4.4) is then a walk over it and inspection and rendering come free. **Its stated limit:** only a
   *declared* send is in the graph, and Java cannot stop an undeclared one. Say so on `@Publishes`.
3. **The copier** (T2.4). The pattern is elektroq's: annotate a record, the processor emits the copy, and a
   deeply immutable record elides it. The annotation's name is undecided and is a Maven-level public name, so
   decide it here.
4. **The capture rule** (T2.5), which is the only one that wants a method body. Per
   the rule that an object which *is* the rule beats a check (T5.3 was held twice for that reason), prefer
   making capture impossible by construction: an offload API that takes its inputs as an argument and a function that cannot close over
   anything. If that is not available, the processor check should read only the lambda passed directly to the
   offload call, not method bodies in general. *Unverified; the choice belongs in the same entry as TODO's
   "two promises need a method body".*
5. **Supervision's missing halves:** the main-thread heartbeat that knows a dialog is up, and `Overrun` per
   lane.
6. **The completion path:** route offload completions into the timeline through `KronBridge`.

Steps 2 to 4 are where new compile errors come from; all of them land before v1 under ruling 7.

## What this page deliberately does not decide

The handler lane's bound (it waits on a census of what still blocks on a handler), `kronometer`'s own `offload`,
and a restart policy beyond relaunching the process. None of them changes what a component is.

# Threading: the message model

The rules an application and this framework both keep, stated normatively and in one place.

**Rewritten for 0.2 on 2026-10-26.** The model changed. Everything an application does is now a component
that owns its state and talks by messages. Components drain their mailboxes once per *tick*, in one order, and
the last write to a piece of state within a tick wins. The six rulings that set this up are in §0 with their
reasons. The roadmap that builds it is `ROADMAP.md` beside the `v0.2` checkouts, and its phases are what the
**owed** entries below point at. [architecture.md](architecture.md#the-concurrency-model) is the reasoning for
what survived from 0.1, and [its 0.2 section](architecture.md#the-message-model-02) records what changed and why.

**Each rule carries its enforcement**, because a threading rule that nothing enforces is the exact
condition `@MainThread` was written to cure: *"true, load-bearing, and enforced by nothing but the
reader's memory."* A rule here is one of:

| | Meaning |
| --- | --- |
| **held** | enforced now, by a test or by the type system |
| **owed** | ruled for 0.2, and the code still does the 0.1 thing. The entry names the roadmap phase that builds it |
| **processor** | the processor's to make a compile error, and it does not yet, for a reason the entry gives |
| **upstream** | cannot hold until a sibling repo changes |
| **open** | the rule is not yet decided, and the entry says what is missing |
| **read** | true, load-bearing, and enforced by the reader: the state to get things out of |
| **withdrawn** | no longer a rule. The number is kept so citations still land, and the entry says what replaced it |

**Numbers are stable.** A rule keeps its number when it is rewritten, a withdrawn rule keeps its entry, and a new
rule takes the next free number in its section. Code and documents cite these numbers, so renumbering would
silently point them at a different rule.

---

## 0. The model, and the rulings it rests on

In the author's words: *"All components, be that GUI elements, controllers, or any application logic, all
communicate via the message bus system. They each get a mailbox, have their own internal state they manage, can
subscribe to any desired event streams with a filter, every mailbox gets drained every frame, and the last
chronological update within the frame wins."*

The 0.1 framework did not keep this. Its handlers ran on an unbounded pool, its public API answered through
callbacks "from any thread", its windows and dialogs guarded their state with locks, and `@BeforeFrame`,
`FrameStage.APP` and `post(Runnable)` let application code onto the main thread. The 2026-10-26 audit lists each
instance. The template's own move to messages found the cost from the other side: a close gate held as two
callbacks under a ticket because *"a callback cannot be sent to another lane"*, and a "close these tabs" protocol
with batch ids and counts because two mailboxes were not ordered with each other.

The six rulings, all taken 2026-10-26:

| # | Ruling | Reason |
| --- | --- | --- |
| 1 | Each topic is **state** (keyed; the last write per key in a tick wins) or **edge** (every message, in order), and says which | atchung already separates samples from edges (`Backpressure`, `Fold`), and a channel carrying both has no correct policy |
| 2 | **One logic lane by default.** A component gets a lane of its own only by declaring one, and every lane ticks on the same schedule | Multithreading should be something a part asks for, never the default an application author inherits |
| 3 | **A tick delivers until its mailboxes are empty or its deadline arrives**, and carries the rest to the next tick in order. The framework sets the deadline, which need not line up with a frame being drawn. A hop limit catches loops | One slow tick must not cost a frame, and logic should not run at a rate set by whichever display it happens to be on |
| 4 | **No application code on the main thread.** A **single-thread mode** for CPU-constrained and headless runs has the framework run the same ticks there, and the application cannot tell which mode it is in | The main thread is the frame. On a machine with no display and few cores a second thread buys nothing, and the message model makes switching free |
| 5 | **Sends are declared by construction**: a component is handed a typed sender per topic, never the bus | A constructor parameter is a declaration the processor already reads, so every edge of the message graph is checked |
| 6 | **Immutable collections may cross** when their elements may. *Accepted tentatively*: revisit when T2.4's check is rewritten | T2.4 refusing every collection is why the template sends five messages and a count instead of one list |

### A tick, precisely

Ruling 3 says what a tick does, and §5 states it as rules. This is the picture they describe.

1. **Arrival.** Between ticks, messages arrive from outside the logic: input the main thread published, a job's
   reply, a widget's event, a rate stepping on the timeline. Each is queued in its subscribers' mailboxes in
   arrival order. A state message replaces whatever is queued for its key.
2. **Rounds.** A tick runs in rounds. In each round, every component with anything queued is handed all of it, in
   order, one message at a time. What it publishes is queued for the *next* round. When a round ends with nothing
   queued, the tick is done.
3. **Deadline.** The framework gives each tick a deadline. Once it passes, the round in progress finishes and no
   further round starts. What is queued stays queued, in order, for the next tick. A delivery is never
   interrupted: the deadline falls between deliveries, never inside one.
4. **Hop limit.** Each message counts how many deliveries separate it from the message that started its chain. A
   chain past the limit is a loop, and a loop is a bug: the framework fails loudly, naming the topic and the inbox
   where the limit was crossed (T5.7). Counted per message, not per tick, so a deadline cannot hide one.

With one lane, the rounds deliver every message in the order it was published, because a message published in
round *k* is always later than everything delivered in round *k*. With several lanes, the lanes run a round in
parallel and meet at its end, so the order across lanes is fixed by the round and a declared tie-break rather than
by a race (T5.6).

---

## 1. Lanes

**T1.1 — The lanes are a closed list.** The **main thread**, the **logic lane**, any **declared component
lanes**, the **job lane**, and the **timeline**. A further lane is a design change, argued in architecture.md,
not a convenience added at a call site. The framework also runs threads that are not lanes, because they must
stand outside what they watch or serve: the watchdog, the automation sockets, and the operating system's own
dialog threads. They are listed in one place (`Lanes`' javadoc), never run application code, and reach the logic
only by publishing.

*0.1 had a **handler lane** here, an unbounded cached pool that ran every click, key and selection handler. It is
withdrawn: a widget's event is a message (T3.8), so there is no handler to run.* *(owed, phases 3 and 4: the
handler pool is built in `Lanes.java:81` and passed to every `Gui`)*

The timeline is a lane in the sense that matters here: a confinement domain, single-threaded by construction,
entered from `FrameStage.CLOCK`. Whether it is physically the main thread or a carrier of Kronometer's own is
Kronometer's business; what is normative is T3.2.

**T1.2 — Each lane has exactly one name, used everywhere.** The logic lane is `vexel-logic`, a declared lane
`vexel-component-<name>`, the job lane `vexel-job`. *(held for the 0.1 names by `LanesTest` in both repos; owed
for the new names, phase 4. The name is load-bearing in the thread dump somebody reaches for when one lane is the
problem)*

**T1.3 — Placement is decided in the wiring and never at runtime.** No work stealing, no placement
decision, nothing to tune while running. This is the restriction the rest of the model is bought with:
static assignment is what lets the processor emit thread construction and the component-to-lane mapping
as generated code, with no scheduler in the binary. *(held, by
`PlacementTest.anApplicationCannotPlaceAComponentOrAddAMailboxByHand`. A `@Component`'s lane is on its
declaration, and the generated wiring places each lane once)*

**T1.4 — Lane threads are platform threads, never virtual.** Not stylistic:
`jdk.virtualThreadScheduler.parallelism` is a global JVM property and JDK 25 has *"no public per-thread
scheduler"*, so an application that takes Kronometer's single-carrier flag has no second carrier to give,
and virtual threads become the documented deadlock rather than merely slower. *(held, by
`LanesTest.everyThreadIsAPlatformDaemon`. The About intro's `Thread.ofVirtual()` is a virtual thread outside the
lanes, which T1.1 forbids; phase 5 removes it)*

**T1.5 — The job lane is bounded, in threads.** A pool that answers a full queue by growing has refused
backpressure. A full lane makes work wait behind the work already on it. *(held, by
`LanesTest.theOffloadLaneIsBoundedInThreadsRatherThanGrowing`; the lane is still called offload there)*

**T1.6 — One logic lane by default** (ruling 2). Every component that declares no lane runs on the logic lane.
No two of them ever run at once, so no component needs to think about memory visibility, and a run can be replayed
from its message log. A component declares a lane of its own (`@Component(lane = "physics")`) only when its work is
heavy enough to want a core: a simulator's step, say. A declared lane is not a way to block. Blocking work is a
`@Job` (T5.8). *(owed, phase 4: 0.1 gives every component lane a thread that drains continuously, `Placement`)*

**T1.7 — Where the ticks run is a deployment mode, and nothing in an application can tell** (ruling 4). By
default the logic lane is a thread of its own. In **single-thread mode**, chosen by a launch setting, the
framework runs the same ticks on the main thread between frames, or with no frames at all when headless. An
application's source is identical in both. So nothing may let a component observe its thread: no API that hands
out the current lane, no thread-local state, no difference in delivery order between modes. Declared lanes run on
the main thread too in single-thread mode, in their place in the round. *(owed, phase 4. Held, when built, by
running one witness in both modes and requiring identical message logs)*

## 2. Colour

**T2.1 — Every value has exactly one owner**: the main thread, one component, or nobody, in which case it is
*shareable* and must be deeply immutable. *0.1 said "one named component thread". Under one logic lane, many
components share a thread, and what matters is that they still share no state, so the owner is the component.*
The main thread is the framework's owner only (T7.1). *(held for the main thread, by
`VexelProcessorTest.aComponentIsNotAlsoMainThread`; the component half is T2.3, T2.6 and T2.7)*

**T2.2 — A main-thread value may not be injected into anything that is not itself main-thread.** One rule, one
direction. *(held, by `VexelProcessorTest`'s T2.2 cases. Under T7.1 the only main-thread parts are the framework's
and the widgets', so in 0.2 this is what keeps a `Gui`, a window and a Vulkan handle out of every component)*

**T2.3 — A component never holds another component.** *0.1 permitted a direct reference between two components on
one lane. Withdrawn, because it made placement change meaning:* moving a component to its own lane turned a call
into a compile error, and the reason a lane is semantically neutral (architecture.md, *the mapping is static*) is
that it should cost capacity, never behaviour. Two components talk by messages whether or not they share a thread.
*(owed, phase 4: held today only across lanes, by `VexelProcessorTest.componentsOnDifferentLanesMayNotHoldEachOther`)*

**T2.4 — A value crossing between owners arrives as if it had crossed a wire**: no shared reference to the
sender's heap, anywhere in the reachable graph. Sharing a deeply immutable value is indistinguishable from copying
it, so that is how it crosses. **Identity does not survive a crossing**: `a == b` is false afterwards. *(held for
`@Subscribe` payloads, by `VexelProcessorTest`'s T2.4 cases: a payload must be primitives, strings, enums,
records, final classes of those, or a sealed type of them. **Amended by ruling 6, owed in phase 4:** a `List`,
`Set` or `Map` whose elements may cross may cross too, provided it is unmodifiable. Which collections the check
accepts as unmodifiable, and whether that is the JDK's `copyOf` collections or a framework type, is settled when
the check is rewritten. The copier 0.1 planned is not needed and not built)*

**T2.5 — No closure crosses an owner.** *0.1 stated this as "a lambda that crosses a lane may capture only what
T2.4 lets cross", and could not enforce it without reading method bodies.* In 0.2 it holds by construction instead:
no framework or gui API that an application can reach takes a `Runnable`, `Consumer`, `Supplier`, `Function`,
`Executor` or other functional interface (T7.2), and slow work is a `@Job`, whose request and reply are payloads
checked as T2.4. *(owed, phases 3 to 5. `@Job` is built; the executors and callbacks are still reachable)*

**T2.6 — A provided value that more than one component can reach is shareable, or a framework service.** A
`@Provides` value injected into two components is shared state unless it is deeply immutable (T2.4). The framework
services that are safe from any thread (`Settings`, `Launch`, gui-core value types such as `Span` and `Color`) are
listed in the processor's table, beside the main-thread ones, and are the only exception. *(owed, phase 4. In 0.1
nothing stopped it, and the component witness's `Bench` says so: "exactly the shape the colour rule permits")*

**T2.7 — A component's state needs no synchronisation, so its fields may not ask for any.** A field of a
component may not be `volatile`, an `Atomic*`, a `java.util.concurrent` type, a lock, or a functional interface.
Each is evidence that something other than the component's own deliveries touches it. Decidable from declarations.
*(owed, phase 4)*

## 3. Ownership

**T3.1 — Vulkan, the window and present are the main thread's.** The stack's constraint, not this framework's
invention: `vexelray-gui/CLAUDE.md` files it under constraints *not visible in the code*. *(held, via T2.2, by
`VexelProcessorTest.aComponentAskingForTheWindowIsAnError`, with the framework's table of what it hands out)*

**The one exception, ruled 2026-10-08: a compute queue lent to one component.** A device made with a
compute-only queue beside the one that draws lends that queue, as a `ComputeQueue`, to the one `@Component` that
takes it, and that component's lane alone submits to it. Under T1.6 that component should declare a lane of its
own: a compute submission on the shared logic lane would make every other component's tick wait on it. *(held for
the exception by `VexelProcessorTest.theComputeQueueIsLentToOneComponent` and `aProviderIsNotLentTheComputeQueue`;
the lane requirement is owed, phase 4)*

**T3.2 — The timeline graph is the baton's.** A component never holds the baton and never reads or writes a
`Signal`, `Cell` or `Effect`. A component's output enters the timeline as a topic that `KronBridge` folds into a
cell, and a rate reaches a component as a message stepped into its mailbox. *(read)*

**T3.3 — A component's state is its own.** Nothing else reads it, writes it, or holds a reference to it.
*(processor, via T2.3, T2.4, T2.6 and T2.7. What 0.1 could not stop was the same thing through a send nobody
declared, and ruling 5 removes that: T4.5)*

**T3.4 — Components form a tree.** *(open. components.md records that the tree's first reason, supervision, was
answered per lane instead, so T3.4, T3.6 and T6.3 are owed a re-argument and are expected to move after v1)*

**T3.5 — The tree, if there is one, does not restrict who may message whom.** A supervision tree is about failure
and lifecycle; the message graph is about data flow; forcing them to coincide makes parents relays. *(read)*

**T3.6 — A subtree is the default thread grouping.** *(open, with T3.4)*

**T3.7 — The container is the only source of a cross-component reference, and it hands out senders and
shareable values, never a component.** *(held for providers, by `VexelProcessorTest.aProviderMayNotHoldAComponent`;
for components, T2.3)*

**T3.8 — A widget is the GUI's own kind of component.** Its state is the main thread's. It is written only through
its mailbox, which is the tree's folded write sink that `vexelray-gui` already drains at the start of each frame:
a property write folds by cell, a structural edit does not. What a widget *does* (a click, a selection, an edit, a
menu item, a chord) is published as a message, never handed to a handler. A shortcut binds a chord to a message.
Every message a widget publishes carries its **address**: the name of its tree and its own (`main.path`), so a
widget's name need only be unique in its tree, two windows' "path" fields are two addresses, and a reply goes to the
one that spoke. A widget built from widgets hears its parts through a hook of the widget library's own, in the
dispatch, rather than by message: that is the widget's interaction state (T3.9), and it is not the application's to
see. **Why the address is the tree's and not the application's to make unique:** a view built once per window would
otherwise have to put its window into every name, and the first one that forgot would answer for both. *(held, phase
3: every widget's callbacks are topics, `Button.PRESSED` to `TreeView.HOVERED`; an application hears one with
`gui.mailbox(topic, widget.address(), stage)`, and vexelray-gui's `WidgetAddressTest` holds the address. The
`Runnable` forms that remain (a dialog's or a rail's action, the title bar's mark) sit beside a `Sender` form, as
`Keymap.Action.on` does, and leave a view's reach with T7.2's walk)*

**T3.9 — A widget's own interaction state stays in the widget.** A caret, a selection, a hover, a scroll offset and
the text being typed change inside the widget, on the main thread, with no message. Only what the application must
know crosses: the committed text, the chosen item, the command. This is what keeps typing and dragging at zero
latency under T5.4. *(read; phase 3 checks the text field against it first, because that is where a round trip
per keystroke would be felt)*

## 4. Channels

**T4.1 — A topic is state or edge, and says which** (ruling 1). A **state** topic carries what is true now: a
tab's title, a document's dirty flag, a progress value. It names a key, and within one tick a subscriber is
delivered only the last write per key. An **edge** topic carries what happened: a keystroke, Save, Close, the
answer to a question. Every message is delivered, in order. The kind belongs to the topic, so every subscriber
agrees on it. *0.1 chose a loss policy per subscription, and held only that one topic was not an edge to one
subscriber and a sample to another.* *(owed, phase 4. **The bus half is built** (atchung 1.4, 2026-10-26): `Topic.state(name,
type, key)` and `Topic.edge`, a `Tick` folding state by key and delivering every edge, and the bus refusing two
declarations of one channel that disagree about its kind, held by `TickTest.theLastWritePerKeyWinsAndEveryEdgeIsDelivered`
and `twoDeclarationsOfOneChannelMustAgreeAboutItsKind`. What is missing is the framework running components on
the tick and the processor requiring every topic to declare its kind)*

**T4.2 — An edge is never lost quietly.** An edge mailbox is bounded, and overflowing it ends the application,
naming the topic, the bound and the subscriber, as `Backpressure.FAIL` does now. A state mailbox cannot overflow:
it holds one message per key. Loss is never a policy an edge can choose. *(held for 0.1's `FAIL` default; the
per-topic form is owed with T4.1, and built in the bus: a tick's edge mailbox has no lossy policy to choose, and
overflowing it runs `Fatal`, `TickTest.anEdgeMailboxThatOverflowsIsAFaultNamingTheTopic`)*

**T4.3 — Each `@Subscribe` gets a mailbox of its own.** *(held, by construction)*

**T4.4 — A blocking edge in a cycle.** *Withdrawn.* Under the tick, publishing never blocks: a message is queued
for the next round and the publisher carries on, so there is no blocking edge to put in a cycle and
`Backpressure.BLOCK` has no use. A cycle that keeps producing messages is caught by the hop limit (T5.7) instead.
*(owed, phase 4: a tick's inbox offers no `BLOCK`, and the framework stops offering it on `@Subscribe`. atchung keeps
`BLOCK` for `Pump`, whose other users still choose it)*

**T4.5 — Every send is declared, by construction** (ruling 5). A component is handed a typed sender for each topic
it publishes on (`Outbox<T>`, working name) as a constructor parameter, and the generated wiring builds it. The raw
`Atchung` bus is not injectable into an application part, and `@Publishes` goes away because the constructor
already says it. So the message graph the processor checks is the whole graph: there is no send it cannot see.
*(owed, phase 4. In 0.1 `@Publishes` was optional and an undeclared send was invisible to every check. The graph
is exposed to the Inspector as `BUS_GRAPH` already, held by T4.5's 0.1 form)*

**T4.6 — Any number of `Gui` trees may share a bus, each under its own name.** A tree is named when it is built
(`new Gui(bus, "main")`), and every topic it uses for itself is named by it: its mutations, its device input
(`gui.input()`, `vexelray.gui.main.input`), its gestures, clicks, focus and key routes, and its node events; every
message its widgets publish carries an address qualified by it (T3.8). A window's input source publishes on that
window's tree's input topic. Navigation is the one topic trees share on purpose: an address names its window, and
each tree takes only its own. A second open tree of one name on one bus is refused when it is built.
**Why:** 0.1's topics were class-level, so two trees on one bus each applied the other's mutations, and the bounded
mailbox of the one not being presented filled and then blocked the other's node setters for good; two windows meant
two buses, and a message could not travel between them without a bridge. *(held: vexelray-gui's `SharedBusTest`
puts two trees on one bus and holds that each applies only its own edits, hears only its own input, and that a name
is one open tree's)*

**T4.7 — Main-thread code never waits on the logic.** *0.1 stated this as "main-thread code sends only on channels
that never block". Under T4.4 no send blocks, so the narrower rule holds by construction, and what is left is
wider: the main thread never waits for a tick, a reply or a lock.* In single-thread mode the main thread runs the
tick itself, which is not waiting on it. *(owed, phases 2 and 5)*

**T4.8 — A subscription may filter by key.** The author's "subscribe to any desired event streams with a
filter": a subscriber to a state topic may name the keys it wants (`@Subscribe(topic = ..., key = ...)`), and the
wiring does the filtering, so a component is never handed a message it would discard. A filter is a declaration,
never a predicate lambda, so the processor can see it. *(owed, phase 4; the bus half is built, `Inbox.subscribe(topic,
subscriber, capacity, keys)`, held by `TickTest.aKeyFilterDeliversOnlyTheKeysItNames`, which also holds that an edge
topic refuses a filter. Filters on anything but the key are open until an application needs one)*

## 5. Completion and the tick

**T5.1 — A result arrives as a message.** Work done anywhere comes back to a component through its mailbox, and
nowhere else. Into the timeline, it comes through `KronBridge`, which folds a topic into a cell. *0.1 had a second
door, a queue drained on the main thread in `FrameStage.APP`. It is withdrawn with T7.1.* *(owed, phases 4 and 5)*

**T5.2 — `FrameStage.APP` is for draining.** *Withdrawn.* `APP` is no longer open to applications (T7.1). Whether
the stage survives for the framework's own use is decided in phase 4.

**T5.3 — Nothing has to remember to wake the loop.** A publish that queues a message is work the frame loop cannot
predict, so the bus wakes the loop itself, and the next tick runs. An idle application runs no ticks: a tick with
nothing queued is skipped, never polled. *(held for 0.1's component lanes, by
`PlacementTest.aDeliveryWakesTheLoopWithoutTheComponentAskingItTo` and `anIdleComponentDoesNotWakeTheLoop`; owed
for the tick, phase 4)*

> **This rule was held twice in 0.1, and the first time was not good enough.** The first version gave the
> component a `published()` to call after publishing a result. **A rule enforced by an API you must remember to
> call is not enforced.** Kept here as the shape of the mistake, because it is the one this document is most
> likely to make again.

**T5.4 — The latency is one tick, and it is priced, not a defect.** Input published in one frame is delivered at
the next tick, and what the logic writes to widgets is applied at the start of the frame after. A widget's own
response (T3.9) costs nothing. *"It is the same price every retained-mode GUI pays."* *(read; phase 7 measures it,
input to photon, before and after, and the author accepts the number)*

**T5.5 — A tick delivers until empty or until its deadline, and carries the rest over in order** (ruling 3). The
framework schedules each tick and sets its deadline; ticks usually follow the frame loop, but they need not
coincide with a frame being drawn, and a headless run draws none. A delivery is never interrupted. What the
deadline leaves queued is delivered first in the next tick, in its original order, and a state message carried
over still folds with any newer write to its key. "The last write within the frame wins" means within the tick.
*(owed, phase 4 for the deadline policy, which lives beside `Pacing`. The mechanism is built, atchung's
`Tick.run(deadline)`, held by `TickTest.aDeadlineStopsBetweenRoundsAndTheRestComesFirstNextTick`,
`aStateMessageCarriedOverIsStillReplacedByANewerWrite` and `aMessageFromOutsideWaitsForTheNextTickAndWakesWhoeverRunsIt`)*

**T5.6 — Delivery order is fixed, so a run replays.** Within a round, a component receives its messages in the
order they were published. Messages that arrive between ticks are ordered by arrival. Messages published in the
same round by components on different lanes are ordered by a fixed tie-break: the publishing component's position
in the wiring, then its own publish order. The same inputs in the same order produce the same deliveries, in both
modes (T1.7). *(owed, phase 4; built in the bus, held by `TickTest.sendsOfOneRoundArriveInInboxOrderHoweverTheLanesRan`, which
runs one round on one thread, on lanes backwards, and on a pool, and gets one order from all three. The tie-break
costs nothing: each component sends into an outbox of its own, and the outboxes are emptied in wiring order at the
round's end, so nothing is sorted)*

**T5.7 — A chain that will not settle is a loop, and a loop fails loudly.** Every message carries its hops: zero
when it arrives from outside, one more than the message whose delivery sent it. A message past the limit (64 by
default) ends the application, naming its topic and the inbox it was for, which is somewhere on the loop. Hops are
counted per message rather than rounds per tick because the deadline would otherwise hide a loop: a tick cut short
every time never reaches a round limit, and the loop runs forever across ticks. *(owed, phase 4; built in the bus,
`TickLoop`, held by `TickTest.twoComponentsAnsweringEachOtherForeverAreALoop` and
`aLoopIsCaughtEvenWhenEveryTickIsCutShortByItsDeadline`)*

**T5.8 — Blocking work is a `@Job`, and nothing else blocks.** Disk, network, a decode, a long computation: each is
a `@Job` with a request topic and a reply topic, run on the job lane. Its reply is a message like any other,
delivered at the first tick after it finishes. A component's delivery never blocks, never sleeps and never waits for
a reply. *(held for the `@Job` mechanism, by `JobsTest` and `VexelProcessorTest`'s job cases (components.md section
10); owed for "nothing else blocks", phases 3 and 4, which withdraw `Gui.offload()`, `Gui.async()` and
`Lanes.offload()` from application parts)*

## 6. Lifecycle

**T6.1 — Start order is distinct from construction order.** No tick runs before the wiring's `ATTACH` has
returned, so nothing is delivered to a component whose publishers are still half-built. Messages published during
construction wait in their mailboxes for the first tick. *(held for 0.1's placements, by
`PlacementTest.nothingRunsUntilTheFrameworkStartsIt`; owed for the tick, phase 4)*

**T6.2 — Shutdown is drain-then-stop, with a timeout.** The last tick runs with no deadline but the shutdown
bound, so what was queued is delivered; then the mailboxes close. Bounded, because a process that refuses to quit
is worse than one that quits having abandoned a message. *(held for 0.1's placements, by
`PlacementTest.stoppingDrainsWhatWasQueuedBeforeItCloses`; owed for the tick, phase 4)*

**T6.3 — Shutdown runs depth-first.** *(open, with T3.4)*

**T6.4 — A bus fault halts, and the framework does not save first.** Saving window placement first is refused for
upstream's reason: *"running application code on a thread that is mid-publish, holding a mailbox lock, with a full
queue behind it, is how a crash becomes a hang."* *(held, in `Faults`)*

**T6.5 — A wedged lane is reported by name, and in single-thread mode the window wedges with it.** A delivery that
runs longer than the liveness policy's threshold is reported once, by lane and component, and the policy decides.
Under T1.6 a wedged component holds the whole logic lane, so every component on it stops, while the window keeps
painting and the user can still close it: [v1.md's liveness guarantee](v1.md#the-liveness-guarantee) holds in the
default mode. **In single-thread mode it does not**: the tick is on the main thread, so a delivery that never
returns freezes the window, and the only layer left is the watchdog ending the process. That is the price of the
mode, and choosing the mode is choosing it. *(held for 0.1's lanes, by `Watchdog` and `LivenessPolicy`,
`WatchdogTest.aLaneInsideOneDeliveryTooLongIsReportedOnceByName`; owed for the logic lane and the mode, phase 4)*

## 7. The main thread and the API surface

**T7.1 — No application code runs on the main thread** (ruling 4). Two exceptions are named, and only two: drawing (below) and presentation (T7.4). The main thread pumps input, applies widget
writes, lays out, draws and presents, and runs the framework's own frame hooks. Every door through which an
application reached it in 0.1 is closed: `@BeforeFrame`, `FrameStage.APP`, `@MainThread` on an application's
provider or type, `Window.onOpened` and `onClosed` listeners, a close gate run there, and `post(Runnable)`.
**Drawing is not logic**: a custom widget's `draw` runs on the main thread, reads only the widget's own state, and
that state is written only through the widget's mailbox (T3.8). The fluid simulator's field and the About art are
a widget fed frames by a component. In single-thread mode the framework runs ticks on this thread, and that is the
framework's choice, not a door an application opens (T1.7). *(owed, phases 4 and 5: the processor refuses each
annotation in application code, and the shell withdraws each method)*

**T7.2 — No API an application can reach takes or returns a callback.** No public type in `-api`, `-core`,
`-shell` or `vexelray-gui` that an application part can be handed has a parameter or a return of a functional
interface, `Executor`, `CompletableFuture` or `Future`. A question is a request topic and its answer a reply
topic: a close gate is `CloseRequested` and `CloseAnswer`, a file picker is `PickFile` and `Picked`, a confirm is
`Ask` and `Answer`. *(owed, phases 3 to 5. Held, when built, by a test in the framework's own build that walks
the public API, so a callback added later fails the build rather than a review)*
*Ruled 2026-10-27, what the rule is about:* **which thread runs the code.** A subscription is not a callback: its
code runs on the subscriber's own thread, handed an immutable message, and answers by sending. What the rule forbids
is code handed to the GUI that the GUI keeps and runs on its own thread, in the frame. So:
- **The widget library's and the host's seams** (`onClick`, `onKey`, `onDrag`, `claim` with a `Runnable`,
  `onDrop`, `reveals`, `onResize`, the host's sinks and `frame`) are on vexelray-gui's `spi.Seams`, reached by
  `Seams.of(gui)` and from nothing an application is handed. **Why:** a seam on `Gui` is a side channel a view could
  do logic through in the frame. *(held for `Gui` by vexelray-gui's `GuiSurfaceTest`: `Gui` takes a function only
  where it runs as the caller, `mailbox`, `hear` and `batch`, and returns nothing that leads to `Seams`. Owed: the
  processor refusing a `@View` that imports the `spi` package, with `@View` in phase 4)*
- **A view may still give a widget display functions** (a row builder, a label, an `f(x)` to plot). **Why:** they
  are presentation, which T7.4 already puts on the GUI thread, and logic can never pass one because it holds no
  widget. On two conditions, both owed: the function's shape takes the item and returns a description, with no `Gui`
  in hand; and the processor reads each such lambda in a `@View` and refuses I/O, blocking calls and captures of
  mutable state.
- **What answers a question the logic owns** (`Reorder`, `Checkable`, a tree action's body and predicates,
  `Button.perform`, `Property`'s bindings) becomes declared data or a request and a reply, as menus (T7.5) and a
  tree's children did. *(owed, phase 3)*

**T7.3 — The framework's own concurrency is an allowlist.** `synchronized`, `volatile`, `java.util.concurrent` and
`Thread` appear in the framework only in the classes that implement the model: the tick, the mailboxes, the job
lane, the watchdog, the automation driver, and the operating-system seams (native dialogs, input backends) where
a foreign thread hands something in. Each of those publishes; none hands a foreign thread's call to application
code. Anywhere else, they are a defect. *(owed, phase 5. Held, when built, by a test that reads the shell's and
core's sources against the list. In 0.1 `Window`, `Windows`, `HeldDialogs`, `AboutIntro` and `UndoFeedback` hold
locks and volatiles for state the main thread and the handler pool both touched)*

**T7.4 — A view component is presentation, and it runs on the GUI thread.** *Ruled 2026-10-27, the second exception
to T7.1 beside drawing.* Something has to turn "the editor opened a file" into "add a tab holding a text field", and
that touches widgets. A **view component** is an application's own GUI element: a component whose inbox is in the
GUI's widget tick, drained on the GUI thread at the start of each frame, after input is dispatched and before the
tree's mutations are applied, so it hears a widget's event in the frame the input arrived. It may hold widgets,
nodes and the `Gui` (they are main-thread values, and T2.2 lets them into a main-thread part), send messages, and
receive them. It may not take a `@Job`, a blocking service or a logic component, and no logic component may take it
(T2.2): the processor checks each from declarations. It hears its widgets as messages (T3.8), never through a
callback (T7.2), and a node of its own the same way: the dispatcher publishes a click, a hover, a drag, the wheel, a
key or a new size on the tree's topic for that kind, addressed to the node, and the view's inbox receives it in the
widget tick of the frame the input arrived (vexelray-gui's `Gui.hear`, held by `HearTest`). Hover and size are
state, so a pointer that crossed a row and left within a frame is heard once, where it ended. A size is laid out
after the widget tick, so it is heard in the next frame's. The node-level registrations that run during dispatch
(`onClick`, `onKey` and the rest) are the widget library's: a widget's press, caret and focus have to move in
order with the input around them, which a delivery after dispatch cannot do, and the widget library is the GUI. Its time inside the frame is the main thread's watchdog's to report, as a wedged main thread is.
**Why:** it is the author's model as stated: GUI elements are components with a mailbox drained every frame, and an
application's composite element is a GUI element. The alternative, every dynamic UI described as commands in the
widget library's vocabulary, would make each application's screens a protocol and the widget API several times its
size. *(owed: phase 3 builds the GUI tick it drains in and the widgets' topics, phase 4 the `@View` declaration and
its checks)*

**T7.5 — What the dispatcher asks, a node declares.** *Ruled 2026-10-27.* Some input is a question the dispatcher
must answer in the frame it arrives in: what goes on this node's menu, what a drag beginning here carries, whether
a drop would land here and where its indicator goes, which part claims this chord. The application answers each one
**ahead of time, as data set on the node**: a menu is a list of items, each naming the message it sends when chosen;
a shortcut or a claim binds a chord to a message; a drag source is the payload a node carries. The dispatcher reads
the declaration itself and calls no one, and only the outcome leaves as a message (the item chosen, the chord
pressed, the drop made), which may arrive a tick later because nothing on screen waits for it. A declaration is
state: when the facts behind it change, the view declares again, as it would set a label. What it cannot be is an
answer worked out at the moment of the click. A widget that needs the application's data to draw at all asks for it
by message and is told it as data in the reply: a tree asks for a level (`ChildrenWanted`) and is answered with its
entries (`Children`), each carrying what its row is drawn from, so drawing a row calls no one (vexelray-gui's
`TreeView`, held by `TreeChildrenTest`). That reply may land frames later, and unlike a menu's it is allowed to: a
folder shows open and empty until its contents arrive, which is what a folder on a slow disk does, and nothing already
on screen moves meanwhile.
**Why:** a message's reply arrives a tick later, and a question asked as a message would open the menu a frame late
and drag the drop indicator a frame behind the pointer for the whole gesture, which is the visual discontinuity the
author has ruled out. A function answering in the frame would be a callback (T7.2) that could read anything, which
is the side channel this model removes. The widget library's own containers (a tree's reorder, a board's cards, a
text field's Copy and Paste) still answer in code: they are the GUI, not an application. *(owed, phase 3: the
declarations on `Gui`, and moving the function-valued seams (`onContextMenu`'s source, `onDrop`, `onDragSource`, a
shortcut's `Runnable`) where only the widget library reaches them, so T7.2's API walk holds for what a view is
handed)*

---

## The matrix

The point of the column is that the unenforced rules are a list rather than an impression.

| | held | owed | processor | upstream | open | read | withdrawn |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **1 Lanes** | T1.3, T1.4, T1.5 | T1.1, T1.2, T1.6, T1.7 | | | | | |
| **2 Colour** | T2.2 | T2.1, T2.3, T2.4, T2.5, T2.6, T2.7 | | | | | |
| **3 Ownership** | T3.1, T3.7, T3.8 | | T3.3 | | T3.4, T3.6 | T3.2, T3.5, T3.9 | |
| **4 Channels** | T4.3, T4.6 | T4.1, T4.2, T4.5, T4.7, T4.8 | | | | | T4.4 |
| **5 Completion** | | T5.1, T5.3, T5.5, T5.6, T5.7, T5.8 | | | | T5.4 | T5.2 |
| **6 Lifecycle** | T6.4 | T6.1, T6.2, T6.5 | | | T6.3 | | |
| **7 Main thread** | | T7.1, T7.2, T7.3, T7.4, T7.5 | | | | | |

Where a rule is held for its 0.1 form and owed for its 0.2 form, it is counted as owed: what the column answers is
whether 0.2 is true yet, and it is not. **Ten of the forty-nine rules are held and twenty-nine are owed.**
Phase 2 built the bus half of T4.1, T4.2, T4.8, T5.5, T5.6 and T5.7 in atchung; each says so, and each stays owed
until the framework runs its components on the tick. That is the honest state of a
redesign at its first commit, and the column is how its progress is read: each phase in the roadmap moves a known
set of entries to *held*, in the same commit that makes them true.

## Promoting a rule

A rule moves left when something checks it, and the entry changes in the same commit that makes it true. A rule
that cannot be checked should say what seam it would need. Deleting a rule is allowed and preferable to keeping one
that nothing believes, but its entry stays, marked **withdrawn**, with what replaced it: its number is cited
elsewhere. Re-argue it in architecture.md first, since this file holds rules and that one holds reasons.

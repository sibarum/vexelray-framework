# Threading conventions

The rules an application and this framework both keep, stated normatively and in one place.

[architecture.md](architecture.md#the-concurrency-model) is the reasoning — why one component gets one
thread, why placement is static, why a component never holds the baton. This document is the rules that
fall out of it, written so they can be checked rather than read.

**Each rule carries its enforcement**, because a threading rule that nothing enforces is the exact
condition `@MainThread` was written to cure — *"true, load-bearing, and enforced by nothing but the
reader's memory."* A rule here is one of:

| | Meaning |
| --- | --- |
| **held** | enforced now, by a test or by the type system |
| **processor** | a compile error once `-processor` exists; inert until then |
| **upstream** | cannot hold until a sibling repo changes |
| **open** | the rule is not yet decided, and the entry says what is missing |
| **read** | true, load-bearing, and enforced by the reader — the state to get things out of |

Most of what follows is assembled rather than invented: the constraint already existed as prose
somewhere in the stack, and is quoted where it did. §3.4–§3.7 are the exception and say so.

---

## 1. Lanes

**T1.1 — The lanes are a closed list.** Five: the **main thread**, the **timeline**, **component
threads**, the **handler lane**, the **offload lane**. A sixth lane is a design change, argued in
architecture.md, not a convenience added at a call site. *(read)*

The timeline is a lane in the sense that matters here — a confinement domain, single-threaded by
construction, entered from `FrameStage.CLOCK`. Whether it is physically the main thread or a carrier of
Kronometer's own is Kronometer's business; what is normative is §3.2.

**T1.2 — Each lane has exactly one name, used everywhere.** "Worker thread" used to mean the handler lane
in `Gui`'s Javadoc and, in practice, the offload lane as well — one name for two lanes with different
rules, in documentation applications read. The name split when the pool did: `vexelray-gui-handler` and
`vexelray-gui-offload` upstream, `vexel-handler` and `vexel-offload` in the container, and
`vexel-component-<name>` for a placed one. *(held, by `LanesTest` in both repos — and the name is
load-bearing in the thread dump somebody reaches for when one lane is the problem)*

**T1.3 — Placement is decided in the wiring and never at runtime.** No work stealing, no placement
decision, nothing to tune while running. This is the restriction the rest of the model is bought with:
static assignment is what lets the processor emit thread construction, the component-to-thread mapping
and barrier participation as generated code, with no scheduler in the binary. *(processor)*

**T1.4 — Component and offload threads are platform threads, never virtual.** Not stylistic:
`jdk.virtualThreadScheduler.parallelism` is a global JVM property and JDK 25 has *"no public per-thread
scheduler"*, so an application that takes Kronometer's single-carrier flag has no second carrier to give
— and virtual threads become the documented deadlock rather than merely slower. That flag is the
application's and correctness never depends on it, so the default has to be the one that is right when
it *is* set. *(held, by `LanesTest.everyThreadIsAPlatformDaemon` — which asks `Thread.isVirtual()` rather
than reading the property, because the property is the application's and this may not depend on it)*

**T1.5 — The offload lane is bounded.** A pool that answers a full queue by growing has chosen the one
policy the rest of this document refuses (§4.2). `Gui` built an unbounded `newCachedThreadPool`, so a
wedged filesystem answered backpressure by spawning threads. Bounded in **threads**, not in queue, and
the distinction is the rule rather than an implementation detail: a full lane should make work *wait*
behind the work already on it, which is backpressure, whereas a bounded queue makes it *fail* — and
choosing loss is §4.2's decision, taken per channel by whoever knows what the channel carries, not a
default a pool takes on their behalf. *(held, by `LanesTest.theOffloadLaneIsBoundedInThreadsRatherThanGrowing`)*

**The handler lane is deliberately not bounded yet**, and that is a gap with a reason rather than an
oversight. Bounding it is safe only once nothing blocking runs on it, and the census that would settle
that has not been taken — the one application checked, `text-editor-vexel-demo`, turned out to be doing
its file I/O on the *main* thread rather than on a handler, which is a different defect and is now fixed.
So the bound waits on knowing, not on a known blocker. *(open — and the thing it wants is a census)*

## 2. Colour

**T2.1 — Every value has exactly one colour**: the main thread, one named component thread, or
*shareable*. Colour is a property of the value, not of the moment. The main thread being its own colour
rather than a lane with a reserved name is now a **decision** rather than an accident of how this
sentence was first written — see [architecture.md](architecture.md#the-vocabulary-decided-before-the-processor-emits-anything).
`Lanes` does not mint it, there is exactly one of it, and what may happen there differs in kind; naming
it as a lane would make a string the discriminator for all of that. *(processor)*

**T2.2 — A main-thread value may not be injected into anything that is not itself main-thread.** One
rule, one direction. The inverse is deliberately allowed: handing a main-thread component an immutable
model or a settings record violates nothing, and requiring an annotation for it would put `@MainThread`
on most of an application. *(processor — this is `@MainThread`'s written specification, and it is inert)*

**T2.3 — A direct reference between two components is permitted only where they share a thread.**
Decidable because placement is static: the colour of a value *is* the thread it was placed on, and
dynamic placement would have made this check undecidable. **Static is not yet the same as visible**, and
this rule as written assumed it was. `shell.place("compose")` is a call in a wiring method body, so the
placement is decided once and never changes afterwards but a processor — which reads declarations, not
bodies — cannot see it. The rule holds; what it needs first is placement moved onto the declaration.
*(processor, once placement is declared — see
[architecture.md](architecture.md#the-vocabulary-decided-before-the-processor-emits-anything))*

**T2.4 — The shareable set is closed**: immutable values, `Versioned<T>`, and `State<T>` — the last two
being `atchung-core`'s answer to the same question, *"consumers read coherent, immutable, versioned
snapshots."* **How an application declares a type of its own shareable is not decided**, and it should be
before the processor freezes the classification, because every application type gets classified by this
rule. *(open)*

**T2.5 — A lambda that crosses a lane may capture only shareable values.** This is what makes the
offload lane a policed edge of the model rather than a hole in it: an offloaded task that captures a
component's state becomes a compile error rather than a race. *(processor)*

## 3. Ownership

**T3.1 — Vulkan, the window and present are the main thread's.** The stack's constraint, not this
framework's invention — `vexelray-gui/CLAUDE.md` files it under constraints *not visible in the code*.
*(processor, via §2.2)*

**T3.2 — The timeline graph is the baton's.** A component thread never holds the baton and never reads
or writes a `Signal`, `Cell` or `Effect`. `KronBridge` exists because the two systems have incompatible
threading models: the bus publishes on whatever thread published, *"while the timeline is
single-threaded by construction, because that is what makes it ordered"*, and delivering on the
publisher's thread *"would mean mutating the graph from off the timeline, which is the one thing the
design does not permit."* *(read)*

**T3.3 — A component's state is its own thread's.** Nothing else reads it, writes it, or holds a
reference to it. *(processor, via §2.3)*

**T3.4 — Components form a tree.** A component may own child components; the application is the root.
*(open — new to this stack, see the note below)*

**T3.5 — The tree is for lifecycle, supervision, grouping and naming. It does not restrict who may
message whom.** Keeping these apart is the whole of the rule. A supervision tree is about failure and
lifecycle; the message graph is about data flow; they rarely have the same shape, and forcing them to
coincide gives one of two bad outcomes — the tree gets contorted to match the traffic, or parents become
relays, and a relay parent inherits all of its subtree's traffic along with being the single point where
backpressure bites. Restricting messaging to peers and children does not buy acyclicity either: a
sibling group is a complete graph, and parent↔child is itself a cycle the moment a child may answer.
The real hazard is narrower, and §4.4 states it. *(read)*

**T3.6 — A subtree is the default thread grouping.** A child shares its parent's thread unless the
wiring places it elsewhere. This is what makes *which grouping is late* an answerable question: slip is
a property of the one timeline and cannot be made per-domain, but a static mapping can name the grouping
that is overrunning. *(processor)*

**T3.7 — The container is the only source of a cross-component reference.** A component's internals are
unreachable unless the wiring exposes them, and what the container hands out is a channel or a shareable
value, never the object. This is the sharp form of a mismatch already recorded for the processor's brief:
`@Component` is a DI contract, and constructor injection handing A a direct reference to B is the thing a
mailbox exists to prevent. *(processor)*

> **On §3.4–§3.7.** These are new to the stack — there is no hierarchy, parent or supervision concept in
> `atchung`, its documentation, or elektro-Q, whose `Actor` is a marker and whose `Conduit` is flat. By
> this repo's own rule that is grounds for suspicion, so the argument has to be made rather than assumed:
> the tree is not added for tidiness, it is added because **supervision has no structure to live in
> without it**. The recorded gap is that *"nothing notices that a component has stopped draining, or
> names it"*, and a parent is the thing with standing to notice and to name. The grouping in §3.6 is the
> second thing it pays for. If supervision is ever answered another way, §3.4 should be re-argued rather
> than kept.

## 4. Channels

**T4.1 — A channel carries one loss class.** A *sample* — pointer position, window size, a clock reading
— is `COALESCE_LATEST`, because the next one supersedes it. An *edge* — a keystroke, a command, a tree
mutation — is `FAIL`, or `BLOCK` where the publisher can safely wait. A channel carrying both *"cannot be
given a correct policy — every choice is wrong for half the traffic. That is not a policy problem to be
solved here; it is a signal to split the channel."* *(processor — structural rather than remembered)*

**T4.2 — `FAIL` is the default, and needs no justification; loss does.** *"Choose FAIL unless you can say
why loss is harmless on this channel"* — a lost event is not an error anywhere, it is an absence, and the
bug is then looked for in the consumer, which is working perfectly. *(held, by `Backpressure`'s default)*

**T4.3 — A component gets a mailbox per loss class, not one mailbox.** §4.1 applied to a component
rather than to a channel in isolation. *(processor)*

**T4.4 — No cycle in the message graph may contain a blocking edge.** Cycles are not the hazard —
request/response *is* a cycle, and with asynchronous mailboxes it is ordinary. A cycle containing a
`BLOCK` edge is a deadlock, and it is not hypothetical: a mailbox bounded at 65,536 with `BLOCK`, filling
because nobody drained it, is the freeze this repo shipped for three commits. Because placement is static
and the wiring is compile-time, the processor sees the entire graph — every channel, its loss class and
its policy — and can reject the loop. *(processor — and this is the rule that pays for §4.5)*

**T4.5 — The message graph is declared in the wiring.** An emergent graph cannot be checked, rendered,
or reasoned about; a declared one can. This, rather than any restriction on the graph's shape, is what
keeps the design from being cornered: §4.4 catches the blocking cycle that a topology rule would miss.
*(processor)*

**T4.6 — Two `Gui` trees may not share a bus.** `Gui`'s topics are `static final` class-level names, so
every instance subscribes to the same `vexelray.gui.mutations` and two trees on one bus each receive the
other's mutations. Share a bus with anything that is not a tree — input publishers, workers, components
and their mailboxes — and never with a second `Gui`. It is also the ceiling on one inspectable fabric per
application, and on any scoped addressing §3.4 would want. *(upstream — the fix is topics named per
instance)*

## 5. Completion

**T5.1 — A result never lands in place.** Work done off a lane comes back through one of exactly two
doors: published on a `Topic` and folded into a `Cell` by `KronBridge`, or dropped on a queue drained in
`FrameStage.APP`. The first is for anything the timeline should see, the second for main-thread-only
effects. *(read)*

**T5.2 — `FrameStage.APP` is for draining, not for working.** *"The right place for reading a queue a
worker filled, and the wrong place for the work the worker was doing"* — whatever runs there is inside
the frame budget, on the main thread, every frame the loop wakes for. *(read)*

**T5.3 — Every mailbox owes a `WakeSource`, and nothing has to pay it.** A component that finishes work has
produced something the loop cannot predict, which is `WakeSource`'s definition. The symptom of omitting the
wake is the one the GUI already paid for: a window that is responsive except for the interactions that
happened to arrive that way. A `Placement` **is** a `WakeSource`, the container connects it when it starts
the component, and it wakes the loop itself after any drain that delivered something — hung on the one
thing every path has in common, which is that a delivery ran. **There is no call to make and therefore none
to forget**, and the processor has nothing to emit for this at all. *(held, by
`PlacementTest.aDeliveryWakesTheLoopWithoutTheComponentAskingItTo`, with
`anIdleComponentDoesNotWakeTheLoop` holding the other side — a park expiring is not work, and a component
that woke on one would turn a render-on-demand loop back into a polling one while looking like a fix)*

> **This rule was held twice, and the first time was not good enough.** The first version gave the
> component a `published()` to call after publishing a result. That is a real improvement on the designer's
> hand-written original — which met the obligation *by accident*, because announcing a phase wrote to a node
> and a node mutation wakes the loop — and it is still the same bug one step along: omittable, silent, and
> producing exactly the symptom the rule exists to prevent. **A rule enforced by an API you must remember to
> call is not enforced.** Worth keeping here as the shape of the mistake, because it is the one this
> document is most likely to make again: `held` is a claim about whether something *can* go wrong, not about
> whether the framework has written down that it shouldn't.

**T5.4 — One drain period of latency is the price of the ordering guarantee, and it is not a defect.**
*"It is the same price every retained-mode GUI pays."* Named here so it is not re-litigated later as a
fault of the component model. *(read)*

## 6. Lifecycle

**T6.1 — Start order is distinct from construction order.** A mailbox must not pump before its publishers
exist. `Shell.place` builds a component with nothing running on it and the container starts every
placement together, after the wiring's `ATTACH` has returned — so the rule is a moment in the framework
rather than a line a wiring is trusted to put last. The hand-written first component had to say it for
itself: *"last, so nothing the component touches is still half-built when its thread starts"*, which is
an ordering rule enforced by where one line happened to sit in a constructor. *(held, by
`PlacementTest.nothingRunsUntilTheFrameworkStartsIt` and `aMailboxCannotBeAddedOnceTheComponentIsRunning`)*

**T6.2 — Shutdown is drain-then-stop, with a timeout.** Not cosmetic: `Backpressure.FAIL` is the default
and resolves through `Fatal`, so getting shutdown ordering wrong kills the process rather than dropping a
message. `Placement.close` is that order exactly — the flag goes down, `Pump.wake()` ends the park, the
thread does one last drain and returns, and only then are the mailboxes closed. Closing them first would
drop what is queued on the floor; interrupting first would abandon a delivery mid-flight. Bounded at one
second per component and two for the lanes, because a process that refuses to quit is worse than one that
quits having abandoned a message — the same trade §6.4 makes, for the same reason. *(held, by
`PlacementTest.stoppingDrainsWhatWasQueuedBeforeItCloses` and `shutdownStopsTheComponentThread`)*

**T6.3 — Shutdown runs depth-first: a child stops before its parent.** A parent that stopped first would
have nothing left to answer a child's last publish. *(open, with §3.4)*

**T6.4 — A bus fault halts, and the framework does not save first.** `Atchung.onFatal` is process-wide
and wants calling *"once, at the application edge"*, which is `VexelApplication`. Saving window placement
first is refused for upstream's reason: *"running application code on a thread that is mid-publish,
holding a mailbox lock, with a full queue behind it, is how a crash becomes a hang."* A lost placement is
the cheaper loss. *(held, in `Faults`)*

**T6.5 — A wedged component is not a bus fault.** It is a mailbox filling, answered by the `Backpressure`
of its channel rather than by a process policy. What neither half covers is **supervision** — nothing
notices that a component has stopped draining, names it, or decides what the rest of the application does
about it. It wants `Overrun` surfaced per grouping (§3.6) before it is built on anything but a timeout.
*(open)*

---

## The matrix

The point of the column is that the unenforced rules are a list rather than an impression.

| | held | processor | upstream | open | read |
| --- | --- | --- | --- | --- | --- |
| **1 Lanes** | T1.2, T1.4, T1.5 | T1.3 | | | T1.1 |
| **2 Colour** | | T2.1, T2.2, T2.3, T2.5 | | T2.4 | |
| **3 Ownership** | | T3.1, T3.3, T3.6, T3.7 | | T3.4 | T3.2, T3.5 |
| **4 Channels** | T4.2 | T4.1, T4.3, T4.4, T4.5 | T4.6 | | |
| **5 Completion** | T5.3 | | | | T5.1, T5.2, T5.4 |
| **6 Lifecycle** | T6.1, T6.2, T6.4 | | | T6.3, T6.5 | |

Three readings worth taking from it. **Thirteen of the thirty-two rules say `processor`**, which is still
the argument for writing the colour checker earlier than its position on the critical path: it is the
single change that moves the most of this document out of the reader's memory. **`open` is four rules,
three of which are the component seam and its supervision**, which is where the remaining design work
actually is. And **the `upstream` column is down to one**, which is the useful surprise: T1.2 and T1.5
were filed as things this repo could not fix, and both turned out to be one change in `vexelray-gui` —
the worker pool ceasing to be a field initializer — rather than two problems.

**What moved this commit, and what made it move.** Eight rules are now held rather than five, and every
one of them was paid for by the same two objects: `Lanes`, which owns the application's threads instead
of a `Gui` owning one set per tree, and `Placement`, which owns a component's thread and the
drain-then-stop around it. Worth noticing that none of the promoted rules needed the processor. They
needed something to *be* the rule — a lane with a name, a placement that is a `WakeSource`, a start that
is a separate moment from a construction — which is the difference between a rule enforced by a type and
a rule enforced by a reader, and it is available a long way before the compile error is.

## Promoting a rule

A rule moves left when something checks it, and the entry changes in the same commit that makes it true.
A rule that cannot be checked should say what seam it would need, the way the TODO's dropped-capability
entry does — *"recorded so the gap is a decision."* Deleting a rule is allowed and preferable to keeping
one that nothing believes; re-argue it in architecture.md first, since this file holds rules and that one
holds reasons.

# VexelRay 0.2: the message model, end to end

A roadmap for redesigning `vexelray-framework` and its template so that every part of an application talks by
messages, and nothing else. The apps (mainframe, calculator, text editor, scheduler) are allowed to break while this
happens; they are ported one by one after it lands. The bar is [v1.md](docs/v1.md)'s:
**no major refactor of an application after v1**, so whatever this settles has to be the shape the stack keeps.

Written 2026-10-26. Status: **section 2's forks ruled 2026-10-26**, two of them with amendments (forks 3 and 4).
Fork 6 is accepted tentatively. Phases 0 to 4 are built, and phases 3 and 6 have work left; each phase says what.

*Kept in vexelray-framework since 2026-10-29, at the top of the repo that holds most of the redesign; it was written
beside the `v0.2` clones, in a folder of their own. A link to a sibling repo assumes the checkouts sit side by side.*

---

## 1. The model

In the author's words:

> All components, be that GUI elements, controllers, or any application logic, all communicate via the message bus
> system. They each get a mailbox, have their own internal state they manage, can subscribe to any desired event
> streams with a filter, every mailbox gets drained every frame, and the last chronological update within the frame
> wins.

And the four things it rules out:

| Ruled out | Why it is wrong here |
| --- | --- |
| A callback instead of a message | A callback runs on whoever calls it, with whatever it closes over. The processor cannot see it, check it or order it. |
| A lock on state several threads touch | If state is owned, nothing else touches it. A lock is evidence that ownership failed. |
| Multithreading by default | Every extra thread is an ordering problem the application author inherits. Concurrency should be something a part asks for. |
| Application logic on the main thread | The main thread is the frame: input, layout, draw, present. Logic there is a dropped frame waiting to happen. |

### 1.1 What the model becomes in practice

```
             ┌──────────────── frame N ─────────────────┐
main thread  │ pump input → publish input edges         │  (framework only)
             │ drain widget mailboxes → layout → draw   │
             └──────────────────────────────────────────┘
                          │ messages        ▲ widget writes (folded)
                          ▼                 │
logic lane   │ tick N: drain every component mailbox, in global order │  (application code)
                          │ requests        ▲ replies (land at the next tick)
                          ▼                 │
job lane     │ @Job: disk, network, decode — runs to completion │
```

* **Component.** A class with a mailbox, private state and `@Subscribe` methods. It is the only kind of application
  part that has behaviour. Controllers, views and services are all components.
* **Widget.** A GUI element is a component of the GUI's own kind. Its mailbox is the tree's folded write sink, which
  `vexelray-gui` already has, drained by the main thread at the start of each frame. What a widget *does* (a click,
  a selection, an edit) is published as a message, never handed to a handler.
* **Tick.** The framework schedules ticks, usually once per frame, and gives each one a deadline that need not line up
  with a frame being drawn (fork 3). In a tick, the logic lane drains every component's mailbox. Delivery is in
  **global publish order**, across topics and senders, not per mailbox. For a *state* topic, only the last write per
  key in the tick is delivered (fork 1). Whatever the deadline leaves undelivered carries over to the next tick, in
  order.
* **Mode.** By default the logic lane is a thread of its own. In **single-thread mode** (fork 4, for CPU-constrained
  and headless runs) the framework runs the same ticks on the main thread. Application source is identical in both,
  and nothing in it can tell which mode it is in.
* **Job.** Work that blocks (disk, network, a long computation) is a `@Job`: a request topic and a reply topic, run on
  the bounded job lane. Its reply arrives like any other message, at the next tick. This is the other agent's
  `@Job` (components.md §10, built 2026-10-26), kept as it is.
* **Values.** Anything provided to more than one component is deeply immutable or a framework service whose own
  docs say it is safe from any thread (`Settings`, `Span`, `Color`). A mutable object shared between parts is a compile
  error.

### 1.2 What it is not

* It is not an actor system with a thread per component. Mailboxes are per component; threads are per lane, and by
  default there is one logic lane.
* It is not a request/response RPC layer. A reply is a message on a topic like any other, correlated by an id in the
  payload when the sender needs one.
* It is not "everything through `Atchung.publish` with a string". Sends are declared and typed (§4.3), so the
  processor sees every edge of the message graph.

---

## 2. Forks to rule before code

Every one of these changes what is built after it. Each has a recommendation and the reason for it, because a
ruling without its reason is only an idea. None is ruled yet.

### Fork 1. "Last write wins" for state, every message for edges

> **Ruled 2026-10-26: yes, as recommended.** The reason is the one below: atchung already separates samples from
> edges, and a channel that mixes the two has no correct policy.

**Recommendation:** each topic declares which of two kinds it is, and the processor requires the declaration.

* **State** (`@Topic(kind = STATE, key = "id")`): what is true now. A scroll offset, a tab's title, a document's
  dirty flag. Within one tick, only the last write per key is delivered. This is the author's "last chronological
  update within the frame wins".
* **Edge** (`kind = EDGE`): something that happened. A keystroke, Save, Close, an answer to a question. Every one is
  delivered, in order. Dropping three keystrokes because they arrived in one frame would be a bug no one could find.

**Why:** atchung already draws exactly this line. `Backpressure`'s javadoc says a sample and an edge are different
loss classes, and a channel carrying both "cannot be given a correct policy". `Fold` already folds a keyed cell and
leaves edges alone. The redesign promotes that from a per-subscription choice to a property of the topic, so every
subscriber to a topic agrees on it.

**What it removes:** `Backpressure.FAIL` with capacity 64 as the default, and the `SET = 4096` mailboxes the template
needed so a burst of per-tab messages would not end the application. Edge mailboxes become unbounded within a tick
and are bounded by a per-tick budget that is a loud failure, the way `FAIL` is now.

### Fork 2. One logic lane by default

> **Ruled 2026-10-26: yes, as recommended.** The reason: multithreading should be something a part asks for, never
> the default an application author inherits.

**Recommendation:** every component runs on one logic lane unless it declares another. A component that declares a
lane of its own still ticks once per frame, on its own thread, between the same barriers.

**Why:** the author's "multithreading by default" complaint. One lane means no two components ever run at once, so
no application author has to think about memory visibility, and a run can be replayed from its message log. A lane
of its own is for a component whose tick is genuinely heavy (a simulator's step), and it buys parallelism without
giving up the per-frame contract. Blocking work is never a lane: it is a `@Job`.

**What it removes:** `Lanes`' unbounded handler pool, `Placement`'s continuously draining per-component threads, and
the "two mailboxes are not in order with each other" caveat that makes the template's `Editor` count batches and
carry the view's word on `dirty` in every `Closing` message.

### Fork 3. Delivery inside a tick: run to quiescence

> **Ruled 2026-10-26: yes, with an amendment.** The author: "there can be a deadline imposed by the framework, which
> does not necessarily align with the actual frame render timestamp."
>
> **What that means (this reading confirmed by the author 2026-10-26):** a *tick* is the framework's unit, not the renderer's. The framework
> schedules each tick and gives it a deadline. Ticks usually follow the frame loop, but they need not coincide with a
> frame being drawn: the display may be capped at 60 Hz or refresh at a variable rate, a window may be hidden, and a
> headless run (fork 4) draws nothing at all. "The last write within the frame wins" therefore means *within the
> tick*. A tick delivers until its mailboxes are empty **or its deadline arrives, whichever is first**. Whatever is
> still queued carries over to the next tick in its original order, so nothing is lost and order holds. The hop limit
> stays, but only as a loop detector: a chain that keeps feeding itself is a bug, which a deadline alone would only
> spread across ticks.
>
> **Why the deadline, in this reading:** one slow tick must not cost a frame, and logic should not run at a rate set
> by whichever display it happens to be on.

Messages published during tick N can be delivered in tick N (keep draining until the mailboxes are empty) or held
until tick N+1 (double-buffered).

**Recommendation:** run to quiescence within the tick, with a hop limit that is a loud failure.

**Why:** held-until-next-tick is simpler and makes loops impossible, but every hop costs a frame. The template's
"click a file in the navigator" goes view → editor → job → editor → view, which would be four frames before the job
even counts. Quiescence keeps a chain inside one frame while the global order still holds. A loop is a bug, and the
hop limit turns it into a crash naming the topics, like an overflow does now.

Jobs are the exception: their replies land at the next tick after they finish, because they ran outside the tick.

### Fork 4. The main thread runs no application code

> **Ruled 2026-10-26: yes, with an amendment.** The author: "there could be a mode where the application code is
> intentionally run on the main thread, such as for CPU-constrained, headless environments."
>
> **What that adds:** where the tick runs is a *deployment mode*, never something application code chooses or can
> observe. In the default mode the logic lane is its own thread. In a **single-thread mode** the framework runs the
> tick on the main thread, between frames or with no frames at all when headless. The application's source is the same
> in both modes. That can only hold if components never depend on which thread they are on, so this becomes a
> requirement on everything else: no API that hands out the current thread, no thread-local state, and no difference
> in delivery order between the two modes. The mode is a framework setting (`--logic=main` or similar, named in
> phase 4), and the processor's refusal of `@BeforeFrame` and the rest stands in both modes. Running on the main
> thread is the framework's decision, not a door an application opens.
>
> **Why:** on a CPU-constrained machine with no display, a second thread buys nothing and costs a core's worth of
> scheduling. The message model is what makes the switch free: a part that only reads its mailbox cannot tell which
> thread drained it.

**Recommendation:** remove, for applications, `@BeforeFrame`, `FrameStage.APP`, `@MainThread` providers,
`Window.onOpened`/`onClosed` listeners and `post(Runnable)`. They stay framework-internal where the framework needs
them (`FrameHooks` for pacing and the timeline).

**Why:** each is a door through which application code reaches the frame, and they are what the `@BeforeFrame`
meters in the witnesses and the About intro use. The one exception the model needs is **drawing**: a custom widget's
`draw` runs on the main thread, and that is GUI code, not logic. It reads only the widget's own state, and that state
is written only through the widget's mailbox. The fluid simulator and the About art become a widget plus a component
that sends it frames.

**Cost:** one frame of input-to-response latency when the response needs logic: the input is published in frame N,
the logic ticks, and the widget writes are applied at the start of frame N+1. A widget's own response (a caret moving,
a button pressing) stays inside the widget and costs nothing. **To measure, not assume:** `PacingMeasurementTest`
gets an input-to-photon case before and after.

### Fork 5. Sends are declared by construction

> **Ruled 2026-10-26: yes, emphatically.** The reason is the one below: a constructor parameter is a declaration the
> processor already reads, so every edge of the message graph is checked.

**Recommendation:** a component does not get the raw `Atchung` bus. It gets a typed sender per topic as a constructor
parameter (working name `Outbox<T>`), and the wiring builds it. `@Publishes` goes away, because the constructor
already says it.

**Why:** today a send is a method call in a body the processor does not read, so `@Publishes` is optional and an
undeclared send is invisible to every check (T4.x). A constructor parameter is a declaration the processor already
reads. The memory note *rules promote by construction* is this exactly: an object that *is* the rule moves it off
the reader's memory.

### Fork 6. Lists in payloads

> **Accepted tentatively 2026-10-26** ("Sure?"). Built as recommended unless something better turns up first. The
> alternative, if the author prefers it, is a framework-owned immutable collection type that is the only collection
> T2.4 accepts. It is more explicit at the call site and cannot be confused with a mutable `List`, but it costs a
> conversion at every boundary with ordinary Java code. Revisit at phase 4, when T2.4's check is rewritten.

**Recommendation:** a `List`, `Set` or `Map` built by `List.copyOf` / `Map.copyOf` (or the framework's own
immutable collections) is a deeply immutable payload when its element type is.

**Why:** T2.4 refuses any collection today, which is why the template sends "close these five tabs" as five messages
with a batch id and a count. That is a protocol written around a checker, not around the problem.

*Built 2026-10-28, ahead of phase 4 because the template's `-core` needed it (vexelray-framework `3df25ce`): the JDK's
`List`, `Set` and `Map` of shareable values, inside a payload only; a job's field holding one is still refused. The
type cannot show the collection is unmodifiable, so that is the sender's part (`List.copyOf`, or a record that copies
in its constructor), which chains.md's rule 4 lets the check leave to it.*

### Fork 7. Input, logic and drawing on their own clocks

> **Ruled 2026-10-29**, by the author, after phase 7's measurements: *"the framework was engineered so that it would be
> possible to run everything on independent clocks, so we may as well."* Built the same day (vexelray-gui `63b2abb`).

**What was wrong:** three rates were one. The frame ceiling (60 Hz, kept as ruled in phase 7) was enforced by sleeping
out the rest of a gap after anything ended the park, so OS input that landed inside a gap sat unread until it ran out,
the input snapshot and its dispatch ran only when a frame did, and the logic's answers reached a view only in the next
frame. Phase 7 measured a click's result through the logic at 17.5 ms, and called it fork 4's cost; most of it was input
and messages waiting for the drawing clock.

**The ruling:** the ceiling caps draws and nothing else. OS input and a wake from another thread (a lane, a job, the
automation socket) run a pass at once: pump, input snapshot, dispatch, the views' mailboxes, layout. The pass draws only
if the ceiling's slot has come; otherwise its draw stays owed and the loop parks until the slot, still woken by input.
The logic already runs on its own thread when messages arrive; now what it sends is heard when it arrives too.

**One thread, three clocks.** The OS pump stays on the main thread (Win32 binds a window to its thread), so this is two
kinds of pass on the loop that exists, not a new thread. **A wake the loop posts to itself** (motion still moving, a
relayout, a widget's write) is a draw request, held to the slot, and posts no OS message: posting it would end the next
park at once and spin the loop through the gap. That is the one rule that keeps the split safe.

**What it costs:** a pointer sweeping a window runs an update (hit-test, hover, the widgets' messages) per batch of mouse
messages rather than per slot, which the 2026-10-25 change had stopped; it still draws at most once a slot. A burst of
wakes from a busy lane runs a pass each (W3's metronome: about 200 passes a second against 57 draws).

**Measured** (phase 7's instrument, the same machine): a click's result through the logic from **17.5 ms to 1.9 ms**
median (p95 8.3), in both modes. At fifteen frames a second it is **1.1 ms in `--logic=main`** (was 66) and still
**64 ms on a lane**: a draw that slow holds the main thread for as long as it takes (the instrument's slowed draw parks
it 62 ms, as a slow GPU would), and an answer arriving during it waits for the draw to finish; in `--logic=main` the
answer is produced inside the pass that took the click, before it draws. So on a slow machine single-thread mode is
the faster one for a click, which is what it was meant for. A wake from a parked loop is unchanged at 0.6 ms. Animation and idle are unchanged: draws at ~57 a second, five idle
refreshes a second, 0.8–2.7 % CPU. Held by `LoopCountersTest.aWakeInsideTheCeilingsGapIsTakenAtOnceAndOnlyItsDrawWaitsForTheSlot`
(a wake 50 ms into a 400 ms gap runs its pass at once; its draw waits for the slot).

**Found while measuring it, and not caused by it:** presenting to a background (cloaked) window sometimes blocks for
100 to 360 ms, inside the presenter, after the tree update. The old loop shows the same on the same probe (worst wakes of
245 and 143 ms against the new loop's 153). It is what the p95 outliers in the table are, and it is vexelray-gui's
presenter's to explain: a background run only, so far.

---

## 3. Evidence: what the current shape costs

From the 2026-10-26 audit, and from the template's in-progress move to messages (uncommitted in the main checkout).
Each item names what in §2 removes it.

| Where | What it does | Removed by |
| --- | --- | --- |
| `Lanes.java:81` | Handler lane is `newCachedThreadPool`, passed to every `Gui` | Fork 2, §4.2 |
| `vexelray-gui` `Gui.java:411` | Clicks, keys and drags run handlers on a worker pool | §4.2 |
| `Window.java` | Nine `synchronized(this)` blocks, `CopyOnWriteArrayList` listeners, `onOpened` on the main thread | Fork 4, §4.4 |
| `CloseGate`, `Dialogs`, `NativeDialogs`, `SlowFramesPrompt` | Answers delivered as `Runnable`/`Consumer`, "from any thread" | §4.4 |
| `HeldDialogs` | A lock-guarded queue of `Consumer<Dialogs>` doing a mailbox's job | §4.4 |
| `AboutIntro` | Eight volatiles, a `synchronized load`, and a virtual thread outside the lanes | Fork 4, §4.4 |
| `UndoFeedback`, `PointerLock`, `TactrollerInputBackend` | Consumer sinks from the GUI | §4.2 |
| `Generator.java:863` | `@BeforeFrame` becomes `hooks().add(stage, value::method)` | Fork 4 |
| Processor | A mutable `@Provides` value can be shared across lanes; `@Publishes` optional; T2.5 unenforced for raw executors | Forks 5, §4.3 |
| Template `QuitGate` | Keeps the close gate's two callbacks in a `ConcurrentHashMap` under a ticket, because a callback cannot cross a lane | §4.4 |
| Template `View` | `ws.onEdited`, `tabs.onSelect` ("delivered on a worker"), menu `Runnable`s, `gui.shortcut(Key, Runnable)` | §4.2 |
| Template `Messages.Closing` | Batch id, count, and the view's own `dirty` flag, because mailboxes are not ordered with each other | Forks 2, 3, 6 |
| Template `Editor.SET`, `View.SET` | Capacity 4096 so a per-tab burst does not end the app | Forks 1, 6 |
| Witnesses `Bench`, `Meter` | Shared mutable objects written by several lanes, with a `volatile Runnable changed` | §4.3 |

The template's own javadoc states the problem: *"a callback cannot be sent to another lane: it would carry whatever it
closes over."* The redesign takes that sentence as the rule for the framework's own API too.

### What is infrastructure, not an offence

Some concurrency is the bus's own machinery and stays: atchung's mailboxes and `Pump`, the tick scheduler's barrier,
the job lane, the watchdog (it must stand outside what it watches), the automation `Driver`'s socket threads, and
`WakeSource`. They live in the framework, are written once, and are never in an application's code.

---

## 4. Work, in order

Each phase ends with its repos building and its tests green. The apps are not built until phase 8.

### Phase 0. Set up (this commit)

* Clones side by side, apart from the main checkouts: `vexelray-framework`, `vexelray-gui`, `atchung`, each on a `v0.2` branch from `develop`.
* Versions: `vexelray-framework` and `vexelray-gui` to `0.2.0-SNAPSHOT`. **Why:** every repo installs into the same
  `~/.m2`. Without the bump the first `mvn install` here replaces the jars the other agent and every app build against.
* **Known break from the bump:** the template's `vexelrayVersion` slot (`template.manifest`) is used for both
  `vexelray` and `vexelray-gui`, which now differ (0.1.3 and 0.2.0). A generated project will resolve the wrong gui
  until the template gets a slot of its own for the gui's version. Deferred to phase 6, because the acceptance loop is
  not expected to pass before then.
* `vexelray-gui` builds its own icon plugin and uses it in its own reactor, so after a version bump that plugin has to
  be installed first (`mvn install -pl vexelray-gui-icons-maven-plugin -am`). Done once here at 0.2.0, which touches
  nothing at 0.1.3.
* `atchung` is cloned but not bumped until phase 2 changes it. When it is, it becomes `2.0.0-SNAPSHOT`, and the
  framework's and gui's `dependencyManagement` pin it, so `tactroller`, `kronometer` and `vexelray` (all built against
  1.3) resolve to it. Their use of atchung is publish/subscribe, which phase 2 keeps.
* **Bring in the other agent's work.** `@Job`, the processor changes and the template's `-core`/`-gui` split are
  uncommitted in the main checkout and are not in these clones. Once they are committed on `develop`, fetch and merge
  them into `v0.2` before phase 3. They are the right first step, and the redesign builds on them rather than
  redoing them. `vexelray-gui` also has six uncommitted files there; the same applies.
* **Added 2026-10-29: `vexelray-codec`**, cloned beside the others on a `v0.2` branch from `develop` (`4940a1e`), at
  `0.2.0-SNAPSHOT` for the same reason (Pix and Vexplore build against its `0.1.3-SNAPSHOT`). Its stack properties
  are spelled with dots (`vexelray.gui.version`), unlike the apps'. Here so it compiles against every change to
  `Seams` as a widget library outside vexelray-gui, and because the frame contract's codec step lands in it. See
  phase 8.

### Phase 1. Rulings

*Drafted 2026-10-26 on `v0.2`: `docs/threading.md` rewritten; notes added to `components.md` (rulings 5 and 9),
`architecture.md` (*The message model (0.2)*) and `v1.md` (the liveness guarantee). **Approved 2026-10-26**, with
the six decisions it derived from the rulings (T2.3 tightened, edges never dropped and `BLOCK` withdrawn, rounds and
their tie-break, every part with behaviour a component, T2.7, futures in T7.2).*

Rewrite `docs/threading.md` as the message model (§1), with forks 1 to 6 as rulings, each with its reason (§2), and
the reading of fork 3's amendment confirmed or corrected by the author. Mark the T-rules that go (T1.1's handler lane, T5.1's main-thread door) and the ones that
change. `components.md` rulings 5 (mailbox overflow) and 9 (`@Subscribe` only on components) are revisited here.
**Nothing below starts until this is approved.**

### Phase 2. atchung: the tick

*Built 2026-10-26 on atchung's `v0.2`. What it is: `Topic.state`/`Topic.edge` (with `Topic.of` an edge, so every
existing caller is unchanged), `Tick`, `Inbox`, `Sender`, `TickLoop`, and `TickTest` (20 tests: ordering, folding,
key filters, overflow, the deadline and carry-over, loops, failure, hand-over to subscribers outside the tick, and
no allocation per message once warm). What changed from the plan below:*

* *atchung is **1.4-SNAPSHOT**, not 2.0: everything is additive, and tactroller, kronometer and the engine compile
  against it unchanged. vexelray-gui's `atchung-probe` was pinned to a literal 1.3 and now follows
  `atchung.version`.*
* *No global sequence number. Order comes from rounds and from each component's own outbox, emptied in wiring order
  at a round's end, which gives the same order on any number of threads with nothing sorted.*
* *The hop limit counts per message, not rounds per tick, because a deadline that cuts every tick short would
  otherwise hide a loop.*
* *A tick's sends to anything outside it (a `Pump`, an inline subscriber) are handed over when the tick ends, in
  order. **For phase 3:** `Gui`'s pump can still drain partway through that hand-over and draw half of a tick's
  writes for one frame. The fix belongs where the frame drains: a tick boundary the GUI drains up to.*
* *atchung's bus registry is now an immutable array per topic, swapped on subscribe and unsubscribe. A publish no
  longer allocates an iterator, anywhere in the stack, and a fan-out can no longer see a registration set change
  underneath it.*

> ***Rule, 2026-10-27: atchung's existing behaviour does not change.*** *Refactoring and additions are fine; what
> the existing API does is not, because tactroller has been tuned against it since day one. Phase 2 had changed
> three behaviours, all now restored: `Topic.toString` (back to the record's text), a topic whose last subscriber
> left (it keeps an empty registration again, so a publish to it is not counted as having no subscriber), and a new
> throw from every `subscribe` over a topic's kind (that check now lives in `Inbox`, between tick subscriptions
> only). Each atchung change is checked by running **tactroller's own suite** against it in a throwaway clone:
> 88 tests, 0 failures on 1.3 and on 1.4.*

* A **tick scheduler**: a `Pump` variant that drains a set of mailboxes once per call, in global publish order, until
  they are empty or a deadline passed in by the caller arrives, carrying the rest over in order, with a hop limit as
  the loop detector (forks 2, 3). It knows nothing of frames or threads: the caller says when and with what deadline,
  which is what lets the framework run it on either thread (fork 4).
* A global publish sequence number, stamped at `publish`, so delivery order is chronological across topics.
* Topic kind (`STATE` with a key, `EDGE`) on `Topic`, and folding by key for `STATE` (generalising `Fold`).
* Tests: ordering across two senders, folding per key, quiescence, carry-over at the deadline keeping order, the hop
  limit's failure, no allocation per
  delivery on the drain path (same rule as `FrameHooks.run`).

### Phase 3. vexelray-gui: widgets publish, the handler lane goes

*Prototype built 2026-10-27 on vexelray-gui's `v0.2`: the text field. `TextFieldMessagesTest` (5 tests) holds it.*

* *Typing, the caret, selection and undo were already the field's own, as ordered stages on the GUI thread; only
  its notifications went to the handler pool. So T3.9 cost nothing to keep.*
* *What leaves: `TextField.CHANGED` (a state topic keyed by the field's name, carrying text and document version)
  and `TextField.SUBMITTED` (an edge). Five keystrokes in one frame reach a component as one message. A field
  nobody gave a callback to now sends nothing to the handler lane.*
* *What arrives: `TextField.COMMANDS`, records that each apply themselves (`SetText`, `Replace`, `Insert`,
  `Select`, `ReadOnly`), drained on the GUI thread through a new seam, `Gui.mailbox`, which uses the frame's own
  pump, after input dispatch and before layout.*
* ***Last write wins, between a person and the logic:*** *a `Replace` names the version it was computed from. If
  the person typed since, it is dropped and the field announces what it says now, so the logic recomputes. A
  keystroke is never overwritten by a completion of text the person had moved past.*
* *A field is addressed by a name, unique on its bus. Every field hears every command and keeps its own: O(fields)
  per command. That gets replaced by a router keyed by name, one mailbox per tree, before more widgets are done.*

**The question the prototype raises, to rule before the other widgets:** where does *view* code run? Something has
to turn "the editor opened a file" into "add a tab holding a new text field", and that touches widgets. Two shapes:

1. ***Everything is commands.*** *Each widget library type defines a command vocabulary, including creating
   children (`Tabs.Add`), and all application code stays on the logic lane. T7.1 holds as written. Cost: every
   dynamic UI an application has becomes a protocol in the widget library's vocabulary, and building a widget
   from logic means describing it in data.*
2. ***View components, run on the GUI thread.*** *An application's own GUI element (a tab with an editor in it)
   is a component whose mailbox drains in the frame, like a widget's. It may hold widgets and send and receive
   messages, and nothing else: no `@Job`, no blocking service, checked by the processor, with the watchdog
   watching its time in the frame. T7.1 gains a second named exception beside drawing: presentation.
   Recommended: it is the author's own model ("GUI elements ... each get a mailbox ... drained every frame"), and
   it keeps the widget library's API the size it is.*

***Ruled 2026-10-27: view components on the GUI thread*** *(threading.md T7.4): presentation is T7.1's second
exception. The widgets' tick is where view components' inboxes will live, so a view hears a widget's event in the
frame the input arrived (input dispatch, then the widget tick, then the mutation drain).*

***Built 2026-10-27, in order:***

1. *Routing by address: `Gui.mailbox(topic, address, stage)` and `Addressed`. One subscription per topic and a
   route table, so a widget is handed only its own messages. `TextField`'s commands route by its name.*
2. ***The handler lane is gone.*** *Handlers run on the GUI thread during dispatch, with the same wake and
   frame-owed accounting. `Gui`'s executor constructors, `async()`, `offload()` and
   `GuiApp.offload(work, landed, failed)` are removed. A `Gui` owns no threads (`GuiThreadsTest`). The harness has
   one mode, the production one. 1,868 gui tests, 0 failures; the framework clone builds and passes on it.*

3. ***Declarations (T7.5, ruled 2026-10-27).*** *What the dispatcher asks during dispatch, the application declares
   on the node ahead of time, and only the outcome leaves as a message. Built: `Menu` (a value built only from
   sends: `item(label, sender, message)`, no form that takes code) and `Gui.menu(node, menu)`, which replaces rather
   than accumulates, follows the nearest-node rule, and comes after a widget's own items; `Gui.shortcut(chord,
   sender, message)`; `Gui.claim(node, chord, scope, sender, message)`; `Gui.draggable(node, payload)`; and the
   keymap's `Action.sends(sender, message)`. `DeclarationsTest` (7) and a `KeymapTest` case hold them. The
   function-valued forms (`onContextMenu`'s source, `onDrop`, `onDragSource`, `Runnable` shortcuts and claims,
   `Action.on`) remain, as the widget library's; moving them where a view cannot reach them is owed with T7.2's API
   walk. A declared drop zone is not built: no witness drops onto anything but a widget library container
   (`TreeView.reorderable`, `Board`), and a feature with no witness is invented rather than needed.*

4. ***Node events as messages.*** *`Gui.hear(node, NodeEvent.KIND, stage)`: the dispatcher publishes the event on
   the tree's own topic for the kind (`vexelray.gui.treeN.node.click`, so two trees on one bus never hear each
   other's node ids, T4.6), addressed to the node and routed in the widget tick, in the frame the input arrived.
   Kinds: `CLICK`, `DOUBLE_CLICK`, `CONTEXT_CLICK`, `DRAG`, `WHEEL`, `KEY`, `CHAR` (edges) and `STATE`, `RESIZE`
   (state, last per node wins). Hearing a node is what makes it a click target or focusable. Hearing a kind a
   widget already handles on its node is refused: the widget says what happened on topics of its own. Released
   with the node. `HearTest` (7) holds it, including three hover crossings in one frame heard as one.*
   * *The dispatch-time registrations (`onClick`, `onState`, `onKeyUi` and the rest) stay, as the widget
     library's: a button's press, a caret and focus must move in order with the input around them, which a
     delivery after dispatch cannot do. Like T7.5's function seams, moving them where a view cannot reach them is
     owed with T7.2's API walk.*
   * *A size is heard in the frame after the layout that produced it. That is no regression (`onResize` ran on the
     pool before), but a view that redraws from its size shows one frame at the old one. If a witness needs it, the
     frame can run the widget tick again after layout when a size was published, and lay out once more.*

5. ***`TreeView`'s children as request and reply.*** *`TreeView.Source` is gone. A tree is built with a name
   (`new TreeView<>(gui, "files")`), asks for its roots then, and asks for a level the first time it opens or a walk
   goes through it: `ChildrenWanted(tree, parent)` on `TreeView.CHILDREN_WANTED`, answered by `Children(tree,
   parent, entries)` on `TreeView.CHILDREN`, routed to the tree by name and applied in a frame. What a row is
   drawn from comes in the answer as data, `Entry(item, label, icon, branch, acceptsChildren)`, so nothing of the
   application's runs to list a level or draw a row (T7.2, T7.5). Expand-all, find and `revealPath` are walks that
   wait on a level's answer and go on from it when it lands, a level per frame; a superseded walk asks for nothing
   more. `refresh()` asks for every listed level and applies them together when the last lands, so a row moved
   between two open folders is reused whichever answer comes first. An answer nobody asked for is dropped and
   counted. `TreeChildrenTest` (6) holds the contract; the existing tree tests (101) pass through a test-side
   `Listing` that answers inline, with a frame per level where a walk is involved.*
   * *`expand(root)` straight after building the tree waits for the roots, as the walks do, because a board that
     opens its columns at start (the demo's) otherwise opens nothing.*
   * *Answering is the application's, on whatever thread its logic is: the demo lists the disk on a thread of its
     own and answers an in-memory board inline. In the framework that is a `@Job`'s, once it is merged in. The
     template's `Navigator` and `FolderSource` still use `Source` and are left for that merge, which rewrites them.*
   * *A capture plays animation to rest but does not wait for answers it has not had: a capture of a tree whose
     listing runs on another thread can be taken before the listing lands. Belongs with the shell's "what is
     owed" in phase 5.*
   * *`matcher`, `onContextMenu` and `Action`'s body and predicates became declarations and `CHOSEN` in step 13.
     The tree's `onSelect`, `onClick`, `onHover` and `onActivate`
     became messages in step 6.*

6. ***Widgets' callbacks become topics.*** *Every widget says what it did on topics of its own, carrying its
   address: `Button.PRESSED` (an `IconButton`'s too) and `TOGGLED`, `Toggle.CHANGED`, `Slider.CHANGED` and
   `COMMITTED`, `NumberField.CHANGED`, `ColorPicker.CHANGED` and `COMMITTED`, `Segment.CHANGED`, `Select.CHANGED` and
   `COMMITTED`, `ListView.SELECTED`, `CLICKED` and `ACTIVATED`, `Table.SELECTED` and `SORTED`, `Tabs.SELECTED` and
   `REMOVED`, `TreeView.SELECTED`, `CLICKED`, `ACTIVATED` and `HOVERED`, `Breadcrumb.NAVIGATED`, `Rail.SELECTED`,
   `SplitPane.RESIZED`, and `TextField.LINK_FOLLOWED` beside its `CHANGED` and `SUBMITTED`. A value is a state topic
   (the last in a frame), an act an edge (every one). A view hears one with `gui.mailbox(topic, widget.address(),
   stage)`, in the frame it happened. `WidgetAddressTest` (3) holds the address; the suite (1,892) passes.*
   * *Ruled with the author: **an address is tree-qualified**. A `Gui` has a name (`new Gui(bus, "main")`, default
     `tree<n>`) and a widget's address is `main.path`: a name need only be unique in its tree, and two windows'
     "path" fields are two addresses. Every widget has `name(String)` and `address()`; `IconButton`'s accessible-name
     setter became `label(String)`, as `Button`'s is, to free `name`. `TreeView`'s requests carry its address too.*
   * *Ruled with the author: **a composite hears its parts through a library-only hook**, package-private, in the
     dispatch: `FindBar` its field, `ColorPicker` its sliders, `Select` its list, `Property` its controls. The hooks no
     composite used were deleted rather than kept. `KeymapEditor`, in its own module, hears its field and buttons by
     mailbox, as a view does.*
   * *`SelectionModel`'s listeners are hooks now; a selection is heard from the widget showing it.*
   * *`Tabs.REMOVED` is a message where `onRemove` was inline: the index is the tab's when it went, and a view that
     applies them in order (tabs are only appended) stays in step with the bar, and replaces the last tab in the
     widget tick of the frame it went, so no frame is drawn empty.*
   * *A `Sender` form beside each remaining `Runnable` action: `Modal.button`/`defaultButton`/`cancelButton`,
     `Modals.confirm`, `Rail.action`, `TitleBar.onMark`. The `Runnable` forms are the host's (a close request's
     `proceed`, the framework's About) and leave a view's reach with T7.2's walk, with `Button.perform`.*
   * *`GuiApp`'s window key and the `Gui`'s name are two names for one window. The shell should build each `Gui` with
     the window's name; phase 5.*
   * *`Inspector.card`'s toggle and `Property`'s get/set bindings became `VALUES` and `EDITED` in step 15.*

***Still to do in phase 3, the inventory:***

* *~~Widgets' app-facing callbacks become topics~~: done as step 6. `TextField.onContextMenu` and the other
  menu sources became declarations in steps 13 and 14.*
* *~~`TreeView`'s source~~: done as step 5.*
* *~~`Gui`'s 26 node-level input APIs~~: done as steps 3 and 4. Events are heard as messages (`Gui.hear`); the
  dispatcher's questions are declarations (`Gui.menu`, `shortcut`/`claim` with a message, `draggable`). Left: the
  `onUndo`, `onCursorChange`, `onPointerLock` and `onPointerWarp` sinks, which are the host's seams (`GuiApp`, the
  automation driver, the framework's undo feedback) rather than a node's, and go with the shell in phase 5.*
* *~~Transitional leftovers~~: done as step 7. `Gui.handlers()`, the executor on `InputDispatcher`, `ReadModels`
  and `Undoing`, `CloseGate.keepAlive`'s executor, `Keymap.Action.ordered()`, `Gui.awaitHandlers` and capture's
  wait on it are gone. A handler runs inline in the dispatch; `dispatch` says whether one ran, and the frame wakes
  the loop for the next before it pays (`Owed` is two stages now, wake and frame). A close request's handler runs
  in the frame and must not block; making it a message is still phase 5's.*
* *T7.2's walk, begun as step 10: the widget library's and the host's seams leave `Gui`.* Ruled with the author:
  the rule is about which thread runs the code (a subscription is not a callback); a view keeps display functions
  under two conditions; the library's seams go to an spi type. `onClick`, `onDoubleClick`, `onContextClick`,
  `onContextMenu`, `openMenu`, `menus`, `onState`, `onDrag`, `onWheel`, `onDrop`, `dropTargetsAt`,
  `onDragSource`, `watchDrag`, `onResize`, `onKey`, `onChar`, `reveals`, `shortcut`/`claim` with a `Runnable`,
  `claimAny`, `onUndo`, `onCursorChange`, `onPointerLock`, `onPointerWarp`, `onWork` and `frame` are on
  `dev.vexelray.gui.core.spi.Seams` (`Seams.of(gui)`, an inner class of `Gui`, installed once). `GuiSurfaceTest`
  holds `Gui`'s side. Every caller in vexelray-gui and the shell moved; both suites pass (1,897 and 320).
  * *Step 11, a tree's ticks as messages.* `TreeView.Checkable` and `recheck()` are gone. `checkable()` shows the
    boxes; a click on one, or Space on the selected row, selects the row and publishes `Toggled(tree, item)` on
    `TreeView.TOGGLED`; the application answers with `Marks(tree, {item → Check})` on `TreeView.MARKS`, naming every
    box it changed (a cascade is one message), routed to the tree by name and drawn in the frame it lands. The tree
    keeps the last mark for every item named, row or not, so a level and its marks arrive in either order and a row is
    never built empty that was ticked. A click changes no box until the answer, a frame later: a box that ticked
    itself first is one the application could untick under the pointer. The demo stages files on its listing thread
    and answers there. `TreeCheckTest` (10) holds it.
  * *Step 12, placements as declarations and messages, ruled with the author.* `Reorder` is gone, and with it
    `Gui.dropHistory` and the transfer history. Three rulings, each with its reason:
    * *What a drop is offered is declared, and decided in the frame.* `TreeView.reorderable(MOVE[, COPY])`,
      `Deck.reorderable()`, `ListView.droppable(type, effect)`; the tree refuses from what it was told
      (`Entry.acceptsChildren`, the effects) and what it can see (onto or under itself, a move that leaves the item
      where it is). **Why:** the indicator follows the pointer every frame, so asking by message would draw it a
      frame behind (T7.5), and a function answering would be logic in the frame (T7.2). What only the model knows
      (a name taken in that folder) the application refuses when it hears the placement, and says why.
    * *A drop is announced, not applied.* `Placed(at, item, where, effect)` on `TreeView.PLACED` and `Deck.PLACED`,
      `ListView.Landed` on `ListView.LANDED`; a tree is told to look again with `TreeView.REFRESH`. A tree's row
      stays put a frame or two until the refresh lands and then moves; a board's card lands in its slot at once, as
      it always predicted, and the new list confirms it. **Why:** moving a tree row early and then back on a refusal
      would be a visible jump.
    * *Undo of a drop is the application's.* **Why:** the change is made in its model, so it is recorded in its
      history like any other edit. `Drop` carries a `Runnable` landing, run in the dispatch (the widget library's
      seam), and `DragSession.commit()` takes no history.
    * *A paste is offered on the same terms and announced the same way. `TreeReorderTest`, `TreeTransferTest`,
      `BoardTest`, `ListViewTransferTest` and the core's drag tests hold it; tests of undo through the GUI's
      history were dropped or now undo the test model's own. The demo's Cards and Drag chapters record placements
      in their own histories; the framework's `Window` binds the window history to the chords only.*
  * *Step 13, a tree's row menu as declarations and messages, ruled with the author.* `TreeView.Command`,
    `Action`'s body and `shownWhen`/`enabledWhen`, the tree's `onContextMenu`, `expandAction()`/`collapseAction()`
    and `matcher` are gone. Five rulings, each with its reason:
    * *An action is declared data and choosing it is announced.* `TreeView.Action.of(id, mark, label)`, its id
      unique in the tree and none of the tree's own; choosing it publishes `Chosen(tree, action, item)` on
      `TreeView.CHOSEN`. **Why:** a body run on the GUI thread at the click is logic in the frame (T7.2).
    * *What a row offers comes with its entry.* `Entry.hides(ids…)` and `greys(ids…)`; everything is shown and
      enabled by default, and a changed fact arrives with `REFRESH`. The tree's own lines have ids (`EXPAND`,
      `COLLAPSE`, `CUT`, `COPY`, `PASTE`) an entry can hide, and when they are enabled stays the tree's. **Why:** the
      listing already answers about each item on the logic's thread, and whether Delete applies to a file is the same
      kind of fact as `acceptsChildren`; a predicate at the right click would run the application's code in the frame
      (T7.5).
    * *The tree's free-form menu source goes; its lines are actions.* **Why:** it was a function asked at the click,
      and no user needed more than a line per command (the demo's Open, Copy path and Properties, the template's Copy
      path). Its submenus had no witness.
    * *`Job` is the tree's own.* Its walks still supersede each other, and choosing any line stops the one running;
      the application stops its own earlier work when it hears a later `Chosen`. **Why:** nothing witnessed the tree
      stopping application work: the demo's Count files ran its loop on the GUI thread inside the choice, so
      `job.live()` could never turn false while it ran. The demo's count now runs on a thread of its own and stops
      when a later line is chosen.
    * *`Entry.find(text)` is what Ctrl+F looks in, the label by default.* **Why:** the matcher was a function asked
      in the frame for every row the walk reached; the demo's need, the whole path, is a string the listing has. A
      match that is not a substring of one string is no longer possible, and nothing asked for one.
    * *`TreeActionsTest` (9), `TreeFindTest` and `TreeViewTest` hold it. `ListView`, `Tabs` and `TextField` followed
      in step 14. The template's `Navigator` is left for the `@Job` merge.*
  * *Step 14, the other widgets' menus as declarations and messages.* `ListView.onContextMenu`, `Tabs.TabMenu` and
    `onContextMenu`, and `TextField.onContextMenu` are gone. The tree's `Action` and `Chosen` became top-level, as
    `Placed` is: `MenuAction.of(id, mark, label)` and `Chosen(at, action, item)`, on each widget's own topic
    (`TreeView.CHOSEN`, `ListView.CHOSEN`, `Tabs.CHOSEN`, the last carrying the tab's index when the menu opened). The
    bar's own Close has the id `Tabs.CLOSE`, which an action may not take. Two rulings, each with its reason:
    * *A text field's lines are its node's declared menu* (`gui.menu(field.node(), Menu)`), which core already had and
      adds after the field's Copy, Cut and Paste behind a rule. **Why:** the view holds the field's node, so the
      declaration T7.5 describes fits as it is; a second, field-specific form would be two ways to say one thing.
    * *A list's and a tab bar's actions are on every item; no per-item hiding or greying yet.* **Why:** no witness
      asks for it (the tests' Delete and Open, the template's Duplicate), and a tree has it because its entries
      already carry per-item facts. A list's right click still selects first, and a menu on one of several selected
      rows is about all of them: the selection was on `ListView.SELECTED` before the menu opened.
    * *`DefaultMenuTest` and `ListViewTest` hold it. The template's `Actions.java` (a tab's menu) and `Navigator.java`
      still use the old forms, and are left for the `@Job` merge with the rest of the template.*
  * *Step 15, a button's edit and an inspector's bindings as messages, ruled with the author.* `Button.perform`,
    `Property`'s suppliers and consumers, `Inspector.refresh()` and the card's `BooleanSupplier`/`Consumer` are gone.
    Three rulings, each with its reason:
    * *A press that is an edit is recorded by the view that hears it.* `Button.PRESSED`, then
      `history.perform(change)` in the view's mailbox. **Why:** the supplier was code the button ran on the GUI thread
      at the press (T7.2), and the press is already a message. `MenuSink.edit` stays, as the widget library's.
    * *Undo of data the logic owns is a `Change` whose apply and revert each send it a message.* The history stays
      the window's, on the GUI thread, one stack for the widgets' values and the application's commands. **Why:** a
      second history in the logic would split undo into two stacks, and a `Change` applying logic's data on the GUI
      thread would reach into it from the frame. The cost: the history's conflict check cannot see the logic's state,
      so an undo the logic refuses is the logic's to report, as a refused drop is. `ValueWidgetUndoTest` holds it.
    * *A property is told its value and announces its edits.* Every row and card has an id; values arrive as
      `Values(inspector, {id → value})` on `Inspector.VALUES` and are shown, never echoed; edits leave as
      `Edited(inspector, property, value)` on `Inspector.EDITED`, a state topic keyed per row, so a drag is heard once
      a frame. A value told before its row is added is kept and shown when the row is built. An application's own
      `Property` still builds its editor (presentation, T7.4), now given an `Edits` sink, and `refresh()` became
      `show(value)`. The inspector has `name()`, `address()` and `close()` like the other widgets. **Why:** the
      supplier was read on the GUI thread on every refresh and the consumer wrote the model in the dispatch, both
      reaching logic's state from the frame. `InspectorTest` (24) and `SwatchClickTest` hold it.
    * *The fluid simulator's `ControlPanel` (`Property.choice` and `refresh()`) needs converting when it moves to
      0.2. A row is at its rest value (a range at its minimum, a choice with none chosen) until its value is told; a
      panel built in the frame its values are sent may show that for a frame, which phase 4's view tick should rule
      out by telling a view's values before its first frame.*
  * *Step 16, the GUI's read models leave `Gui`, ruled with the author.* `drag()`, `modifiers()`, `viewport()`,
    `zoom()`, `dpi()`, `layout()` and `semantics()`, each an atchung `State`, are on `Seams` now. Four rulings, each
    with its reason:
    * *A `State` is a seam.* **Why:** its `onCommit` listener runs on whichever thread commits, which for these is the
      GUI thread in the frame, so handing one to a view hands it a way to run code there (T7.2). Every subscriber was
      the widget library, the host, automation or the gallery, and moved with the widgets. `GuiSurfaceTest` refuses a
      public method on `Gui` that returns a `State`.
    * *`Gui` keeps plain reads.* `layoutSnapshot()`, `semanticSnapshot()` and `Node.layout()`. **Why:** reading an
      immutable snapshot runs as the caller, with nothing kept or called later.
    * *No topic for these yet.* When an application needs to hear the zoom, the modifiers or the window size, it gets a
      state topic on the tree's names, heard in its mailbox. **Why:** nothing in the framework, the template or the
      witnesses needs one. The Scheduler's `Recipes.shortcuts` reads `gui.zoom().value()` to glide the zoom itself,
      which it wrote before the framework glided the zoom keys (`WindowMotion` sets `Gui.zoomGlide`); it binds `zoomIn`
      and `zoomOut` when it moves to 0.2.
    * *A zoom or density write from another thread is applied at the top of the next frame.* `zoom(float)`,
      `zoomIn`, `zoomOut`, `resetZoom`, `zoomRange`, `dpi(float)` and the zoom glide's knob, once frames have begun.
      **Why:** the zoom's listeners restyle widgets (`TypesetBlock`, a status line), and a commit from a logic thread
      ran them there. On the GUI thread, or before the first frame, a write is at once, as before.
      `MinSizeAndZoomTest.aZoomFromAnotherThreadIsCommittedOnTheFrameThread` holds it.
  * *Step 17, the display functions' narrow shape, ruled with the author.* Six rulings, each with its reason:
    * *An item is drawn by a `Face<T>`, `(nodes, item) -> Node`, never with the `Gui` in hand.* `ListView`'s rows,
      `Table.Column`'s cells and `Board`'s cards; `ListView.RowBuilder` is gone. `Nodes` makes boxes, rows, columns,
      text and icons and reads the theme, and leads to no bus, mailbox, clipboard or seam. **Why:** a face runs
      whenever a row scrolls in, in the frame; with the `Gui` in hand it could publish, subscribe or read the
      clipboard there, which a processor reading the lambda could only partly catch, and the type stops outright.
    * *A control in a row is described, and its press is a message.* `nodes.button(icon, label, action, enabled)`
      draws an icon button whose press is `Chosen(list, action, item)` on `ListView.CHOSEN`. A table's cells and a
      board's cards refuse one until a witness asks what theirs announce. **Why:** the editor's `GitPane` puts
      stage and unstage on every row, and a widget built in a face would need the `Gui`. The cost: a row's controls
      are what `Nodes` can describe, a button for now.
    * *A page built on first showing is the view's, told by message.* `Rail.OPENED(rail, key)` the first time a
      panel shows, built into `rail.page(key)`; `Popout.POPPED(popout)` the first time a panel pops out, built into
      `awayBody()` against `away()`, and the docked copy built into `body()` by the view at once. `Rail.Page` and
      `Popout.Content` are gone, and `Popout` has `name()` and `address()`. **Why:** a builder the widget ran later
      was the view's code run in the frame. The view hears `OPENED` in the widget tick of the frame the panel opened,
      before layout, so a page is never drawn empty.
    * *Text from an item stays a function.* `Select`'s and `Breadcrumb`'s labels, `Select.summary`, a drag's
      label. **Why:** they take the item, return a string and hold no `Gui`: the narrow shape already, left to the
      `@View` lambda check.
    * *Marks are data.* `ListView.marks(Set)` replaces `marked(Predicate)` and `remark()`. **Why:** the predicate was
      asked on every paint and read a mutable set; Vexplore, the one user, keeps exactly such a set.
    * *A tooltip's text is data.* `Tooltip.attach(node, String)`, attached again to change it, on the one observer
      it has; the `Supplier` form is the widget library's (`IconButton.tip`, `ColorPicker`, `TitleBar`). **Why:** the
      supplier was asked at hover and read what it captured, and every witness passes a fixed string.
    * *`ListViewTest.aRowsButtonIsAnnouncedWithItsItem`, `aBoardsCardsRefuseAButton`,
      `TooltipTest.attachingAgainChangesWhatItSays` and the rail and popout tests hold it. `Tabs.HeaderSkin` stays
      (it paints a node from its state, with no `Gui` in hand). The motion hooks (`Ramp`, the tab and rail
      transitions, `Cue`, `DropIndicator`) go with `KronoGui`. The editor (`GitPane`, `GitStage`, `HistoryStage`,
      its `Navigator`'s breadcrumb), MainFrame (`WorkspacesPage`'s list and board) and Vexplore (its rail, marks
      and breadcrumb) convert when they move to 0.2.*
  * *Step 18, part 1: motion is a value, and `KronoGui` leaves a view's reach, ruled with the author.* A widget takes a
    `Timing` (vexelray-gui-core: a duration and a `Curve`, `LINEAR`, `OUT_CUBIC` or `IN_OUT_CUBIC`) where it took a
    `Ramp`: `motion(...)` on `TreeView`, `Board`, `SplitPane`, `Unclip`, `Inspector` and its cards and rows,
    `transition(...)` on `Toggle` and `Slider`, `Tabs.indicator`, `tabs.slide` and `crossfade`, `rail.fade` and
    `slide`, and `new Cues(gui, timing)` with `play(node, cue, timing)`. The widget runs it on the tree's `Clock`,
    which the host installs with `Seams.clock` and `KronoGui.attach` provides (and `close` takes back); with no clock a
    timing lands at once. `Ramp`, `Tabs.TabTransition` and `Rail.PanelTransition` are the widget library's now
    (package-private). The framework no longer provides `KronoGui` to an application part, and `WindowMotion`,
    `UndoFeedback` and the template's `Motion` say tempi. **Why:** a ramp was code a view handed a widget to run in
    the frame, and making one needed `KronoGui`, which schedules any code on the GUI thread; a tempo is all a view
    has to say. `KronoGuiTest` holds that every curve maps and that attaching installs the clock, `TabCrossfadeTest`
    that a crossfade runs on it, and the widget tests drive time through `TestClocks`.
    * *Still open from it:* `Shell.krono()` is public, because the automation driver (another module, the host's)
      reads quiescence from it, so an application that takes `Shell` can still reach the clock; it goes with the
      open item on what `Shell` hands over. And three witnesses used `KronoGui` for more than a widget's tempo:
      Vexplore's `Lens` (a custom-drawn lift), the editor's `View` (a git poll) and the Scheduler's `Motion.glide`
      (its zoom, which the framework now glides itself); part 6 settles the first two.
  * *Step 18, part 2: the `Runnable` action forms leave a view's reach, ruled with the author.* A `Modal`'s button is
    data now: a label and the message it sends (`button`, `defaultButton`, `cancelButton` with a `Sender`), or none
    for one that only puts the dialog away (`defaultButton(label)`, `cancelButton(label)`), readable as
    `Modal.Button.message()`; `Modals.confirm` keeps only its message form. `TitleBar.onMark` and `Rail.action` keep
    only theirs. `TreeView.revealPath(chain)` announces its end on `TreeView.REVEALED` as `Revealed(tree, item,
    reached)`. `KeymapEditor.onCancel` and `keymap.Action.on` are the keymap module's own (package-private). The
    host's code forms are on `dev.vexelray.gui.widget.spi.WidgetSeams` (a modal's buttons, `press`, the mark, a rail
    action), installed as `Seams` is, so the framework's shell (its `Dialogs.confirm`, the slow-frames prompt, About
    on the mark) and the demo's quit prompt and gallery dialogs use it. **Why:** each was code a widget ran on a press,
    in the frame; the host is the GUI's side and may, by the same door as the widget library's seams (step 10).
    `ModalTest`, `TreeRevealTest`, `DialogsTest` and `SlowFramesPromptTest` hold it.
    * *Still open from it:* the framework's `Dialogs` still takes callbacks from an application (`confirm`'s
      `Runnable`s, the file pickers' `Consumer<Path>`), which is the framework's own API walk. The template's
      `Actions.java` (a save prompt) and `Navigator.java` (a reveal's callback) still use the old forms; they are the
      files left for the `@Job` merge.
  * *Step 18, part 3: the widgets' `State`s leave a view's reach, as `Gui`'s did in step 16.* `History.status()` reads
    a `Status` now, and a history announced on a bus (`announceOn`, which `Gui.history` does for the window's, at
    `gui.address(History.ADDRESS)`) publishes `Changed(history, status)` on `History.STATUS`. `TextField.document()`
    is the widget library's, and a field announces its caret on `TextField.CARET` as `Caret(field, offset, line,
    column)`. `ColorHistory.colors()` and `Keymap.overrides()` are their packages' own. `ScrollSource` moved to
    `core.spi`, and a `Scroller`'s axes are reached by `Seams.of(gui).scrollSource(scroller, vertical)`; a view
    scrolls with `Scroller.scrollTo`. **Why:** a `State`'s listener runs on whoever commits it, in the frame. The
    template's `Buffer` (its dirty flag and caret) and `Workspace` are the witnesses for `STATUS` and `CARET`, and
    convert with the `@Job` merge. `HistoryTest`, `TextFieldKeysTest.theCaretIsAnnouncedByLineAndColumn` and the
    demo's text and undo chapters hold it.
  * *Step 18, part 4: `Gui.pointerWarp()` is on `Seams`, with its setter.* `GuiSurfaceTest.guiReturnsNoFunction` now
    refuses a public method on `Gui` that returns a function, beside the one that refuses taking one. **Why:** a
    returned function is the other half of a seam.
  * *Step 18, part 5: two noted and left, with their reasons.* `History.perform(Change)` runs a view's `Change` on the
    GUI thread when the user undoes: it is the view's own code (T7.4 allows a view on the GUI thread), and step 15
    rules that a change to logic's data sends messages, so nothing of the logic's runs there; but it is code the GUI
    keeps and runs later, and it is recorded rather than hidden. `Payload.lazily(Supplier)` is used only by the widget
    library's drag sources, which are behind `Seams` already.
  * *Step 18, part 6: a view's own motion is a `Tween`, and time is a message source, ruled with the author.*
    vexelray-gui-core's `Tween.of(gui, name, timing)` is a position from rest (0) to arrived (1): `toward(target)`
    eases there from wherever it is, in the part of the timing the distance left needs, on the tree's `Clock`, and
    announces each step on `Tween.VALUE` (a state topic keyed by its address); an overtaken ramp announces nothing,
    and with no clock it lands at once. The framework's `-core` has a `Timer` (`after` and `every`, each publishing
    a message on a topic, cancelled by the `Scheduled` it returns), one per application on a daemon thread of its
    own, handed to any part as a root from `CONFIG` and closed when shutdown begins. A period runs from the end of
    one publish, so a stall is followed by one beat and not by the missed ones. For it `-core` now takes
    `atchung-core` (the author's ruling, 2026-10-28; `-api` stays JDK-only). **Why:** Vexplore's `Lens` eased a
    custom-drawn lift with `krono.ramp`, whose lambda was the view's code run in the frame; with a tween the code
    in the frame is the library's, the view redraws in its own mailbox, and the generation counter it kept by hand
    is the tween's. The editor's `View` polled git with `krono.every`, view code on the GUI's timeline for work that
    is not the GUI's, and a named loop that kept the window from ever idling, or automation from seeing it settle,
    while Git mode was up; a timer sending `GitTick` puts the poll beside the `Repository` that answers it and lets
    the loop park between beats. `-core` takes the bus because the `Timer` names a `Topic` and an application's own
    `-core` module (where the `Repository` is) reaches the framework's `-core` and never `-shell`; atchung is
    JDK-only, so the container is still tested with no GPU. The clock steps in `CLOCK`, before `Gui.frame` drains
    the widget tick, so a tween's value is drawn in the frame that computed it. `TweenTest` and `TimerTest` hold
    them, and `FrameworkTableTest` the root.
    * *Converts with the witnesses:* Vexplore's `Lens` takes a `Tween` and its mailbox, and the editor's
      `Repository` takes the `Timer`, starting the beat on `GitWatch(true)` and cancelling it on `false`, so
      `GIT_TICK` is its own topic and the `View` only sends `GitWatch`. `vex-suite-common`'s ramps (the picture
      viewer's fades, the filmstrip's step) take tweens, and its and the Inspector picker's `krono.after` and
      `krono.every` take the timer. The Scheduler's `Motion.glide` is its zoom, which the framework glides itself.
  * *Transactions and chains, direction ruled with the author 2026-10-28:* a declared function sent to the state
    that runs it, `(state, args, outbox) → state`, and a chain of them from a cause to its outcome, written once at the
    cause. The plan is vexelray-framework's [docs/plans/chains.md](docs/plans/chains.md). Its three
    rules: a step is a declared function, never a closure (the processor reads the body); a step returns an event and
    does not wait for what the event leads to; a step belongs to the module that owns its state. **The API walk waits
    on it**: the walk's draft found 287 entries over 413 types (`walk.log`), and which are owed where depends on this,
    since `History`/`Change` become snapshots and part of the bus's callback group becomes transactions. *Done means*
    2 gains the checked step as its one exception. **Step 2 built 2026-10-28** as atchung's `Ledger` (`9ff6e18`), and
    rule 4 added: the check never refuses safe code. **Step 4 built 2026-10-28** as atchung's `Chain`, with branches (a link's alternatives, tried as `catch` clauses are). **Step 5 built 2026-10-28** as atchung's `Undo`: a cause's changes put back on every
    ledger at once, by the tick's coordinator between rounds. **Step 3 built 2026-10-28** as the processor's `StepCheck`,
    and moved the same day to read after javac's analysis (reading during processing broke every compact constructor).
    **Step 6 built for the controller 2026-10-28** in the template's `-core`; the view's half and undo wait for phase 4
    (see phase 6).
  * *Still to do in the walk:* the framework's API-walk test over all of it, which the parts of step 18 give its
    definition of what a view can reach (`Gui`, the widgets and their values, never an `spi` package); and the
    framework's own callbacks (`Dialogs`, `Shell.krono()`). The processor checks (no `spi` import in a `@View`, the
    lambda body check) come with `@View`.
  * *The demo uses `Seams` in its chapters, as gallery code that builds widgets of its own; the framework's
    `custom-view.md` is still written in 0.1's terms (a `Consumer` pick, `show` on the committing thread) and is
    rewritten with `@View` in phase 4.*
* *~~The duplicate registration pairs~~: done as step 9. `onKeyUi`, `onCharUi`, `onDragUi`, `claimUi` and
  `onResizeUi` are gone into `onKey`, `onChar`, `onDrag`, `claim` and `onResize`, and the dispatcher's stage tables
  and a claim's `inline` flag with them: one registration per node and kind, run in the dispatch. They were not
  quite twins: a node could hold a stage and a handler of one kind, both run. Only `NumberField` did (its arrows
  beside its `TextField`'s keys); it now hears its field's keys through a package-private `TextField.keys` hook, as a
  composite hears its parts, and keeps hold-to-repeat, which a claim would have lost. Every claim now wakes the
  loop after it runs, the framework's own Tab included: a frame per Tab that finds nothing to do. Which of these a
  view may reach at all is still T7.2's.*
* *~~T4.6, topics named per tree~~: done as step 8, and held. A `Gui`'s mutations, clicks, focus and key routes
  are `vexelray.gui.<tree>.*`; its device input and gestures are `gui.input()` and `gui.gestures()`
  (`InputTopics.input(tree)`), and the static `InputTopics.INPUT` and `GESTURE` are gone. A second open tree of one
  name on one bus is refused at construction, and closing gives the name back. Navigation stays shared on purpose.
  `SharedBusTest` (3) holds it.*
  * *An input source publishes on its window's topic. tactroller is unchanged: the demo and the shell use
    tactroller-atchung's `InputPublisher(bus, gui.input().name())` in place of `TactrollerInputBridge`, which only
    knows the default topic. `Cursor` and `KronoInput.on` take the `Gui` rather than its bus.*
  * *Rebuilding the framework found `About` still on `IconButton.onPress` from step 6, and calling the old accessible
    `name(...)`: both fixed (the mailbox, and `label(...)`). The framework's api, core, processor and shell suites
    pass (320) against this vexelray-gui.*
  * *Not yet exercised: two windows really sharing one bus in a running application. `GuiApp` and the shell still
    give each window its own; putting them on one, with the window key as the tree's name, is phase 5's.*

***The torn frame, fixed 2026-10-27.*** *A tick's sends reached the GUI one message at a time, and a frame could
apply part of them: half of one logic tick's writes on screen for a frame, which is the visual discontinuity the
author has ruled out. Fixed in new code only:*

* *A tick hands another tick its whole share in one step, under that tick's lock (`Tick.stageShare`), so the
  receiving tick runs on all of one tick's sends or none. `TickTest.anotherTickReceivesEachTickOfSendsWholeNeverPartOfOne`
  races a frame thread against 2,000 logic ticks of 32 writes. With the share disabled it saw 45 torn frames; with
  it, none.*
* *The widgets' mailboxes (`Gui.mailbox`) are a tick of the GUI's own, one inbox for every widget, run at the start
  of each frame's drain. Last write wins per topic, as each topic's kind says. Input and the tree's mutations stay
  on the `Pump`, exactly as before. `TextFieldMessagesTest.whatOneLogicTickWritesAppearsInOneFrame` holds it.*

* Every input-shaped API on `Gui` and the widgets (`onClick`, `onSelect`, `onChange`, `onContextMenu`, `shortcut`,
  `onUndo`, `onPointerLock`) becomes a topic the widget publishes on. A shortcut binds a chord to a message, not a
  `Runnable`. A context menu's items are messages.
* Remove the handler executor, `Gui.async` and `Gui.offload`. The constructor takes no executor.
* Widget writes from a component arrive through the tree's folded sink, drained at frame start, as now. Writes from
  anywhere else (a raw thread) are a startup or runtime error, not quietly accepted.
* Widget-local state (caret, selection, hover, scroll) stays inside the widget and needs no message.
* This is the largest phase. **Its risk is the text field**: typing must stay inside the widget, and only the
  committed text crosses. Prototype that first.

### Phase 4. Framework core and processor

* Replace `Lanes`' handler pool and `Placement`'s per-component threads with the tick: the framework decides when a
  tick runs and its deadline (fork 3), the logic lane runs it, and a publish wakes the loop. The deadline policy is the
  framework's, beside `Pacing`, and is measured in phase 7.
  *Begun 2026-10-28 for a component that takes its `Inbox`, which the template's `Editor` needed: the shell's
  `Logic` owns one `Tick` and its thread, `vexel-logic`, parks until atchung stages an arrival, runs a tick with an
  8 ms deadline (a placeholder for the policy beside `Pacing`), and wakes the frame loop after a tick that delivered.
  The generated wiring hands such a component its inbox (`Inboxes.of(shell, name)`), in `CONFIG`; the processor
  refuses an inbox anywhere but a component's constructor, and a component that takes one and also declares a
  `@Subscribe`. Held by `LogicTest` and `VexelProcessorTest`'s inbox cases. Still to do here: a component with no
  lane and no inbox is still placed on a thread of its own, so the logic lane is not yet the default; and the
  handler pool remains.*
  *The same day, for the template's view: **`@View`** (T7.4: a view's `@Subscribe` methods drained on the GUI thread
  in the widget tick, built no earlier than `TREE`, held by nothing but another view, its widget messages not
  crossings); **`@Hears`** (what a component hears through its inbox, which the processor cannot read from a
  constructor: the channel checks count it, and the logic lane refuses to start when the inbox heard otherwise); and
  **`Senders`**, a framework service giving a sender for any topic, which a view's chords, menus and dialog buttons
  name. `Senders` is interim: fork 5's senders per declared topic replace it. Also `VexelApplication.frame(shell)`,
  one headless frame, so a test of a view can step the frames it hears in.*
* **Single-thread mode** (fork 4): a launch setting that runs the tick on the main thread, with or without a window.
  Tested by running the same witness in both modes and comparing their message logs, which must be identical.
  **`vexelray-mini` is the witness for the mode's purpose**: it exists to make VexelRay usable on low-end and
  CPU-only machines, so the mode is judged there, on such a machine, not only on the development one.
* `Outbox<T>` (fork 5): injected per topic, built by the generated wiring. The raw `Atchung` and every `Executor`
  become non-injectable into application parts.
* New processor checks, all from declarations:
  * a provided value injected into a component must be deeply immutable or a listed framework service;
  * a component's fields may not be `volatile`, `Atomic*`, `java.util.concurrent.*` or a functional interface type;
  * every topic declares its kind;
  * `@BeforeFrame`, `@MainThread` and `FrameStage.APP` are refused in application code.
* `@Subscribe` with a filter: the author's "subscribe to any desired event streams with a filter". Proposed shape: a
  key filter for `STATE` topics (`@Subscribe(topic, key = ...)`), resolved by the wiring, not a predicate lambda.
* An API test in the framework's own build: no public type in `-api`, `-core` or `-shell` reachable by an application
  takes or returns a `Runnable`, `Consumer`, `Supplier`, `Function` or `Executor`.

***Done 2026-10-29*** *on vexelray-framework's `v0.2` (uncommitted), with one addition to vexelray-gui's: the
framework's suites pass (core 64, processor 148, shell 167, automation 14, template 61), and an application generated
from the template builds (`-core` 55, `-gui` 17) and was driven with ottermate in both modes: the session restored, a
folder expanded through its job, a file opened from the navigator.*

* *Every component is on the logic lane's tick. `@Subscribe` methods are subscriptions on an inbox the wiring makes
  for the component (`Inboxes.mailbox`), so the logic lane is the default. A declared lane is a lane of the same
  tick, whose share of each round a thread of its own delivers (`Crew`), meeting the others at the round's end.
  `Placement`, `Placements` and the handler pool are gone, and `Lanes` mints the logic lane's, the declared lanes'
  and the job lane's threads (`vexel-job-<n>`, T1.2) and no others. The native dialogs answer on a thread of their own
  until phase 5. Each lane's share is watched by its own gauge, so a wedged declared lane is reported by name.*
* *Single-thread mode is `--logic=main` (`thread` is the default; anything else is refused at launch). The tick runs in
  the framework's own `APP` hook, or in `VexelApplication.frame` when headless, and an arrival wakes the loop for the
  frame that runs it. `LogicTest`'s witness runs two components on two lanes through three hops in both modes and
  requires identical logs. **Not yet judged on vexelray-mini on a low-end machine**: that is phase 7's.*
* *`Outbox<T>` is atchung's own `Sender<T>`, taken as a constructor parameter. The wiring finds its topic by the
  payload: the `static final Topic<P>` declared in the payload's type or the one enclosing it. A component's sender is
  its inbox's, so its sends are ordered by the tick; a view's or a recipe's is an ordinary publish. The template's view,
  close gate and start recipe were moved onto senders, and `Senders` is no longer handed to parts. **`@Publishes`
  stays for one case**: what a ledger's steps send through `Out`, which no constructor shows. Nothing yet checks that
  list against the steps' bodies; `StepCheck` reads them and is where the check belongs.*
* *Processor checks: no part takes the bus, `Senders`, the lanes, the frame hooks, the disposer or any `Executor`
  (T2.5, T4.5); a provided value a component takes is shareable (T2.6); a component's fields ask for no
  synchronisation (T2.7); `@BeforeFrame` and `@MainThread` are refused, so `FrameStage.APP` is unreachable (T7.1); a
  component never holds another, on any lane (T2.3); the `ComputeQueue` needs a declared lane (T3.1). Every topic in
  source declares its kind (`Topic.of` is refused for an application's own), and every payload heard or sent has
  exactly one declared topic (T4.1). Building the template found the widget library's `Chosen` carried by
  three widgets' topics, which a payload cannot choose between: see the ruling below. `@Subscribe`'s `overflow` is gone with `Overflow`, and with them the blocking-cycle and
  blocking-send checks (T4.4, T4.7), which the tick makes true by construction.*
* *`@Subscribe(key = {...})`: keys as strings, which is what a widget address is, filtered by the inbox, or by
  the view's frame through a new `Gui.mailbox(topic, stage, capacity, keys)` in vexelray-gui (`6cd1985`). A key on an edge is a compile error where the constant's source is readable (T4.8).*
* *`ApiWalkTest` walks every public framework type a part can be handed and requires that what takes or returns a
  function is exactly its list of what phase 5 owes: `Dialogs`, `CloseGate`, `Window`'s listeners, and what the
  `Shell` still reaches (`FrameHooks`, `Disposer`, `WakeSource`, two of the window types). The widget library's half
  is vexelray-gui's walk.*
* ***What this breaks, as phase 0 allowed:** the acceptance witnesses (`BLOCK`, `COALESCE_LATEST`, `@BeforeFrame`
  meters), which phase 7 rewrites anyway; every application's `@Subscribe(overflow = ...)`, `Topic.of` constants
  and injected `Atchung`, at its port; and **pontif-framework**, whose `FrameworkLaneTransport` places conductors with
  `Placements` and a `Fold`. pontif is not on the port list above and needs adding to it: under 0.2 a conductor is an
  inbox on the tick, and its samples are a state topic.*
> ***Ruled 2026-10-29, after phase 4: no string topic names.*** *A channel is its payload type, declared once as
> `Topic.edge(P.class)` or `Topic.state(P.class, key)`, named by the payload's canonical name, and referred to by the
> type everywhere else: `@Subscribe` lost `topic`, `@Job` lost `topic` and `reply` (its `run` names both), and
> `@Hears` and `@Publishes` take class literals. So a reference to a channel that does not exist fails the build;
> before, a view could subscribe to a misspelt name and hear nothing. vexelray-gui split its two shared payloads
> (`Chosen`, `Placed`) into a record per widget, so every payload names one channel there too, and the template lost
> its eleven name constants. Only a topic named at run time, a tree's own (T4.6), keeps a string.*

* *Still open from the bullets above: the tick's deadline is the 8 ms placeholder, which phase 7 measures; and the
  processor's two `@View` checks threading.md T7.2 places with `@View` (no `spi` import in a view, and the body check of
  a view's display functions) were not built here.*

### Phase 5. Shell

Every item in §3's shell rows, moved to topics, and every item the earlier phases left here. **Done means
`ApiWalkTest.OWED` is empty**: no framework type an application part can be handed takes or returns a function.

* **Close gate:** the framework publishes `CloseRequested(window, ticket)` and subscribes `CloseAnswer(ticket,
  proceed)`. The template's `QuitGate` goes (phase 6). A close request's handler runs in the frame today and must not
  block (phase 3, step 7); as a message it runs nowhere near the frame.
  ***Done 2026-10-29.*** *Both records are in `-core`, so an application's `-core` answers without reaching the
  shell. The shell's `Closes` keeps each close's `proceed` and `cancel` under a ticket on the main thread and hears
  the answers in the main window's frame, so nothing about a close crosses a lane but two records. A second press
  while a window's question is up is refused there, not asked again. **A close is a question only when a part hears
  `CloseRequested`**: the processor reads that from declarations into `Wiring.answersClose`, and refuses two parts
  hearing it and one with nothing sending `CloseAnswer` (which would keep every window open for good). So
  `CloseGate` and `Window.onClose` are gone, two entries off `ApiWalkTest.OWED`. The template's `Editor` hears the
  question itself and answers a window other than the main one at once (the framework's About is one); `QuitGate`,
  `Quit`, `QuitAnswer`, `Intent.QUIT` and the view's part in quitting are gone, and `Intent` lost its `id`. Held by
  `WindowsTest` (3 cases), `VexelProcessorTest` (3), and in a generated application by `EditorTest` and `ViewTest`
  (`-core` 56, `-gui` 17). **Not driven live:** ottermate has no verb that presses a window's close, and the title
  bar's controls are not in the tree. **Breaks**, as phase 0 allows: the acceptance witness `windows/Recipes.java`,
  which arms `onClose`.*
* **Dialogs:** `Ask`/`Answer`, `PickFile`/`Picked` topics, in place of `confirm`'s `Runnable`s and the pickers'
  `Consumer<Path>` (phase 3, step 18 part 2). `HeldDialogs`' queue becomes the dialog component's mailbox. The native
  dialogs stop answering on a thread of their own (phase 4).
  ***Done 2026-10-29.*** *All four records are in `-core`, so a component on the logic lane asks with no `Dialogs`
  in hand. `Ask(id, title, message, buttons)` is a question that is only words: the first button is the default, the
  last (of two or more) the cancel that Escape and the close box press, and `Answer(id, button)` carries the index.
  `PickFile(id, kind, start, name, filters)` is answered by `Picked(id, path)`, empty for a cancel or a picker that
  could not open; paths are strings, since a `Path` cannot cross as itself. `Dialogs` keeps `ask(Modal)` for a view
  whose dialog is its own shape, and its pickers and `confirm` became one `pick(PickFile, Sender<Picked>)`, which is
  what a test's or a kiosk's dialogs implement; six entries are off `OWED`. `HeldDialogs` is the dialog component:
  every question, an `Ask`, a `PickFile` or a `Modal` handed to `ask` from any thread, arrives in the main window's
  frame, so what it holds and whom it asks are the main thread's, with no lock. `NativeDialogs` lost its
  `vexel-dialog` thread: a picker's answer is one send from wherever NFD finished. The template's own `Ask`, `Answer`
  and `Choice` messages are gone: the `Editor` sends the framework's `Ask` and reads the `Answer` by index
  (`Editor.Choice`), and the view no longer takes `Dialogs` or turns questions into dialogs. Held by `DialogsTest`
  (6), and in a generated application by `EditorTest` and `ViewTest` (`-core` 56, `-gui` 17).*
  *Driven live: Ctrl+W on an unsaved tab opened the framework's "Unsaved changes" dialog from the editor's `Ask`.
  **Its buttons could not be pressed by automation**, and that is vexelray-gui's, not this change's: `Modals`'
  windows are not among ottermate's `windows`, and a key sent while one is up goes to the main window, past
  `app.modalWindow`, so Enter typed a line into the document instead of pressing Save.*
  *Left as is: a one-button `Ask` is answered only by its button, because a `Modal` has no button that is both
  default and cancel, so Escape puts it away unanswered.*
* **Window:** state confined to the main thread; open, close and focus are published as messages. No locks.
  `onOpened`, `onClosed` and `onClose(CloseGate)` go.
  ***Done 2026-10-29.*** *A part opens, hides, toggles or closes a window by sending `WindowCommand(window,
  command)` with its key, and hears `WindowOpened` and `WindowClosed`; all three records are in `-core`. `Window`'s
  `show`, `hide`, `toggle`, `close` and `isOpen` left the public API, since a handle callable from anywhere was a send
  the processor could not see (fork 5), and `onOpened`/`onClosed` went, two entries off `OWED`. `Windows` hears the
  commands in the main window's frame and hands each to its window by key, so everything a `Window` knows is the
  main thread's: no `synchronized`, no copy-on-write lists, no volatiles (the restart posts its close to the main
  thread rather than setting a volatile outcome from the socket's). The framework's own uses are internal and on the
  main thread: the window mark opens About, and About's Escape closes it; About's intro plays on `WindowOpened`.
  Held by `WindowsTest` (a command held in the frame and until the claim, an unknown key ignored). Driven live: the
  mark opened About, its intro ran, Escape closed it through the close gate, and the mark opened it again.*
  *Found: an application that answers `CloseRequested` is now asked about every window, the About window too, so
  closing About waits a logic-lane round trip; the template's `Editor` answers any window but the main one at once.*
* **One bus, many windows** (phase 3, steps 6 and 8): the shell builds each `Gui` with its window's name, so
  `GuiApp`'s window key and the tree's name are one name, and puts every window on the application's one bus. Two
  windows really sharing a bus has not yet run in an application; this phase is where it first does.
  ***Done 2026-10-29.*** *Every window's `Gui` is built on the application's bus and named by its key (`main`, or a
  named window's), so a widget answers to `<key>.<name>` and a view hears every window's widgets.*
  *Found first, and fixed before it shipped: **a closed window's tree would have been filled by the others'.** A
  tree's widget inbox subscribes to the widget library's shared topics (`Button.PRESSED` and the rest) and is drained
  only in that tree's frame, and `GuiApp` frames only open windows. Every application has a closed About window whose
  buttons hear `Button.PRESSED`, so 4,096 presses in the main window would have failed it, and typing would have done
  the same to any closed window with a text field (a state topic too: staging is per message, before folding).
  Separate buses had been hiding it. Ruled with the author: **keyed edges**, a filter declared on the topic rather
  than written as code. atchung's `Topic.edge(type, key)` is an edge whose key filters and never folds, and
  `Topic.scoped(key)` gives any topic a scope apart from its fold key; `Inbox.subscribe(..., keys)` filters a keyed
  edge, and `Inbox.subscribeIn(..., scopes)` filters by scope, both before a message is staged, so a tick that is not
  run holds nothing it would discard (`TickTest`, two cases). vexelray-gui's widget topics are declared by
  `Addressed.edge` and `Addressed.state`, scoped by the address's tree, and a tree's `Routes` subscribe in their own
  tree alone. Held by `WindowsTest.aClosedWindowsTreeHoldsNothingOfAnotherWindowsWidgets` (5,000 presses in the main
  window, the tool window's next frame hears its own one press). Suites: atchung-core 115, vexelray-gui's reactor
  green, framework shell 171; a generated application (`-core` 56, `-gui` 17) driven live with About open beside the
  main window on one bus: a file opened from the path bar and typed into, then About put away with Escape.*
  *Left: the dialogs keep a bus of their own (`Modals`, vexelray-gui's), and a view's own `Gui.mailbox(topic, stage,
  capacity)` on the main tree is unscoped, which is right while only the main window's frame drains views.*
* **What the `Shell` hands over:** `FrameHooks`, `Disposer`, `WakeSource`, `InputBackend.perWindow` and
  `NamedWindow`'s `Standing` are reached only through the `Shell`, which the generated wiring needs and a recipe
  still takes. A recipe stops taking it. `Shell.krono()` is public only because the automation driver reads
  quiescence from it (phase 3, step 18 part 1); the driver reaches it another way.
  ***Done 2026-10-29: `ApiWalkTest.OWED` is empty,*** *phase 5's exit check for what an application can reach. The
  processor refuses the `Shell` to an application part (`Framework.SHELL_REFUSED`): it stays a root for the generated
  wiring and the framework's own starters, and the automation driver, the host's instrument, keeps taking it. So
  `FrameHooks`, `Disposer` and `WakeSource` leave an application's reach with it, and `Shell.krono()` with them, which
  needed no other route. The walk now seeds from what a part can be handed (the roots but the shell) and what it may
  hand back (the hand-back types, which it implements), which found two doors the shell had been hiding:
  `InputBackend.perWindow` (an interface's static, so public whatever it was meant to be) moved to
  `TactrollerInputBackend`, and `NamedWindow`'s `Standing`, a function, became a declared `owned` flag (`satellite()`
  sets it). The template's start recipe, which took the shell only to be built last, is gone, with `Begin`: the
  `Editor` brings the session back on `WindowOpened` for the main window, which a headless test never hears, as it never
  ran that recipe. An application can no longer provide a driver of its own (`Driver.open` takes the shell), so naming
  `AutomationStarter` is its whole say. Held by `VexelProcessorTest.aPartTakingTheShellIsAnError`,
  `AutomationStarterTest.anApplicationCannotOpenADriverOfItsOwn` and `EditorTest` (another window opening restores
  nothing). Driven live: a generated application started, and its navigator listed the start folder, which only the
  restore that `WindowOpened` begins sends.*
  *What phase 5 still owes is the framework's own inside: the host's sinks, the About intro, the slow-frames prompt
  and undo feedback, capture's wait for answers, and the watchdog. No application can reach any of it.*
* **The host's sinks** (phase 3, steps 3 and 4): `onUndo`, `onCursorChange`, `onPointerLock` and `onPointerWarp`
  are the host's seams (`GuiApp`, the automation driver, undo feedback), not a node's, and move with the shell.
  ***Done 2026-10-29, and smaller than it read.*** *The four already left `Gui` for vexelray-gui's `Seams` in phase 3
  (step 10), the host's door, which no application part reaches, by the same ruling as `WidgetSeams`. Each is told on
  the main thread, in the frame: undo's outcome, the cursor's shape and the pointer lock from the dispatcher, a warp
  from a ride. What was left was the framework's side of them guarding against paths 0.2 has closed: `PointerLock`'s
  `wanted` was volatile because a worker could remove a node, and `UndoFeedback` built its `Cues` under a benign race
  behind a volatile and asked its dialogs through a `Supplier`. Under 0.2 nothing off the GUI thread holds a `Gui`, so
  both are plain fields of the main thread, and the feedback holds the shell's one `Dialogs`. `PointerLockTest` and
  `UndoFeedbackTest` hold them; neither was driven live.*
* **About intro, slow-frames prompt, undo feedback:** components and widgets like any application's. The About
  intro is also where vexelray-mini's installer benchmark is planned (phase 8), so it waits on this.
  ***The About intro done 2026-10-29.*** *§3's row for it is answered: gone are eight volatiles, a `synchronized load`, an
  `AtomicInteger` run counter, a `ConcurrentHashMap` of uploads and a virtual thread outside the lanes. The intro is
  told everything, on the main thread: when the window opens (`WindowOpened`, in the main window's frame), where its
  time is (a `Tween` on the window's clock, in About's own mailbox, in place of Kronometer's `ramp` and its lambda),
  and its art once read (a framework job on the job lane, `About.art`, answered in About's frame). A reopening restarts
  the tween with vexelray-gui's new `Tween.at`, a jump that stops the ramp under way, so no step of an earlier run is
  heard and the run counter has nothing to do (`TweenTest.atJumpsThereAndStopsTheRampUnderWay`). Driven live: About
  opened, was put away with Escape, and opened again to the settled page, the marks condensed and the orb on the V.*
  *Not shown live: the first opening's frames, because its art is read on the job lane and `settle` waits for the
  clock but not for a job; the same was true before. This is the item below, capture's wait for what is owed.*
  ***The slow-frames prompt and undo feedback done 2026-10-29.*** *Both ask as any part asks: an `Ask` on the
  application's bus (`OwnAsks`), which the dialog component hands to whichever `Dialogs` answer, so a test's or a
  kiosk's is asked too. **The framework's questions take negative ids**, counting down from -1, and `Ask`'s javadoc
  now says an application's are zero or more, since every part that hears `Answer` hears every answer. The
  slow-frames prompt keeps the frame loop's `answer` under its question's id and hears `Answer` in the main window's
  frame, so the `Consumer` never leaves the main thread and no button runs code: §3's row for `SlowFramesPrompt` is
  answered, and it no longer reaches `WidgetSeams`' host door. Undo feedback sends a one-button `Ask` in place of
  `Dialogs.info`, and holds no `Dialogs`. Held by `SlowFramesPromptTest` (5: the id is the framework's, each button is
  its answer and only once, an application's answer is not taken for it, and end to end headless, an application's
  own dialogs shown the question and their press heard back) and `UndoFeedbackTest` (3). Driven live in a generated
  application with `-Dvexelray.debug.slowFrames=150` and 200 keystrokes: the offer was made after the warm-up and
  the dialog window "This is drawing slowly" opened 78 ms later.*
  *Lost: the prompt's `Modal.Severity.QUESTION`, since an `Ask` is only words and every one is shown at the default
  severity. Not driven live: pressing its buttons, which automation cannot do (the dialogs item above), nor an undo
  that fails.*
* **Capture waits for what is owed** (phase 3, step 5): a capture plays animation to rest but does not wait for
  answers not yet had, so a tree whose listing runs on another thread can be captured before it lands. The shell's
  quiescence includes answers outstanding.
  ***Done 2026-10-29.*** *`Shell.owesNothing()`: no job in flight and every message for the logic lane delivered,
  with `owed()` saying what is not. The job lane counts a job from the moment it is handed over to the moment it has
  finished, which is after its reply is published; the logic lane counts each arrival as it is staged and marks
  delivered only what a tick saw before it began, when nothing is carried. So an answer under way is owed by one or
  the other at every instant, and a quiet answer is never early. The automation driver's `settle` waits for it beside
  the clock and the frame loop, and a settle that does not go quiet names the jobs and messages it is waiting on.
  Held by `JobsTest.aRequestIsOwedUntilItsAnswerIsDeliveredOnTheLogicLane` (owed while the job runs, still owed while
  its answer is being delivered, and not after). Driven live: About's first opening, photographed after one `settle`,
  is the finished page (before, three settles photographed it blank while its art was read), and a folder expanded
  in the template's navigator, listed by a job, shows its children after one `settle`.*
  *Not counted: a part's own `Timer` (a beat is not an answer owed), and the declared lanes' share runs inside the
  logic lane's tick, so it is counted with it.*
* **Watchdog:** stays outside the lanes, reports by message.
  ***Done 2026-10-29.*** *The watchdog publishes each stalled delivery as `Stalled(lane, stalledFor)`, a record in
  `-core`, from its own thread, and calls nothing: its `Consumer<Lanes.Stall>` is gone, and `start` takes only the
  threshold. The application's `LivenessPolicy` is unchanged (`v1.md` freezes it) and hears the report directly on
  the bus, so it runs on the watchdog's thread as before: it has to run while the lanes are wedged, which a mailbox
  on one of them would not. A part that only wants to know hears the same `Stalled` in its mailbox, once its own lane
  is free to. A throwing policy or a full mailbox is caught around the publish, so it still cannot stop the thread the
  halt deadline is on. Held by `WatchdogTest` (9, the reports now heard on a bus) and
  `LivenessTest.aStallIsReportedAsAMessageOnTheApplicationsBus`. Not driven live: no generated application wedges.*
  ***Phase 5 is done.*** *Suites: framework 468 green (core 64, processor 154, shell 175, automation 14, template
  61); the acceptance profile was not run.*

### Phase 6. Template

Rebuild the template on the new API, starting from the other agent's `-core`/`-gui` split. Expected to shrink:
`QuitGate`, `Intent`, the batch protocol in `Closing` and the `SET` capacities should all disappear. Its `Editor`
and `View` are the reference for what an application looks like, so their javadoc is written last and carefully.

*Begun 2026-10-28, ahead of phases 4 and 5. The other agent's split was brought onto `v0.2` (`ff196a3`), and `-core`
was rebuilt on chains.md: the `Editor` is one ledger of declared steps, closing and quitting are one chain started
by a plain message, the session is read and written by two jobs, and the batch protocol in `Closing` is gone (one
`Close` with a list). `EditorTest` drives it on a tick of its own, and the generated `-core` builds and passes (50
tests). The `Editor` is a `@Component` taking its inbox, listed by `Core`, so the wiring builds it on the logic lane
(phase 4, begun the same day). Still owed here: `-gui` stops compiling on `v0.2`'s gui at `FolderSource`
(`TreeView.Source` is gone), as phase 3 noted; `View`, `QuitGate`, `Intent` and the view's `SET` wait for the view
to be a ledger and for phase 5's close gate.*

*The view rewritten the same day: `View` is a `@View`; chords, the tab menu and the tree's menu are declarations;
the tree is answered by a `ListFolder` job and Markdown coloured by a `MarkText` job; `FolderSource` is gone. **A
generated application runs again**, on `v0.2` throughout, and was driven with ottermate: a folder shown and listed, a
file opened from the path bar, typed into (the tab, title and status say unsaved), saved, closed, and closed unsaved
through the dialog with Save, which wrote it. Its tests: `-core` 55, `-gui` 13 (`ViewTest` steps frames). Still here:
`QuitGate` (phase 5's close gate), and the view's `SET` capacity for a burst of `CloseTab`s.*

*Found while running it, owed to vexelray-gui: **a widget that asks at construction can ask nobody.** `TreeView`
asks for its top level when it is built, and the view that answers is built after it, so the question is published
to no subscriber and dropped, and the tree then waits for that answer before any refresh. The template answers the
top level itself the first time a folder is shown (`Navigator.show`). The fix belongs in the widget: ask on its first
frame, when every view's mailbox exists.*

***Done 2026-10-29,*** *the rest of it:*
* ***The `SET` capacities are gone.*** *Each was a burst of one message per tab, and each is now what fork 1 or fork 6
  says it is. `Retitle` is a state topic keyed by tab (`Topic.state(Retitle.class, Retitle::id)`), so the view is
  handed a tab's last title in a tick and never a backlog; `CloseTab` became `CloseTabs(List<Long>)` and `Flush`
  carries a list, so a closing is one of each however many tabs it closes. The view's mailboxes are all the default
  64. Held by `ViewTest.closingMoreUnsavedTabsThanAMailboxHoldsSavesAndClosesThemAll` (100 unsaved tabs, Save all,
  every file written and every tab closed) and `EditorTest`. **Left:** the `Editor`'s inbox stays 256 deep, because
  a closing still answers with a `Save` and then a `Written` per unsaved tab, each file being its own write; making
  those lists too would put a whole closing's writes in one job, which is a worse failure unit than the depth.*
* ***`QuitGate` was already gone*** *(phase 5's close gate). **`Intent` stays**, and is not what the roadmap expected
  to shrink: that `Intent` carried `QUIT` and an id across to the editor. What is left is the view's own channel for
  its chords (`gui.shortcut` names a sender and a message), every command a method on the view, with nothing crossing
  a lane. A chord needs some message to send, and this is the smallest one that names what the chord means.*
* ***A generated project builds against the framework that generated it.*** *The manifest tied the framework's,
  vexelray-gui's and vexelray-codec's versions to `vexelrayVersion`, the engine's (0.1.3), so a project generated on
  `v0.2` needed its pom edited by hand. `frameworkVersion` is now its own fill, and gui and codec follow it; the engine
  and the build plugin keep `vexelrayVersion`. `VexelDesktopTest.theStackIsTheFrameworksOwnVersion` holds it to this
  module's own version, so a version bump that forgets the manifest fails the build.*
* ***A widget that asks at construction asks in its first frame instead*** *(vexelray-gui). `TreeView` sends itself
  a `Refresh` when it is built, and the first one it hears asks for its roots, by which time the view that answers
  is subscribed. The template's workaround (`Navigator.show` answering the question the tree was still waiting on,
  and its `rootsAnswered` flag) is gone: showing a folder is `tree.refresh()`. vexelray-gui's `TreeChildrenTest`
  says when the roots are asked for, and its `Listing` harness runs the extra frame (widget 618 green).*
* *Suites: a project generated with no hand edit, `-core` 56 and `-gui` 18; template 62. Driven live: the navigator
  listed the start folder on its own, a file typed into showed unsaved in its tab and the title, saved and closed with
  Ctrl+S and Ctrl+W emptied the workspace, and Ctrl+W on an unsaved tab opened "Unsaved changes".*

### Phase 7. Witnesses and measurements

> ***Ruled 2026-10-29: the acceptance witnesses are made to be broken.*** *A change to the architecture is never held
> back to keep a witness compiling, and a witness is never patched to fit an old shape. When one breaks, it is
> rewritten in the new one, and what its rewrite finds improves the framework. Phase 4 broke three (`BLOCK` and
> `COALESCE_LATEST` mailboxes, `@BeforeFrame` meters), and that is the intended direction.*

* Rewrite W2 (components), the long run and pacing witnesses as components that publish, with no shared `Bench` or
  `Meter`.
* Input-to-photon latency before and after (fork 4's cost), and idle CPU (a tick must not run when nothing arrived).
  **Measured at a low frame rate too, on `vexelray-mini`.** Fork 4's extra frame is 17 ms at 60 Hz and 67 ms at the
  fifteen a second where `SlowFrames` makes its offer, which is where a person feels it. Taken in both modes, since
  single-thread mode is meant for exactly those machines.
* `mvn clean install -Pacceptance` green.

***Witnesses rewritten 2026-10-29; acceptance green (the whole profile 17 of 17, then the fifteen-a-second pacing
runs added and green on their own); measurements accepted by the author the same day, with the 60 Hz ceiling
holding animation (below).***

* ***The shared counter is a component.*** *Every witness drove one `Model`, an atchung `State` that any thread
  committed to and that called its listeners on whichever thread committed. It is now `Counter`, a component on the
  logic lane that hears `Bump` and `Reset` and publishes `Counted`, a state topic, and each witness's view shows it.*
* ***W2 (components)*** *has no `Bench`: each component says what it did as a message (`Worked`, `Echoed`,
  `Wedging`), a `@View` shows it, and the stall readout is the watchdog's own `Stalled` (phase 5), with the policy only
  interrupting. Buttons are `Button` widgets that publish; nothing of the witness runs at a press. Driven: work, echo
  and count answered; the tool window opened and closed twice; a wedge on a declared lane and one on the logic lane,
  each named by `Stalled` and freed by the interrupt; the default policy exited the application.*
  * ***Found: a declared lane is not a wall around a wedge.*** *It runs its share in the same rounds as the logic
    lane (T5.6), so a wedge on any lane holds every lane at the round's end. The witness used to assert the opposite;
    it now asserts this, that a bump waits out a wedge on the declared lane and is delivered once it is freed. What no
    wedge stops is the window: the view's own tap count moves throughout. The liveness guarantee as `v1.md` states it
    holds; what is gone is the 0.1 claim that a lane of its own keeps other lanes going.*
  * ***Found: a component cannot report progress from inside one delivery.*** *What it sends goes out with the
    round, so the "wedged" it sent before blocking was held for as long as it blocked, and the view saw only "freed".
    The view now says what it asked for. Right for determinism; worth stating in `threading.md` for long work, which
    belongs in a job.*
  * ***Found: the processor let a component sleep for good.*** *`Thread.sleep(Long.MAX_VALUE)` in a `@Subscribe`
    compiled without a word. The witness needs it, but an application should be told; the T2.5 check does not reach
    blocking calls in a component's body.*
  * ***Found: the bus graph still speaks 0.1.*** *Every mailbox says `overflow=FAIL capacity=64`, state topics
    included, and the logic lane is `lane=<default>` while the watchdog and the thread call it `logic`. The Inspector
    reads this format, so it was left; it should say a topic's kind and use the lane's real name.*
* ***W3 (long run)*** *counts a `Timer` beat on a declared lane (0.1 slept ten milliseconds in a delivery and sent
  itself the next), the frame hook is gone (frames are counted from the flight recorder), and the process is weighed
  by a job. Idle 15 frames in 3 s; running 284 in 5 s (56.8 a second, under the 60 Hz ceiling, where 0.1 ran at the
  wake rate); 15 again after Stop; threads 25 to 26 and the heap 34 to 35 MB over a 15 s run.*
* ***W1 (windows)*** *opens, closes and quits by `WindowCommand`, counts the notes window's openings from
  `WindowOpened`, and the palette's close confirmation is a component hearing `CloseRequested`, asking with `Ask` and
  answering `CloseAnswer`. All four cases as before, the dialog's Escape and Enter included. **Changed:** the
  question and the notes window that the launch asks for are sent as the main window opens, not in `TREE`; holding a
  question asked before there is a window is `DialogsTest`'s, and a command before the claim `WindowsTest`'s.*
  *Found: a recipe may not return a record (the launch's two settings), so they are `@Setting` parameters of the
  component that uses them.*
* ***The pacing instrument*** *had a component holding the tree and a frame hook running a stopwatch, both
  impossible now. A time is a value: stamped into a message and read again by the view in the frame that shows it;
  frame counts are the recorder's. The pulse is a `Tween`, whose steps arrive once a frame. The storm is a 1 ms timer
  beat: **a component sending itself its next delivery is refused at 64 hops as a loop**, which stopped the first
  attempt, and is right; sustained work arrives from outside the logic.*

**The numbers** (this machine, display about 144 Hz; 0.1's from `docs/architecture.md`, *what the pacing measurements
found*). Both modes measure the same to within noise, as fork 4 intends.

| | 0.1 | 0.2, logic lane | 0.2, `--logic=main` |
| --- | --- | --- | --- |
| A 600 ms animation | ~88 frames, **~144 a second** | 35–36 frames, **~57 a second** (gap median 17.5 ms) | the same |
| A click's result through the logic, frame to frame | 6.5 ms through the handler lane (one 144 Hz gap) | **17.5 ms median**, p95 18.6 (one 60 Hz frame) | 17.5 ms |
| The same at ~15 frames a second | not measured | **66 ms median** (one frame) | 66 ms |
| A wake from a parked loop to its frame | 0.6–1.3 ms | **0.7 ms** median, p95 ≤ 1.0 | 0.7–0.8 ms |
| The same at ~15 frames a second | not measured | 1.2 ms median | 1.1 ms |
| Idle, focused: frames | 5 a second | 18 in ~3.4 s (5 a second) | the same |
| Idle: process CPU over 4 s | 0.8–3 % of a core | 0.8–7.4 % across runs | 1.6–7.0 % |
| Arrivals faster than frames | 134–143 frames a second | 1,400–1,500 beats in 3 s, **~59 frames a second**, nothing lost | the same |

*Superseded the same day for the click row by **fork 7**: with input and messages on their own clocks, a click's result
through the logic is 1.9 ms median at 60 Hz (both modes), and at fifteen a second 1.1 ms in `--logic=main` and 64 ms on
a lane. The other rows are unchanged. Acceptance green after it (18 of 18).*

**What they say. Accepted by the author 2026-10-29 (§5's sixth point):**
* **Fork 4 costs exactly one frame**, as predicted: 17.5 ms at 60 Hz, 66 ms at fifteen a second. A result the view
  makes itself costs none: it is drawn in the frame the click arrived.
* **The 60 Hz ceiling now holds animation too.** In 0.1 an animation ran at the display's 144 Hz, because the clock's
  wake ended the ceiling's park; now it runs at ~57. So 0.1's 6.5 ms and 0.2's 17.5 ms are each one frame, and the
  difference is the ceiling, not the lanes. `architecture.md` called a ceiling that held a 144 Hz display to 60 "the
  opposite of the goal the design states". ***Ruled 2026-10-29: keep the 60 Hz cap***, animation included, so one
  frame through the logic is 17.5 ms on any display that runs at 60 or faster.
* **Idle CPU is noisier than 0.1 recorded**, up to 7 % of a core in some four-second windows. Windows accounts CPU in
  15.6 ms ticks, so four seconds is a handful of ticks; a minute-long run would settle whether 0.2 idles dearer.
* **Not measured on `vexelray-mini`**, which is still 0.1 (phase 8). The fifteen-a-second runs stand in for it, with
  each frame slowed by `-Dvexelray.debug.slowFrames=62`, inside the slow-frames offer's ten-second warm-up.

### Phase 7½. A new application, before any port

*Asked for by the author 2026-10-28: build a completely new application from the template (the author has the
idea), and make that the real test of 0.2 before any migration. **Why:** a port starts from an application's old
shape and bends the new API to fit it, so it tests whether 0.2 can carry old code. A new application starts from the
template, so it tests what 0.2 is for: whether the framework is easy to use correctly from nothing. What it finds goes
in its `docs/framework-notes.md`, and is fixed in the framework before phase 8 begins.*

### Phase 8. Land and port

* Merge `v0.2` into `develop` in each repo, in `bootstrap.sh` order. Bump the apps' framework dependency one at a
  time: vexelray-mini, scheduler, calculator, text editor, vexplore, mainframe. Each app's `docs/framework-notes.md`
  records what the port found.
* **vexelray-mini** goes first because it is the smallest port: it uses none of the APIs this branch removes
  (no handler, `async`/`offload`, `Runnable` action, `@BeforeFrame` or `@MainThread`), and its whole application is
  one start recipe building a fixed tree, which becomes a view component (T7.4). Its pom already carries
  `vexelray-gui.version` apart from `vexelray.version`, so phase 0's shared version slot does not reach it. Its
  installer benchmark, planned on the About intro, waits for phase 5's rebuild of that intro as a widget and a
  component.
* **Vexplore** reaches more of the widget library than the others, and its port is inventoried before it starts:
  [*The v0.2 port inventory*](../vexplore/docs/framework-notes.md#the-v02-port-inventory) lists every API it
  uses that this branch removes or changes, with call sites and what replaces each. Two of its needs have no message
  form here yet: the row menu (`ListView.onContextMenu`) and drag and drop onto its own file list
  (`ListView.draggable`/`droppable`), which makes it the witness for a declared drop zone.
* **vexelray-codec and vex-suite-common** are libraries this list missed, and both stand between the stack and two
  of its applications. They come after the framework in `bootstrap.sh` order and before Vexplore and Pix. **The codec
  is on `v0.2` beside the other clones since 2026-10-29** (phase 0): its five call sites are fixed (`onResize` on `Seams`,
  `gui.input()`, and a movie opened on a thread of its own, as its decoders are), its 194 tests pass, and it is
  installed at `0.2.0-SNAPSHOT`. Still owed: its players take a `KronoGui` an application can no longer be given, so
  `ImageStarterTest`'s recipe provides one as a marked interim and the demo player (`demos/video-player`) does not
  compile until it ports with the frame contract. Every moving picture in it (an
  animated image, a video, the viewer's inertia) is paced by `KronoGui`, which step 18 took from applications, and
  0.2 has no form yet for code that runs every frame while a video plays. **The codec's player is the witness for
  it.** vex-suite-common's viewer (`PictureViewer` and its `Tile`s) is shared by Vexplore and Pix, so it is ported
  once for both. The codec's views and the suite's viewer are **widget libraries** (ruled 2026-10-29: they use `Seams`
  and publish what happened). How a video is paced is **proposed** in
  [vexelray-gui's frame-contract.md](../vexelray-gui/docs/plans/frame-contract.md): drawing, presentation and
  pacing as one contract, a part told by declared steps and asked by a declared, checked draw at the present time,
  picking a video's frame from a feed of stamped slots. *Inventoried
  2026-10-29* in [Pix's port inventory](../Pix/docs/framework-notes.md#the-v02-port-inventory), checked by
  compiling copies of all three against this branch.
* **Pix**, added 2026-10-29, after Vexplore, since both stand on the suite's viewer. Its own code is the smallest
  part of its port. The one new shape is its picture reader: deliberately one worker with one slot, so holding an
  arrow key decodes only the picture it stops on. In 0.2 that is a ledger that sends one job request at a time, which
  no application has done yet.

---

## 5. Done means

The redesign is done when these hold, and each is checked by a test or a grep in the build, not by review:

1. No `synchronized`, `volatile`, `java.util.concurrent` or `Thread` in the template, and in the framework only in an
   allowlisted set of infrastructure classes (§3).
2. No public framework or gui API reachable by an application takes a functional interface or an `Executor`, except
   a chain step the processor has checked ([chains.md](docs/plans/chains.md)).
3. No application code runs on the main thread, except the ticks the framework itself runs there in single-thread
   mode: the processor refuses every door an application could open (fork 4).
4. Every message an application sends is visible to the processor as a constructor parameter (fork 5).
5. A run replays: the same input messages, in the same order, produce the same widget writes, in either mode.
6. Input-to-photon latency and idle CPU are measured and written down, and the author has accepted the numbers.

## 6. Risks

* **Latency** (fork 4). Mitigation: widget-local responses stay local; measure before and after.
* **The text field** (phase 3). It is where a per-keystroke round trip would be felt. Prototype first.
* **atchung's other users** (`tactroller`, `kronometer`, `vexelray`, `supirvast`, `pontif-framework`, ...). Phase 2
  keeps publish/subscribe source-compatible. If that turns out impossible, the break is recorded here before it lands.
* **Merging the other agent's work** while it is still moving. Merge once it is committed, and keep the `v0.2`
  branch's changes to the template behind phase 6 so the two do not fight in the same files.

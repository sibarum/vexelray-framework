# Transactions and chains: a function sent to the state that runs it

Status: **Direction ruled 2026-10-28; steps 2 to 5 built, and 6 for the controller (see *Order of work*).**
**Proposed 2026-11-02, not ruled: chains as trees, with bounded waits (ALL, ANY, NONE), near the end.** The
author's words first, then the rules settled in the same conversation, then the design, what it changes in the 0.2
roadmap ([ROADMAP.md](../../ROADMAP.md)), the questions it leaves open, and the order of work. It builds on
the 0.2 message model, on vexelray-gui's
[tree-thread.md](../../../vexelray-gui/docs/plans/tree-thread.md) and [undo.md](../../../vexelray-gui/docs/plans/undo.md),
and on atchung's `State`.

## Why

In the author's words:

> What if I could send a function to another component, and have that component execute the function on their
> state? [...] You wouldn't need to define hundreds of different messages for every possible cross-component read and
> write and response. [...] And it makes everything within the app undo-able, because if the state is the only thing
> that changes, you only need to take snapshots of it.

And then the chain:

> Then you could say: execute this whole chain [...] It makes everything inspectable at a glance. It makes complex
> multi-threaded message passing scenario into a single traceable sequence.

Today a part that changes another part's state writes a message type, publishes it, and the owner writes a handler;
one that reads writes a request type, a reply type and a handler on each side. What happens after a click is spread
over a view's handler, a controller's subscription and a reply topic, and is put back together by following topic
names. Undo is written per edit (`Change.apply`/`revert`), and an edit that crosses into the logic is a `Change` whose
two halves each send a message.

**The message becomes the function.** A declared function `(state, args) → state`, whose code is fixed at compile
time and whose inputs are values, is still a message: it can be queued, shipped, logged and replayed. It just carries
its own meaning. A chain is the path from a cause to its outcome, written once, out of such functions.

**This completes T7.2 rather than contradicting it.** T7.2's objection is to code the receiver cannot see: a closure
over arbitrary state, run on someone else's thread. A transaction is code the receiver owns, declared against its own
state type, and checked at compile time to touch nothing else.

## The rules

Ruled with the author, 2026-10-28:

1. **A step is a declared function, never a closure.** The processor reads each step's body and refuses one that
   captures anything or does what is known to be unsafe (rule 4). A non-capturing lambda is a
   constant (javac makes one instance), so it is a compile-time function; a chain is built once at wiring time, so a
   step is identified by where it is declared. *Reason:* a step that captured the view's fields would be exactly the
   code T7.2 bans, run on another thread; and an identity by declaration gives the log and replay their ids without
   serializing a lambda, which needs reflection.
2. **A step returns an event; it does not wait for what the event leads to.** `PromptToSave` emitted by a step means
   the prompt is wanted, not that it was shown that frame. Showing it is the next step's business, and waiting for the
   person is that step's. The GUI never waits on a chain. A chain's visible results on the tree's state are applied a
   whole tick at a time (tree-thread.md rule 3), so nothing half-done is drawn. *Reason:* a step that may involve a
   person or a disk cannot promise a frame; what can be promised is that the frame shows no half-finished chain (no
   visual discontinuity).
3. **A step belongs to the module that owns its state.** A chain is written where its cause is (the button), but it
   names steps that each owner declares on its own state. An inline lambda is allowed when its state belongs to the
   module the chain is written in. *Reason:* steps written inline at the button would put the application's logic, and
   its types, in the view, undoing the template's `-core` controller and `-gui` view split.
4. **The check never refuses safe code.** It refuses what is known to be unsafe and lets through what it cannot
   judge. An author who gets past it (a call into code the check does not read, a mutable object behind an
   interface) owns what follows. *Reason:* Java lacks the primitives that would let a checker prove a step safe
   (the author designed Pontif to have them, and it cannot be used here), so a check that demanded proof would refuse
   correct code, and an escape from a false refusal is debt by construction. A check that only names what is
   certainly wrong has no false refusals to escape from. *(The author, 2026-10-28: "this is Java's problem. We can
   try to warn you not to do unsafe multithreading things, but if you circumvent our checkers, that's on you, not
   us.")*

The design below follows from these and is not yet ruled in its details.

## What exists today

- **atchung's `State<T>` already holds half of it.** Immutable values; mutations declared by name on the state's
  builder (`State.Builder.mutation`) and required to be side-effect-free (`Mutation`'s javadoc); each commit a
  `Versioned` snapshot; and a mutation's name exists so a replication bridge can ship it to a replica (`Committer`).
  What it lacks is a mailbox (a commit is a CAS on the caller's thread, not a turn on the owner's lane), an outbox, a
  compile-time check, and undo.
- **State topics** (`Topic.state`) deliver the last value per key.
- **The processor** already reads declarations; reading bodies is owed anyway, for `@View`'s lambda check.
- **Undo** (`core.edit.History`, `Change`) is built per edit, with undo.md's behaviour: reveal, refuse, conflict,
  one history per window.

## The design

### A. A transaction

A transaction is `(state, args, outbox) → state`, run on the lane of the mailbox that owns `state`, one at a time.
State is a value: a record, and the framework's immutable collections (fork 6). Args are values the bus can carry.

### B. The outbox is released at commit

What a transaction sends is held until it returns. If it throws, nothing happened: no new state and no messages.
This is Clojure's agent rule, and it is what makes a transaction atomic including what it says. An effect (a file
written, a process started) is never done in a transaction; it is a message to a mailbox whose job is effects, and
that is where what cannot be reversed lives (E).

### C. Reads are snapshots

Every commit publishes the state's new value on its state topic. A read is the latest snapshot, with no message.
Only writes travel. This is what removes most of the request and reply pairs.

### D. Chains

```java
closeItem.chain(
    Chain.on(gui,     View::closeClicked)        // tree thread: reads the selection → CloseAsked(doc)
         .then(editor,  Editor::closeRequested)    // editor's lane: dirty? → PromptToSave(doc) or Closed(doc)
         .on(PromptToSave.class, dialogs, Dialogs::askSave)   // shows the prompt; the answer resumes the chain
         .then(editor,  Editor::finishClose));     // the editor's state, on the editor's lane
```

*(As built in atchung, this reads `Chain.start("close", view, closeClicked).then(CloseAsked.class, editor,
closeRequested).then(PromptToSave.class, dialogs, askSave).then(Save.class, editor, saveAndClose).or(DontSave.class,
editor, discardAndClose).then(Closed.class, view, closed)`: every link after the first names its type, and a link
may have alternatives. See step 4 of the order of work.)*

- **Atomic in one mailbox, a saga across mailboxes.** Between two steps, other transactions run on each state. One
  transaction touches one state; that rule makes a state's edges and the edges of what is atomic the same.
- **A step's result picks the next step by type**: `.on(Type.class, ...)`, one method per outcome. No sealed `switch`
  (the house rule), and a class literal is not reflection.
- **A chain is orchestration; the bus stays for broadcast.** Chains for a finite flow from a cause to its outcome;
  state topics for "this is the latest value", which anyone reads and nobody waits on.
- **One id per run of a chain.** The id travels through every outbox. It is what tracing shows (this click caused
  these steps on these threads, taking this long), and it is the undo group (E).

### E. Undo by snapshot

A step's state before it ran is kept, cheaply when state shares structure between versions. Undo restores, for every
mailbox a chain run touched, the state it had before: always the latest run first, which keeps it sound. Undoing a
run out of order would need inverse operations, and is not offered.

- **Effects are the exception.** A mailbox that does effects declares, per effect, an inverse or an undo barrier.
  That is the author's "permanent" action, and the only undo code an application writes.
- **undo.md's behaviour stays.** Reveal before applying, refuse on a disabled target, fail on a conflict: those are
  about the person, not about how the state goes back.
- **The tree is not undone itself** once tree-thread.md lands: it is a projection of state, and is redrawn from the
  restored state.

### F. The check

At compile time, through javac's `Trees` (the machinery `@View`'s lambda check needs). Under rule 4 it is a list of
what is certainly wrong, not a proof of what is right, so each item is something no safe step does:

- a capture: anything the step uses that is not its parameters or a constant;
- a write to a static field, or a read of one that is not `final`;
- `synchronized`, a `volatile` or `java.util.concurrent` type, `Thread`, or a lock;
- a call on a known list of blocking and effectful JDK methods (`java.io`, `java.nio.file`, `java.net`, `Thread.sleep`,
  `Object.wait`, `Future.get`, `System.exit`), listed by what they are rather than allowed by what they are not;
- a parameter, return or state field of a type the bus already knows is mutable (T2.4's rule).

A call into code the check does not read (a library, an interface) is let through. It cannot rule out an endless
loop; the watchdog stays.

*(As built, step 3: two items above were narrowed under rule 4. A `java.util.concurrent` type and `Thread` are not
refused for being used: a `ConcurrentHashMap` made inside a step is local and harmless, and so is asking the thread
its name. What is refused is what such a type is for: waiting (`Future.get`, `Lock.lock`, `await`, `take`, `sleep`,
`join`) and handing work to another thread (`new Thread`, `start`, `execute`, `submit`, `Executors`). The same for
`java.io` and `java.net`: a `StringWriter` and a `URI` touch nothing, so the list names the types and methods that
reach the disk, the network, the process's streams, another process or the end of this one. A blocking call is
refused by its contract, whether or not this instance would block. The last item, mutable state and argument types,
is not built: T2.4's check is about payloads and has not been rewritten (fork 6), and the two should be one rule.)*

### G. The log

Every transaction is serializable: a declared step's id and its args. The log of transactions is the application's
history: replay (ROADMAP *Done means* 5), crash recovery, recording a session for automation, and stepping back
through it while debugging.

## What it changes in the roadmap

- **Fork 5 (`Outbox<T>`)** stays, and becomes the transaction's outbox (B), released at commit.
- **Fork 6 (lists in payloads)** is needed for state values; snapshots need collections that share structure, which
  the JDK does not have.
- ***Done means* 2** ("no reachable API takes a functional interface") gains one exception: a chain step, checked by
  F. The API walk's lists are built after that is written down, so its owed entries point at the right place
  (`History`/`Change` become snapshots; part of the bus's callback group becomes transactions).
- **The tick** (phase 4, tree-thread.md's logic side) is what runs transactions: a component's mailbox drains its
  queued transactions in order.
- **atchung is added to, not changed** (its behaviour is frozen): a mailbox for `State` with an outbox, beside the CAS
  commit it has.

## Open questions

1. **Where a component's state lives.** One state value per component, or several named states per component.
2. **Allocation.** Every transaction allocates a new state. Fine for logic; never inside `FrameHooks.run` or
   `Pacing`. Measure it on the tree's state, where commits are frequent.
3. ~~**The escape when F refuses safe code.**~~ **Ruled 2026-10-28: there is none, because F never refuses safe
   code** (rule 4).
4. **A step that waits on a person.** How the dialog step parks a chain and how the answer resumes it: the answer is
   a message with the chain's id, but the shape of that in the API is not drawn.
5. **Snapshots and large state.** A document's text: the gap buffer is not a persistent value. Either a rope, or the
   document is an effect-like mailbox with its own undo.
6. **Chains in the tree's own work.** Whether widget-internal flows (a drag, a typing run) are chains too, or stay
   inside the widget.

## Order of work

Each step lands on its own and leaves the suites green.

1. **This plan accepted**, and the three rules recorded in vexelray-framework's `threading.md` beside T7.2.
2. **A transaction on atchung's `State`, on one lane**: a mailbox and an outbox released at commit. Unit tests, no
   GUI. **Built 2026-10-28** (atchung `9ff6e18`), as `Ledger<T>`: a `State` owned by one tick `Inbox`, whose only
   committer is the ledger's, changed by `Step`s declared on its builder and sent as `Step.Transaction` messages on
   the ledger's topic. A step sends through an `Out` into the owner's outbox, which the tick already releases only
   at the end of the round; a step that throws has its sends taken back and commits nothing. Held by `LedgerTest`.
   Not yet: publishing each commit on a state topic (C says a read is a snapshot, and `state()` gives that; a topic
   comes when a subscriber needs to hear one), and the chain-run id, for which the probe's `Cause`, already carried
   through every outbox, is the likely carrier.
3. **The processor's step check (F)**, shared with `@View`'s lambda check. **Built 2026-10-28** as the processor's
   `StepCheck`:
   - it finds every `Ledger.Builder.step(name, body)` call in any source of the compilation (the processor now also
     asks for `*`, returning false, because a step is a builder call no annotation marks), and reads the body when it
     is a lambda or a method reference;
   - a method the body calls, or a method reference names, is read too when its source is in the compilation, so a
     step written as a static method, or leaning on a helper, is checked through; an instance method reached that
     way has its own `this`, and so does a class declared inside the step;
   - what it cannot read (a library, an interface, a body held in a variable) it lets through, and under a compiler
     without javac's tree API it reads nothing;
   - each error names the step, what it did and the fix, at the line that did it, once even when two steps reach it.

   Held by the processor's `StepCheckTest`: each refusal once, and the safe bodies that must compile (constants,
   locals, nested lambdas, `StringWriter`, a `File`'s name, the thread's name, an anonymous class's own field, an
   interface call). `@View`'s lambda check, when `@View` comes, is the same scanner over a different call.

   **When it reads, fixed the same day:** the check first read bodies while annotations were processed, and asking
   javac what a name means in a tree it has not attributed makes it attribute the class there and then, rewriting
   trees in place. The trees outlive the round, so every record with a compact constructor came back to the real pass
   with a `super()` call javac refused as the source's. It now reads each class at its `ANALYZE` event, after flow
   analysis and before desugaring, as javac plugins do. A helper in a class analyzed earlier has been desugared since,
   so a call into one is not read and is let through (rule 4). Held by `aRecordWithACompactConstructorStillCompiles`.
4. **Chains**, with one id per run and its tracing. **Built 2026-10-28** as atchung's `Chain`, on ledgers:
   - a step gives its outcome through its `Out` (`out.result`), since what it returns is its ledger's state; a step
     that gives none ends the run;
   - every link after the first names the type it takes (`.then(Type.class, ledger, step)`), and an outcome of
     another type ends the run, so the chain is a line with an exit at each link rather than a tree;
   - going on is a send in the step's outbox, taken back if the step throws;
   - a step that waits gets a `Chain.Resume` from `out.hold()` and keeps it in its state, and a later step on the same
     ledger calls `out.resume(held, outcome)`. That answers open question 4 for now. A run resumed twice goes on twice;
     nothing stops it yet;
   - every run has an id from one counter, the same through every step and across a hold (`out.run()`), and the
     probe marks each step taken and each run's end. It is not yet the probe's `Cause`.

   A step is checked when the chain is built: one not declared on its ledger is refused then, not when a run reaches
   it. Held by `ChainTest`, on the witness below (view, editor and dialogs ledgers, clean and dirty), in atchung.
   **Branches, built the same day:** a link has alternatives (`.then(Save.class, ...).or(DontSave.class, ...)`),
   tried in the order declared, as `catch` clauses are. An alternative an earlier one in the same link already takes
   (its type a subtype of the earlier one's) could never run, so it is refused when the chain is built, as javac
   refuses an unreachable `catch`; the other way round is a fallback and is allowed. Whichever alternative ran, its
   outcome goes on to the next link, so branches join; a branch that should end gives no outcome or one the next
   link does not take, as `Cancel` does. The chain stays a line of links rather than a tree, so it reads top to
   bottom. Held by `ChainTest`.
   Not yet: the step check (3), which these steps do not have.
5. **Undo by snapshot** for one mailbox, then across a chain run. **Built 2026-10-28** as atchung's `Undo`:
   - a ledger built `.undoable(undo)` tells the history, in the same round's sends, each change it commits: the value
     before and after, and its group, which is the chain run it was in, or the run its step resumed, or a fresh id for
     a change in no run;
   - a group keeps, per ledger, the value before its first change and after its last. Undo puts the first back, redo
     the last, so nobody writes a reverse;
   - **undo and redo run on the tick's coordinator, between rounds**, when no step of any ledger is running. That is
     the one change to the tick: a step may queue work for the coordinator (`Out.between`, package-private), which runs
     after the round and before the outboxes are emptied, and is taken back if the step throws. It is what makes a
     group's restore all or nothing across ledgers without a lock: the check and the restore see one moment;
   - **the conflict check compares values (`equals`), not versions.** A restore is a commit, so a version check
     would refuse the group below for the version the group above it moved; and two equal values are the same state,
     so nothing a value check lets through is lost. It is undo.md's rule 6 ("the target still holds the value this
     edit left behind"), applied per ledger. A group whose changes to one ledger were interleaved with another cause's
     is marked tangled and conflicts too;
   - a conflict moves nothing and is said on `outcomes()` (`UNDONE`, `REDONE`, `CONFLICT`, `EMPTY`), the policy
     undo.md calls *block*; a restore is not recorded; a new change clears redo; the history keeps a depth.

   Held by `UndoTest`: single changes latest first, a chain run undone on every ledger at once, a resumed run's
   answer undone with it, a conflict that leaves both ledgers as they were, and depth. Not yet: undo.md's reveal step
   (navigate to what is about to change), which needs a target per group and belongs with the GUI; and effects'
   declared inverses or barriers, which wait for a mailbox that does effects.
6. **The witness: closing a dirty document** in the template. It crosses a view, a controller, a dialog and undo,
   and it is the chain the rules above were written against. **The controller's half built 2026-10-28**, as the
   template's `-core`:
   - the `Editor` is one ledger whose state (`Editor.State`) is everything it knows, the session's shape and what it
     is in the middle of: a question, a set of saves, a restore. The maps of callbacks, the batch protocol and the
     counters it kept as fields are gone, each now a value in that state;
   - every decision is a static method declared as a step, which the step check reads; each message the editor acts
     on runs one (`Ledger.Builder.on`), so the view and the jobs stay plain publishers;
   - closing tabs and quitting are one chain with two ways in, started by a plain message (`Chain.Builder.on`): the
     first step decides whether anything is unsaved, `ask` holds the run for the person, the answer resumes it into
     save, discard or stop, `saveThem` holds it until every write has landed, and `finish` closes the tabs or answers
     the close gate. Every way through answers a gate exactly once;
   - the disk is jobs, the session's reading and writing included (`ReadSession`, `WriteSession`), and so is
     anything about the platform (where the application started, a new file's line endings), so a step reads only its
     state and its message;
   - held by the generated project's `EditorTest`, on a tick of its own: 19 tests, the close ones the witness.

   The editor is a `@Component` that takes its `Inbox`, so the generated wiring builds it on the logic lane (phase 4,
   begun the same day).

   **The view, the same day:** a `@View` on the GUI thread (T7.4) that hears the widgets and the editor as messages
   and sends through declarations, so the witness now runs in a real window: Ctrl+W on an unsaved file asks in a
   dialog, and Save writes the file and closes the tab (driven with ottermate).

   **Not yet, and why:** the view's link and undo. The view hears in the frame and keeps the widgets, and it is not a
   ledger, so the chain's first link is still the editor's step, started by the view's `Close` message, rather than a
   step of the view's. Undo of a close would need the view's tabs and texts restored with the editor's
   state, so it waits for the view's ledger too.

## Proposed 2026-11-02: chains as trees, with bounded waits

*Not ruled. Converged with the author over one conversation the same day; each proposed ruling below carries its
reason, and the questions still open are at the end.*

### Why

**Found writing the template's first worked example** ([template-examples.md](template-examples.md), A8): "wait until
the typing stops" cannot be written as a step. A held run resumes only when some message arrives, and nothing a step
can reach sends one after a while has passed. `Timer` can, but it is an object a component holds and calls; the one
use of it on 0.2 (the Inspector's `Bench`) starts a period in its constructor and never stops it. The one way with
today's parts is a `@Job` that sleeps and replies, which holds an offload thread asleep per keystroke on a bounded
lane and cannot be cancelled. Search as you type, autosave after a pause, a timeout on a job's reply and a retry are
all the same missing piece.

**The second finding, the author's:** a run can stay open forever today, and nothing says so. A step that holds a run
and is never resumed (the forgotten resume) answers no close gate and draws nothing wrong:

> we need to ensure, also, that some timeout is configured at every link of a chain, otherwise a chain could stay
> open forever. That's the same degree of failure as the window freezing. Sensible defaults. Can be overwritten
> explicitly in the chain, also: it should be possible to unset a timeout too. I'm not trying to babysit the
> developer, I'm trying to make the easiest implementation the one least likely to cause problems.

**How it converged.** A timer a step could ask for came first, and was dropped for the author's *a tree rather than a
chain*. Then not one kind of branch:

> I don't think there would always be "one branch wins". I think that's just one of the kinds of branches there are.
> Specifically, an "OR" branch - that's last-one-there-gets-de-rezzed. There's also an "AND" branch, maybe a "NOT"
> branch?

named ANY and ALL so that they are not read as boolean logic or confused with `.or`, with NONE to match. Then a
timeout for the whole chain, which the author turned into composition: `chain.asBranch().within(...)`, *so you can
connect different chains together to make a tree type deal*. That removed the last special case, and the structure
came out recursive:

> When you've converged on a recursive data structure, that's usually a good sign.

### The structure

```
chain   = link, link, ...                    a line, read top to bottom, ending in an outcome of type R
link    = step | wait                        a step on a ledger, or a node where the run stops for things to arrive
wait    = ALL | ANY | NONE  over branches
branch  = message | time | chain             each with a bound
```

A chain is a line, as now. The tree is in what its waits wait for, and a branch may be a whole chain, so flows
compose: ALL over two chains is two flows in parallel that join; ANY over two is a race between flows; a chain used as
one branch, with a bound, is a deadline for the whole of it.

### The three kinds of wait

| Kind | Goes on when | With | Fails with | What it is for |
| --- | --- | --- | --- | --- |
| **ALL** | every branch has arrived | all their outcomes, as one record | `Missing`, naming what had not arrived, when its bound runs out | two jobs, or two flows, then go on |
| **ANY** | the first branch arrives; the rest are cut off | that branch's outcome | `TimedOut`, when its bound runs out first | a reply or a deadline; a race |
| **NONE** | nothing in its branches arrives before its bound | the bound's outcome | `Broken`, carrying what arrived | nothing typed for 300 ms: a debounce; "unless cancelled first" |

**NONE's bound is its meaning.** That nothing happened can only be seen when a window closes, a time or another event
(`.within(time)` or `.before(Type.class)`), so a NONE without one does not build.

**Today's `.or` keeps its name and meaning.** It is an exclusive choice by the type of one outcome, a fork in the road,
and nothing races. ANY is a race. BPMN keeps the same two apart, as an exclusive gateway and an event-based one.

### What it looks like

Illustrative; the names are proposals.

```java
// Search as you type. Each keystroke starts a run; the next keystroke breaks the waiting run's NONE, which ends it.
Chain.start("search", finder, Finder::typed)                  // Typed: remembers the query
     .none(Typed.class).within(QUIET)                         // nothing typed for QUIET
     .then(Quiet.class, finder, Finder::search)               // sends Find to the job; Broken goes to no link: the run ends

// A timeout on a job's reply, said to the person.
Chain.start("open", editor, Editor::open)                     // sends Read to the job
     .any(FileRead.class).within(SLOW)
     .then(FileRead.class, editor, Editor::show)
     .or(TimedOut.class, editor, Editor::stillWorking)

// Two flows, then go on.
Chain.start("compare", editor, Editor::compare)               // sends Read twice
     .all(Left.class, Right.class).as(Both::new).within(SLOW) // record Both(Left left, Right right)
     .then(Both.class, editor, Editor::diff)
     .or(Missing.class, editor, Editor::sayWhatDidNotCome)

// A chain as a branch: written once, used in close, quit and autosave, with a deadline on the whole of it.
Chain<Doc, SaveAsked, Saved> save = Chain.start("save", editor, Editor::save)
        .then(Written.class, editor, Editor::saved)
        .build();

Chain.start("close", editor, Editor::closeAsked)
     .any(save.asBranch().within(Duration.ofSeconds(10)))
     .then(Saved.class, editor, Editor::finishClose)
     .or(TimedOut.class, editor, Editor::sayStillSaving)
```

### Proposed rulings

1. **Three kinds of wait: ALL, ANY, NONE.** *Reason:* they are quantifiers, so they say what a wait waits for, and
   they cannot be mistaken for boolean logic or for `.or`. They cover every case found: debounce, timeout, join,
   race, "unless cancelled".
2. **A branch is a message, a time, or a chain.** *Reason:* the chain branch makes flows composable and reusable (save
   written once, used in three places), and it makes a deadline on a whole flow the same thing as a deadline on one
   reply, so neither needs a mechanism of its own. A recursive structure with one rule at each level is the smallest
   that covers it.
3. **Every branch and every hand-written hold has a bound, and the framework supplies one when the chain does not.**
   *Reason:* a run that can stay open forever is a hang that has not been noticed yet, the same degree of failure as a
   frozen window. Supplying it means the easiest chain, one that says nothing about time, is the one that cannot hang.
   A step is not bounded this way: it runs inside a tick, and the watchdog already holds it.
4. **The defaults depend on what is waited for.**

   | Waiting for | Default bound | Why |
   | --- | --- | --- |
   | a reply to a request the run made | a time (`DEFAULT_REPLY`, see open question 1) | a stuck job is the usual invisible hang |
   | a hand-written `out.hold()` | the same time | the forgotten resume |
   | the person, through the framework's dialogs | the dialog: closing it unanswered ends the wait with `Dismissed` | a person may take an hour, and a timeout there would be wrong; the framework already guarantees a dialog an answer, as it answers a close gate exactly once, so the wait is bounded by an event |
   | a chain used as a branch | none of its own: each of its waits has its bound, so it ends | a fixed number of links, each bounded, is bounded |
   | NONE | none: it states its bound or does not build | its bound is what it means |

5. **A bound is set at three levels, the most specific winning, and switched off with `.forever()`.** `.within(Duration)`
   on a branch or a hold, a default on a chain's builder, and the application's default in its configuration over the
   framework's. `.forever()` takes no reason and needs no ceremony; the Inspector lists the open runs waiting forever.
   *Reason:* the author's: not babysitting the developer, but making the easy path the safe one. A deliberate
   `.forever()` reads as a choice in review, and stays visible while it runs.
6. **Failure is an outcome, taken by type.** `TimedOut`, `Missing`, `Broken` and `Dismissed` are the framework's
   records, and the next link's `.or` takes them as it takes any outcome. An outcome no link takes ends the run, as
   now, and the trace says why. *Reason:* one way to branch, which the processor checks and which reads top to bottom.
   An ELSE keyword would be a second way to say the same thing, and a bare one would throw away what the failure
   carries (which branches were missing, what broke the NONE). A catch-all is `.or(Object.class, ...)`, which chains
   already allow after more specific alternatives.
7. **A default bound running out is a bug; an explicit one is behaviour.** An explicit `.within` that runs out is
   traced. A default that runs out is logged at warn, and **fails a test** under the test clock, as `Cuts` fails a
   view that changed in one frame. *Reason:* the developer who wrote `.within` expected it; the one who wrote nothing
   did not think about it, so it should be loud where it is cheap to hear (tests, the log) and harmless where it is
   not (the run ends, the window stays live).
8. **What cutting off a branch means.** A cut message or time branch: its late arrival goes nowhere, and is traced as
   arriving after its run moved on. A cut chain: it stops at its next wait (a running step is never interrupted), its
   committed changes stay, and its run is in the parent's undo group, so the parent's `.or(TimedOut...)` may undo it.
   Nothing is undone by itself. *Reason:* a job already running cannot be stopped mid-way, and a chain is atomic per
   step and a saga as a whole; what a loss should cost is the application's to say, at the one place that handles it.
   Tracing the late arrival keeps a dropped reply from hiding a bug.
9. **Which message belongs to which run is decided by where it comes from.** A reply to a request the run made is
   matched by run id, stamped on the request and carried by its reply by the framework, never by the application. Any
   other message (a keystroke, a source's output) is matched by its type. *Reason:* two opens of two files at once
   must not take each other's replies, and keys written into an application's messages would be invented per
   application, and wrong at run time with no error. Jobs are already a request with a reply-to (components.md
   section 10), and the probe's cause already travels through every outbox, so the carrier exists. A keystroke is a
   reply to nothing, and the debounce wants any keystroke.
10. **A wait hears only what arrives after it is entered.** *Reason:* the message that starts a run must never break
    or satisfy that run's own first wait. The debounce depends on it (the keystroke that starts the new run is not a
    keystroke inside its window), and one sentence covers every kind of wait, with no rule about the order of two runs
    within one delivery.
11. **ALL's record is named at the wait and built by a constructor reference** (`.as(Both::new)`). *Reason:* it is
    typed and checked by javac, a constructor reference captures nothing (rule 1), and the processor can check the
    record's components against the branches. A record the processor generated would be one the application never
    wrote, and a `List<Object>` throws the types away.
12. **A chain declares what it ends with**: `Chain<T, A, R>`, and a chain used as a branch starts with the outcome
    before it. *Reason:* a parent has to know what a sub-chain hands back for javac to check the join, and a chain's
    start already checks the type of what starts it.
13. **A sub-chain's run is a child of the run that started it.** *Reason:* the trace becomes the click's whole causal
    tree, which the Inspector can draw, and the undo group of rule 8 is the parent run, whole.
14. **A time branch is atchung's to declare and the tick's to be given.** The tick takes something that schedules a
    wake: the framework hands it its `Timer`, and a test hands it a clock moved by hand, so a thirty-second timeout is
    tested at once. A time branch that fires is an input, recorded like any message from outside. *Reason:* atchung
    has no clock and takes no thread, and the framework owns the threads (T2.5); recording it keeps replay (ROADMAP
    *Done means* 5, and G above) independent of the real clock. No application part holds a timer for this, so none
    can leak one.
15. **No repeat.** *Reason:* a loop inside a chain stops it reading top to bottom, and the debounce already shows the
    shape that replaces one: the next cause starts a new run.

### What it changes

- **Step 4 above said the chain stays a line, not a tree, so it reads top to bottom.** It still reads top to bottom:
  each chain is a line, and the tree is in what its waits wait for and which chains they compose.
- **Most hand-written holds go.** A step that holds a run and keeps its `Resume` in state for a later step to find
  becomes a wait the chain declares. `out.hold()` stays for what a wait cannot say, now bounded by rule 3.
- **A run resumed twice goes on twice** (step 4) stops being possible: an ANY goes on once, and a late resume after a
  bound has run out is refused and traced.
- **`Chain<T, A>` becomes `Chain<T, A, R>`.** The template's close chain is the one caller, and is changed with it.
- **The template's worked examples A7 and A8** ([template-examples.md](template-examples.md)) are written on this
  once it is built.

### Open questions

1. **The default for a reply.** Long enough that reaching it means something is wrong, not that a job was slow; a
   tiled render or an export says `.within` or `.forever()`. Thirty seconds is a placeholder, to be set from what the
   applications' jobs actually take.
2. **Undoing a cut sub-chain.** Undo restores latest first, so undoing a cut child after other runs have committed
   can conflict. The conflict check refuses rather than corrupting anything, so this is a limit, not a hazard, but
   "the loser can always be undone" is not yet true.
3. **N of M**, ALL with a count (a quorum, any two of three mirrors). An addition when a real case asks.
4. **Whether a default bound running out should also fail a run in debug builds**, not only in tests. Rule 7 says
   tests and the log; a debug build could say it on screen, if the log proves too quiet.

## Decided

The four rules above, 2026-10-28 (rule 4 after the plan was first written).

**A plain message runs a step, and starts a chain** (2026-10-28, building step 6). `Ledger.Builder.on(topic, step)`
and `Chain.Builder.on(topic)`, additions to atchung. *Reason:* a job's reply, a view's report and a close gate's
request come from senders that do not know the ledger, and must not: a job that published
`editor.fileRead.with(reply)` would depend on the editor's steps, which is the coupling messages exist to avoid. The
receiver declares what it hears, as `@Subscribe` does, which is the shape a component's subscription takes when its
handler is a step. Rule 3 still holds: the steps are the editor's, declared on its state, and the chain is written
where the editor first hears its cause.

**Lists cross in payloads** (ROADMAP fork 6, built the same day): the processor accepts a `List`, `Set` or `Map` of
shareable values inside a payload, and a record holding one copies it in its constructor. *Reason:* "close these
five tabs" was five messages with a batch id and a count, a protocol written around the checker; and that the list is
unmodifiable is the sender's part, which rule 4 lets the check leave to it.

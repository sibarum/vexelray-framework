# Transactions and chains: a function sent to the state that runs it

Status: **Direction ruled 2026-10-28, not started.** The author's words first, then the rules settled in the same
conversation, then the design, what it changes in the 0.2 roadmap (`ROADMAP.md` beside the `v0.2` checkouts), the
questions it leaves open, and the order of work. It builds on the 0.2 message model, on vexelray-gui's
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
3. **The processor's step check (F)**, shared with `@View`'s lambda check.
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
   and it is the chain the rules above were written against.

## Decided

The four rules above, 2026-10-28 (rule 4 after the plan was first written).

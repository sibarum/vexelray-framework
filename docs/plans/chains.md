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
   captures anything, reads mutable static state, or calls what is not on its allowlist. A non-capturing lambda is a
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

At compile time, through javac's `Trees` (the machinery `@View`'s lambda check needs):

- a step captures nothing but its parameters;
- reads no mutable static and holds no reference beyond what it was given;
- calls only an allowlist of JDK methods and steps or functions that are themselves checked;
- makes no blocking call (I/O, locks, waits, sleeps);
- takes and returns only values.

It cannot rule out an endless loop; the watchdog stays.

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
3. **The escape when F refuses safe code.** A false refusal will happen. What an author does then, without leaving
   debt behind (a reviewed allowlist entry with a reason, or nothing).
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
   GUI.
3. **The processor's step check (F)**, shared with `@View`'s lambda check.
4. **Chains**, with one id per run and its tracing.
5. **Undo by snapshot** for one mailbox, then across a chain run.
6. **The witness: closing a dirty document** in the template. It crosses a view, a controller, a dialog and undo,
   and it is the chain the rules above were written against.

## Decided

The three rules above, 2026-10-28.

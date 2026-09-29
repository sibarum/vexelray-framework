# TODO

Work on this framework that is known about and not done, organised by [what v1 means](v1.md): **an
application built on v1 does not need a major refactor, and later releases add to the abstractions v1
already has.** So the first question about an entry is not how big it is but whether landing it *after* v1
would break an application that compiled before. Most of it is what the ports turned up — what
[the text editor found](architecture.md#what-porting-the-text-editor-found), and what
[the designer found](architecture.md#what-porting-the-designer-found) — where the gaps each port
closed, and the reasoning behind each, are recorded.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than
ticking it. An entry whose fix belongs in a sibling repo says which one; the ones under **Upstream**
cannot be fixed from here at all. An entry moves between sections when the answer to *would landing it
later break an app?* changes, and says why.

## Blocks v1

Landing any of these after v1 would break an application that compiled before, or change what an
application already depends on. Ordered as [v1.md](v1.md#how-to-get-there) orders the work.

- [ ] **The container now gives a component a thread and a mailbox; what is left is the colour rule.**
      `Shell.lanes()` owns the application's threads and `Shell.place(name)` puts a component on one of
      them with its mailboxes, its wake and its drain-then-stop — `Lanes` in `-core` (pure JDK, so the
      container stays testable with no GPU) and `Placement` in `-shell` (a mailbox is atchung's, and that
      edge already falls there). The upstream half landed with it: `Gui` takes both lanes rather than
      building a `newCachedThreadPool` whatever it is handed, and closes only the lanes it built itself.

      **Why this blocks v1:** a rule that rejects code v1 accepted is a breaking change, so the copier and capture halves, the message-graph checks and supervision land before v1 or behind an opt-in. They also decide what a component owes the rest of the application, which is how every component is written.

      **Thirteen of [threading.md](threading.md)'s rules are now held.** Eight came from these two objects, and
      none of those promotions needed the processor — they needed something to *be* the rule; the other five
      are the processor's. The `upstream` column is
      down to one entry, because T1.2 and T1.5 turned out to be the same change.

      **What is actually left**, and it is the part a runtime object cannot hold: the colour rule's copier
      and capture halves (T2.4, T2.5 — T2.1–T2.3 are the processor's now, and held), the message-graph
      checks (§4.1, §4.3–§4.5), and supervision (§6.5, §6.3) — *nothing
      notices that a component has stopped draining, or names it*, which wants `Overrun` surfaced per
      grouping before it is built on anything but a timeout. Plus the handler lane's bound, which waits on a
      census of what still blocks on a handler rather than on any one known blocker.

      **Ruled 2026-09-28: the component model — multithreaded by default — is a design seam frozen at v1.** Groups, lanes, channels, loss
      classes and supervision are not deferred behind the version number: v1 ships them declared, checked and
      frozen, or it does not ship. That makes the declaration side a v1 build item and not a later feature, and
      it makes the eight decisions in the threading audit (the imperative path, public `Shell.place`, `lane`,
      group = lane, the `@Subscribe` defaults, supervision, the landing rule for checks, the tree) rulings owed
      before the seam is built. There is no single document for it yet; see the overview entry below.

      **Ruled: there is a default component lane, and it is not the GUI main thread.** A component that
      declares no `lane` runs on it, so all such components share one thread by default; `lane` is an optional
      override, and two components naming the same lane share that one instead. The main thread stays a
      separate colour (`@MainThread`, never a lane), and the default lane is a lane of its own beside the
      handler and offload lanes in `Lanes`. The default is the contract, because moving it later changes timing
      and blocking behaviour without a compile error. This is what makes *multithreaded by default* cheap: the
      thread count does not grow with the component count, and a plain service costs nothing to make a
      component. **What it costs:** one slow component holds up every other on the
      default lane, so isolating one is `lane = "..."`, and supervision has to name a *lane* that has stopped
      draining, not only a component.

      **Built:** `@Component`'s `lane` is optional and defaults to `Component.DEFAULT_LANE` (`"<default>"`),
      which is one shared placement, so one thread. It is reserved by construction: the processor requires an
      explicit lane to be a name (`[A-Za-z][A-Za-z0-9._-]*`), which the default is not. Nothing to bound: the
      lane is one thread and each mailbox already has a capacity. Default-lane components may hold each other
      and not a named-lane one (T2.3). **Still open:** `Placement.superseded()` reads the lane's shared pump,
      so on a lane with several components it also sees a neighbour's mail.

      **Ruled: the default lane is under supervision.** The framework watches it always on, as `Stalls` does
      the main thread, and a lane that has stopped draining is reported by name. That puts the per-lane
      liveness signal in `Lanes` and `Placement` on the v1 build list, at least for the default lane, and it
      is the case that made the observer more than optional: one slow component holds up every other on it.
      **What supervision does is still only report**, and that is frozen: a component's failure never stops
      the others, a stalled lane is named and not killed by the framework on its own, and richer policies are
      additive (superseded below: the default action is now not contractual). **Open:** the stall
      threshold and where it is set, whether lanes an application names are supervised the same way (the
      mechanism is per-lane, so probably), and what a report looks like to an application that wants to react.

      **Ruled: the liveness guarantee** ([v1.md](v1.md#the-liveness-guarantee)). A wedged component never
      stops the window responding, the user always being able to close, or the application ending itself. That
      turns three things into v1 items: main-thread code may not send on a blocking channel (a processor check,
      and `Backpressure.BLOCK` from the main thread is the freeze the upstream-topics entry already found),
      close has a total bound after which the process halts, and the watchdog is a thread on no lane with an
      explicit `halt`. **To confirm:** the bound (a number and where it is set), and whether a user's close
      halting the process after it is a default action. It is user-directed rather than autonomous, so
      recommended yes. **Test it in W2:** a *wedge* button, then assert the window still repaints and takes
      input, and that close exits within the bound.

      **Ruled: what a wedge does is a policy the application can override, and the default is not part of the
      contract.** The frozen parts are the liveness guarantee and the seam that takes the policy, a provider
      handed back like the look and the input backend, told what happened and acting through a context object
      (`exit` now, `restart` later), never returning an enum. The default today is report and then exit,
      matching the bus-fault precedent. The target is a main-thread fallback offering the user a restart.
      **Realism, unverified:** a fallback on the main thread works for a wedged *lane*, because the default
      lane is not the main thread, and cannot work for a wedged main thread, where the way out is halt.
      *Restart* comes in two sizes. Relaunching the process (start a new one from the same command, then halt)
      is small and needs only what the app already persists. Restarting components in place is not: Java
      cannot stop a wedged thread, so the old one would be abandoned and could wake later on stale state, and
      the container builds each part once, so it would need a rebuildable subtree and the tree's depth-first
      teardown (T6.3), which is unbuilt. Do the first as the target and leave the second to an opt-in policy.
      A default of *exit* on a stall also throws away unsaved work, so the threshold has to be long and the
      policy needs a bounded chance to save first.

- [ ] **One overview of the component model.** Its requirements are in threading.md (the 32 rules),
      architecture.md (*The concurrency model*, from line 231) and four entries here, and nothing states what a
      component is owed, what exists, and what is decided. Written as the spec the declaration seam is built
      against, and it is where the eight rulings are recorded.


- [ ] **One seam carries three of the four differentiators, and it is the one not built.** A
      `@Subscribe` that generates its `Pump` registration in `ATTACH` and its teardown in the
      `Disposer` is at once the actor model's substrate (above), the extension and macro API, and the
      reactive half of automation. That third one is easy to miss: `Automation` is command-in,
      response-out — `click`, `type`, `shot`, `await` — so a user-authored macro can *drive* the
      application but cannot *react* to it, and an extension that wants to run when something happens
      has nowhere to attach. All three want the same thing, which is why it is worth building once
      rather than three times.

      **Why this blocks v1:** the [extension test](v1.md#the-extension-test) fails on it twice: elektro-Q and reactive automation have nowhere to attach without it. It is also where a component declares its channels, so it reshapes component code written before it exists.

      **First slice built (component mailboxes).** `@Subscribe(topic, overflow = FAIL, capacity = 64)` on a
      `@Component` method registers a mailbox on the component's lane where the wiring constructs it, with the
      payload type taken from the method's one parameter; the placement's close is the teardown, so there is
      nothing to generate for it. `@Publishes` declares what a type or method sends on. Held by them: T4.1 (an
      edge and a sample on one topic, or one topic name with two payload types), T4.3 (a mailbox per method),
      and T4.7 (main-thread code declaring a send on a `BLOCK` topic). **Not built:** the rest of the seam. A
      `@Subscribe` that is not on a component (an extension or a macro, which is the whole point of the entry),
      registration in `ATTACH` and teardown through the `Disposer` for something that is not a placement, `Fold`,
      the blocking-cycle check (T4.4), and the graph being *declared in the wiring* (T4.5). The three
      `@Subscribe` defaults (FAIL, 64, registered at construction and started with the lane) are unruled; they
      are the ones a v1 freeze would make contract.

- [ ] **Nothing in the model covers work that outlasts a frame, and the stack already named the
      answer.** `kronometer/docs/architecture.md` §10 specifies it: *"`offload(work)` remains available
      for work that is genuinely unbounded — file I/O, network, image decode — moving it to an ordinary
      executor (**its own**, never the kernel's single carrier) and delivering completion as a timeline
      event."* Specified and unbuilt — `offload` appears in no Java source in that repo. The framework
      wants the same lane, and naming it here is what stops the component model being read as the answer
      to a question it does not answer.

      **Why this blocks v1:** the completion path is what application code will call to get a result back onto the frame loop. The pool is the easy half; the way back through the timeline is the API.

      **A pool does not contradict static placement.** A component is placed statically because it is
      stateful and ordered; an offloaded task is stateless and unordered, so there is nothing to confine
      and no sequence to keep. The edge is policed by [the crossing
      rule](architecture.md#what-may-cross-a-lane-and-what-crossing-does-to-it) — a value crossing a lane
      arrives as if it had crossed a wire — which makes an offloaded lambda that captures a component's
      state a compile error rather than a race. Capture is the one case the rule refuses rather than
      copies, because a lambda closes over a reference and no copier can be slipped in behind it.

      **Platform threads, and the reason is not the component one.** Blocking I/O is the textbook
      virtual-thread case, but §3.1 records that `jdk.virtualThreadScheduler.parallelism` is a global JVM
      property with *"no public per-thread scheduler"* in JDK 25 — so an application that takes
      Kronometer's 3× baton flag has no second carrier to give this pool, and the obvious choice is the
      documented deadlock again. That flag is the application's and correctness never depends on it, so
      the framework's default has to be the one that is right when it *is* set.

      **The completion path is the content; the pool is the boring half.** A result is published on a
      `Topic` and folded into a `Cell` by `KronBridge`, or dropped on a queue drained in
      `FrameStage.APP` — the two doors that already exist, and `APP`'s own list is *"a history to
      restore, a file to open, a preview to render."* What an offload thread must never do is touch the
      tree or the timeline in place.

      **The lane now exists.** `Lanes.offload()` is bounded, platform, and separate from the handler lane;
      `Gui.offload()` is the same lane reached from a widget. Two lanes rather than one, mirroring the split
      Kronometer already makes between the precompute pool and `offload`.

      **What this entry got wrong is worth keeping.** It said `FileActions` called `Files.write` and a
      blocking `FileDialog.save` *"inline on a handler thread"*, sharing an unbounded lane with click
      dispatch. It did not: every command there goes through `GuiApp.post`, so the I/O was on the **GUI
      thread** — which is the right place for the dialog, whose contract requires it, and a worse place for
      the blocking call than the handler lane would have been. A read from a mount that had gone away held
      the frame loop rather than one document. Both are now on the offload lane and land back through
      `app.post`; the dialogs stay where they were. **The lesson is about the inventory, not the editor:** a
      claim about which thread something runs on was written once, was true once, and was not re-checked
      when `app.post` moved it.

      **Still to do**: `kronometer`'s own `offload` remains specified and unbuilt, and the framework does not
      yet route completions into the timeline — the second door (a `Topic` folded into a `Cell` by
      `KronBridge`) is unused, and everything that lands today takes the first one.

- [ ] **The wiring is generated; what it does not do yet.** The processor writes `<App>Wiring` from
      `@VexelApp`, `@Provides`, `@Component`, `@Setting`, `@BeforeFrame`, `@OnMode` and `@ConditionalOnType`,
      the `vexel-desktop` template ships a `Recipes` configuration instead of a hand-written wiring, and
      `-Pacceptance` builds and drives the result. The **resolution rules are the contract** — an application depends on which provider satisfies a parameter without ever writing it — so the decisions below are v1 items and the additions are not. Each bullet says which:
      - **Additive, once the rule is written.** **Most of the framework's defaults still cannot be replaced.** The look, the input backend and the
        clipboard can — a `@Provides` returning one is handed back to `Shell` (see architecture.md, *the
        framework's own defaults are handed back*). Window memory, the icon, the dialogs and pacing cannot, and
        the README lists them among the defaults. Each wants a reason to be replaced before it gets a setter;
        none has one on the stack yet.
      - **Additive, but before a BOM.** **A starter is not checked where it is compiled.** `AutomationStarterTest` compiles applications
        against `AutomationStarter` with the real processor, which covers the one starter there is; the
        processor is not on `-automation`'s own `annotationProcessorPaths`, so a starter's library-level checks
        run only when an application names it. Worth wiring when there is a second starter, and before a BOM.
      - **Decide now.** **A provider returning `null` is passed on as `null`.** `@Provides` says absence is a supported answer,
        that dependents which tolerate it still build, and that the framework reports it once. Today the
        dependents are built with the `null` and nothing reports it; the consumers the wiring itself calls
        (`appearance`, `register`, the hooks) are guarded, and that is all.
      - **Decide now.** **Types match exactly.** A parameter asking for an interface is resolved only by a provider
        returning that interface, never by one returning a subtype. Deliberate for now — a subtype match is
        the ambiguity `@Default` exists to rule out — but it is a decision nobody has argued.
      - **Additive.** **One round.** The graph is taken in the round the `@VexelApp` is seen, which is every source of a
        clean build; anything another processor generates in a later round is not in it.
      - **Decide now.** **Two promises need a method body.** A `@Provides` calling another directly, and T2.5's capture
        rule, are both about what code *does* rather than what it declares. The Trees API can read them;
        doing so is a decision, since *the processor reads declarations* is load-bearing in
        architecture.md's argument about placement.
      - **Before v1, with the component model.** **The copier (T2.4)** and the message-graph checks (§4) wait on the seam that declares a channel.

- [ ] **`vexelray-engine` is a second composition root, and `GuiApp` is the first.** The new module (in
      `../vexelray`) exists because *"six demos each carry a copy of eighty lines of
      instance/device/swapchain/presenter wiring"* — and `GuiApp` is a seventh, doing `new
      VulkanInstance`, `selectGraphicsPresentDevice`, `new VulkanDevice` and driving `WindowedPresenter`
      itself. Nothing is broken by that refactor: it kept every prior constructor and `Config` form
      deliberately, so the stack still compiles and this repo's tests pass against it. But
      `VexelEngine.create` resolves an `EngineProvider` through `ServiceLoader`, so if `GuiApp` is ever
      rebuilt on it the stack acquires reachability metadata an application inherits and this framework
      does not yet aggregate — the open question architecture.md already calls *"probably the
      highest-leverage feature not yet listed."* Nothing to do here until that seam moves; recorded so
      it is not a surprise when it does.

      **Why this blocks v1:** which root owns device creation decides what reachability metadata an application inherits, and so the build shape. Nothing to write until that seam moves, but the decision precedes the BOM.

- [ ] **`Gui`'s topics are `static`, so one bus can carry one tree** (`vexelray-gui-core`). Every
      instance subscribes to the same `vexelray.gui.mutations`, so two trees on one bus each receive the
      other's mutations — into a mailbox bounded at 65,536 with `Backpressure.BLOCK`, drained only while
      that tree is being presented. The second `Gui` fills and then blocks the first one's node setters
      for good: a freeze after tens of thousands of edits, with nothing thrown and nothing logged. This
      framework shipped it for three commits by putting `Modals` on `Shell.bus()`, and the designer found
      it by putting a second window there and watching the viewport stop marching.

      **Why this blocks v1:** it caps one fabric per application, so `Shell.bus()` cannot promise that a second window joins it. Either the fix lands upstream first, or v1 documents the ceiling as part of the contract.

      Constrained rather than fixed: `Gui(Atchung)`'s javadoc now says which things may share a bus.
      **It is also the ceiling on one inspectable fabric per application**, which is the point of having
      a bus at all — a second window cannot join it. The fix is topics named per instance rather than
      per class, and it is a real change in `vexelray-gui` rather than a line here.

- [ ] **What `Shell` hands over is the seventh surface, and it bounds the other six.** `Shell` returns
      `Gui`, `GuiApp`, `KronoGui`, `TitleBar`, `WindowMemory`, `Settings`, `CloseRequest` and
      `Atchung`, so an application holds `vexelray-gui`'s and `atchung`'s types directly and v1 is only as
      stable as they are. Either those repos freeze what is exposed, or `Shell` stops exposing it — a
      wrapper per type, or fewer accessors. A decision rather than a task, and it wants making per
      accessor rather than once. Every side of the edge is the author's own, which makes freezing cheap
      and makes not deciding the only expensive choice.

      **Ruled 2026-09-28: `Gui` is frozen** — the subset applications use, with the remainder marked
      experimental. `vexelray-gui/docs/plans/gui-decomposition.md` reports all seven decomposition steps done, and
      the last app-facing removal from `Gui` was `onCaretHit`/`onCaretDrag` on 2026-08-14; nothing local calls
      them. Still to do: classify the ~100 methods, and give `vexelray-gui` a marker of its own, since the
      framework's `@Stability` sits above it in the dependency order.

      **Ruled 2026-09-28: hide or delete what nothing needs; delete is preferred.** `Shell.dialogs()` is
      deleted: no caller, and it was not an injectable root anyone had a use for. Still open: `memory()` is an
      injectable root in the processor's table, and the text editor port needed `WindowMemory` injected
      (architecture.md, *what porting the text editor found*), so *no caller* is partly the deleted witness.
      `app()` cannot simply go: `Driver`, in a separate module, reaches the window's `WindowControls` through
      it, so the choice is a narrower accessor for exactly that, or an explicit host seam.

- [ ] **Witnesses: the abstractions have been drawn from one application shape.** The three ported
      applications were deleted on purpose, which was right, and left one generated counter-style
      application validating everything. *One component is not a census* applies to the framework itself.
      Wants two or three generated applications that differ where it matters — several windows on one
      device, component-heavy with real placement, long-running with nothing to settle — each expected to
      find something, and each finding written up in architecture.md as the ports were. After the
      component model, since that is what they most need to exercise.

      **W2 (component-heavy) and W3 (long-running) are built** and pass: [what W2
      found](architecture.md#what-the-component-witness-found) is mostly confirmation plus two observations, and
      [what W3 found](architecture.md#what-the-long-running-witness-found) is two real findings, both filed below.
      **Still to build:** W1, several windows on one device, which wants the `Window` seam and is meant to shape
      it. W2 also lacks a wedge that ignores interrupts, which is what would make the halt backstop fire, and W3
      is twenty seconds long, which rules out growth per tick and nothing slower.

- [ ] **Starters, a BOM, and the stack's reachability metadata.** The build shape is a contract: an
      application's dependency block and its `@VexelApp` are the first things it writes. One starter
      exists (`AutomationStarter`), so the abstraction is drawn from a single instance; a second starter
      is the test of it. The BOM and the aggregated native-image metadata are what make one dependency
      enough, and the metadata is what architecture.md calls *"probably the highest-leverage feature not
      yet listed."* After the component model, because what an application inherits depends on what the
      stack turns out to reach.

      **Ruled 2026-09-28: v1 may ship native on Windows only, provided adding another OS later needs no
      change to an existing application.** That makes where the metadata lives a v1 decision. It must be
      carried by each backend's jar (GraalVM merges `META-INF/native-image` from every jar on the classpath)
      or by a starter, never by a file in the application, or every new OS is an edit to every app. The
      per-OS backend selection in a starter has to meet the same test. Also missing, and first in line: no
      generated application has ever been built as a native image, so acceptance stops at a JVM build.
      **Refined 2026-09-29: Windows is the v1 requirement; macOS is wanted but not required; Linux is in scope
      and will become load-bearing, but is not a priority until a distro is chosen and gives it a real build target.** The additivity test above applies to all of them, so what
      v1 must get right is where the metadata lives and how a backend is selected, not the number of backends.

      **Ruled: every native extension is cross-platform in theory, or wrapped in a facade for the future.**
      Platform-specific code never appears in a public signature or in an application's build. The stack already
      has the shape (`NativePlatform` behind a `ServiceLoader`, `InputBackend`, `ClipboardBackend`), so the rule
      is that a new native piece follows it and that an existing one which does not is a v1 fix. **To audit,
      unverified:** which native pieces have no facade today. The reachability report names FFM bindings in
      `vexelray-os-windows`, `tactroller-clipboard` and `tactroller-linux`, a native-file-dialog loader that
      extracts a library from a jar, and bundled `natives/` for image decoding.

- [ ] **A `Window` seam instead of `memory()`, `app()` and one-off dialogs.** Each is a singleton accessor in a
      world where a window is not: the designer already needs two, and every one of them has to say *which
      window*. Adding that later changes the signatures apps call. The capabilities are reasonable to expect
      (per-window placement memory, title/size/maximise/fullscreen/close, and dialogs that can be answered by a
      test as well as a person), the types are not: `GuiApp` is the window, the GPU device and the main-thread
      boundary in one 41-method class, and `WindowMemory` and `Modals` carry their mechanisms. Sketch: one
      framework-owned `Window` handle, `shell.window()` for the main one and `shell.window("log")` for a named
      one, operations safe from any thread and posted to the main thread, with `Driver` using the same seam so
      `app()` can go. **Unbuilt and unwitnessed**: let W1 (two windows, in the witness specs) shape it rather
      than designing it first, then delete `memory()` and `app()` under the legacy sweep. Also the natural home
      for automation's missing `resize` and `zoom` verbs. `Shell.dialogs()` is already deleted.

- [ ] **Spike the extension test.** [v1.md](v1.md#how-future-proofness-is-measured): for each row of the
      extension table, a throwaway prototype against the API as it stands, and the number of existing
      signatures it forces to change recorded here. Candidates: the second window, a Linux backend,
      `@Subscribe`, supervision, a second starter. Zero is the target; anything else is fixed before the
      freeze. Do it with the witnesses, since both need the same applications.

- [ ] **Build the liveness guarantee.** Designed in
      [architecture.md](architecture.md#a-wedged-component-cannot-freeze-the-window), contract in
      [v1.md](v1.md#the-liveness-guarantee). **Landed, all tested with no GPU:** each component thread in its
      own `ThreadGroup` with `Lanes.interruptLane`; a per-lane busy gauge (`Lanes.busy`, set around every
      `Placement` delivery, so parked is idle and *inside one delivery too long* is a stall); `Watchdog` in
      `-core`, a thread on no lane that reports each stalled delivery once and ends the process with
      `Runtime.halt` (status 1) when a deadline passes; `Disposer.beforeClose`, which arms a total bound on
      shutdown; and `LivenessPolicy`, handed back with `shell.liveness(...)` or a `@Provides` returning it, with
      a default for every part. **Defaults, not contract:** stall threshold 10 s, shutdown bound 10 s, exit grace
      30 s (the time a close gate's save prompt has before the process is ended anyway), and on a stall report
      then exit. All three durations are the policy's to override.

      **Still to do:** the main thread is not watched, on purpose. It legitimately blocks in a modal native
      dialog for as long as a person takes, and a halt fired at someone choosing a filename is worse than the
      wedge, so it wants a heartbeat that knows a dialog is up, from the frame loop. The processor check that
      main-thread code sends only on channels that never block is built (T4.7) for declared sends only; see
      the `@Subscribe` entry. Cancellation tokens on `Lanes.offload()`, and
      the handler and offload pools are not grouped. `Context` has `exit` and `interrupt` and no `restart`.
      Verify the group sweep under native-image, untested. W2 gets the *wedge* button and the assertions that
      the window keeps painting and close exits within the bound.

- [ ] **A legacy sweep: v1 keeps nothing for backwards compatibility.** The second v1 condition, in
      [v1.md](v1.md#what-must-not-remain). Known candidates so far, each a question and not yet a finding:
      the prior constructors and `Config` forms the engine refactor kept on purpose; `--profile`, which parses
      and nothing honours; `shell.wake(gui::onWork)`, which duplicates what `GuiApp` already does; the
      `VexelApplication.run` overload taking a window factory, which looks like a test seam; `Gui`'s
      `hasPendingWork` (no caller), `keyRoutes` (no caller) and the `frameOwed` overlap; `Modals`' one-argument
      `install`, documented as a library default; the stale `sealed ... permits` in vexelray-gui's
      architecture.md §6; and the *deliberately not bounded yet* handler lane.

      **It changes one threading ruling.** Keeping `Placement.subscribe` supported *forever* beside
      `@Subscribe` is a second way to do one thing, which this condition rules out. So the choice is now
      either the imperative path is the supported, checked route and `@Subscribe` is sugar over it, or it is
      removed before v1 and the declaration seam has to exist first. It can no longer be "keep both".
      Wants doing after the component model and before the freeze pass, and binds the siblings `Shell` exposes.

- [ ] **The public surface has not had a freeze pass.** When the rest of this section is done: read every
      public type and ask whether anything would be renamed or restructured today, fix it, then mark what
      is frozen. This is the step that turns *nothing known is wrong* into 1.0. It cannot start early,
      because it is meaningless while something above is open.

## Can land after v1

Additive: a constructor parameter with a default, a test, a deletion that changes no behaviour, a new
module behind a seam that already exists. Worth doing, and none of it waits for the freeze.

- [ ] **`-diagnostics`, and move `FpsProbe` into it.** It is `text-editor-vexel-demo`'s, about 250
      lines, and generic apart from the one thing that makes it worth having: it deliberately pokes
      each wake path — a timeline post, a node mutated off the frame thread, a handler that changes
      nothing — to prove each still produces a frame. That is what `--profile` should turn on, and it
      is the module the architecture doc already lists as the Actuator analogue. Until it exists,
      `profile` is a name `Launch` reserves and nothing in the framework honours — documented on
      `FRAMEWORK_KEYS` and in
      [architecture.md](architecture.md#the-two-reserved-keys), along with why the real fix is
      `@ConditionalOnType` and not a runtime check.

      **The module can land after v1; its name cannot wait.** A module name is Maven coordinates an application writes into its build, so it is chosen before the freeze even if the module is not. **The name is now taken upstream.** `../vexelray` ships a `vexelray-diagnostics` for a different
      thing — the channel a seam uses to say it silently dropped a capability (see **Next**). Two
      concepts, one name, one stack; this module wants a name of its own before it is written.

      **And most of it may already exist.** `atchung-probe` is described as *"the stack-wide profiling
      seam: off unless asked, free when off, and dependency-free so any layer can take it without
      taking the bus"*, with spans, counters, a resource ledger, a CSV correlation mode and a
      `CsvView` that hunts a run for stalls. `Pump` instruments itself with it and `Automation`
      already imports `Probe` and `Lane`. So `--profile` may be a few lines turning `Probe` on rather
      than a 250-line port, keeping from `FpsProbe` only the part that is genuinely its own — that it
      deliberately pokes each wake path to prove each still produces a frame. Worth checking before
      porting anything, and more so under the concurrency model, where *"why did we miss a frame"*
      becomes *"which component's mailbox"* and a probe already threaded through the bus is what
      answers it.

- [ ] **A second automation socket, if a second application ever wants one.** `Driver` binds one, for
      the `Gui` the framework built. `vexelray-designer` needs two — `tree` on the first does not list
      the viewport and `shot` on it photographs the wrong window — and places the second itself at
      `Driver.port() + 1`, so it never parses the flag but cannot ask the framework for the socket.
      The shape would be a `Driver.open(shell, gui, controls, offset)`. **Not yet**: one application
      is not a census, and the two things that make the designer's second driver awkward (a named
      window's controls arrive after the driver starts, and are replaced when it reopens) are facts
      about that window rather than about starting an application. Recorded so the second witness is
      recognised as one.

- [ ] **An application that configures a non-default zoom range will disagree with the text editor's
      other two windows.** `FolderWindow` and `EditorWindow` apply `Appearance.ZoomRange.DEFAULT`
      rather than the running application's, because both also run under MainFrame where there is no
      `Shell` to ask. Correct today, since nothing configures a range; wrong the moment something
      does. The fix is a constructor parameter defaulting to `DEFAULT`, and it is not worth the churn
      on two library classes until a host wants one.

- [ ] **`shell.wake(gui::onWork)` is redundant, and the framework should probably stop making the call.**
      `GuiApp.wireAllWakes` already does `gui.onWork(this::postWake)` for **every** tree it presents,
      re-checked each iteration, and says why: *"an application has more trees than it has main
      windows"*, so left to the application it is *"a line to repeat per window, correct on the window
      under test and missing on the one being used."* The framework's single call is the weaker version
      of the same thing — one tree, once — and deleting it changes nothing, which `WakeSeamsTest`'s
      falsification table records. Not obviously a deletion: the line documents that the framework
      knows the wake is owed. But it should say that rather than duplicate it, or a reader takes it for
      the thing that makes the wake happen.

- [ ] **`shell.deadline(() -> krono.kron().sleepTimeout().nanos())` is not covered by anything, and the
      reason is interesting enough to keep.** Deleting it fails no test: the clock's *wake* already
      brings the loop back whenever the timeline has work, so liveness survives without it and what the
      deadline buys is parking **until** the next moment rather than being nudged to it. That is a
      latency and power property, which is exactly the class `GuiApp.idleRefresh` calls *"bounded,
      uniform, and survivable"* — real, and invisible to an assertion about whether a frame arrived. It
      wants a timing assertion (a cue at a known moment fires within a few ms of it, and the loop woke
      once rather than a hundred times) or `atchung-probe`'s FRAME lane, which already instruments the
      wake/budget/frame chain. Parked until `--profile` is a real thing, since that is the same
      machinery.

- [ ] **Three of the six dropped-capability reports are routed and not asserted.** `InputBackend.open`,
      `InputBackend.perWindow` and `Driver.open` are covered — the first two by
      `DroppedCapabilitiesTest`, which gets its absence free because no tactroller platform module is on
      `-shell`'s test classpath, and the third by `DriverTest`'s malformed port, which is the one way a
      socket fails to bind before `shell.app()` is reached. The other three need a fault that cannot be
      arranged from a test: `InputBackend.attach` wants a backend that opens and will not bind,
      `ClipboardBackend.open` wants a machine with no clipboard, and `installMark` wants
      `setApplicationIcon` to throw. Each would need a seam taking the backend rather than opening it,
      which is more API than the assertion is worth today — recorded so the gap is a decision.

- [ ] **`PointerLock`'s state machine is asserted; the line that installs it is not.**
      `PointerLockTest` drives the threshold, the focus rule, the modes and the teardown against
      `PointerLock.Device`, which is the seam the previous entry turns down in general and which was
      worth it here because the thing behind it is a state machine rather than a one-line report. What
      is still taken on faith is `gui.onPointerLock(this::want)` — that the framework subscribes at
      all. Asserting it needs the dispatcher to fire the sink, which needs a press hit-tested against a
      laid-out tree, which is `vexelray-gui-harness` rather than a unit test. `WakeSeamsTest` already
      runs a real `GuiApp`, so the pattern exists; the missing part is a node that declares
      `dragLocksPointer` and a synthesised press over it.

- [ ] **A test for the second close gate.** `Shell.onClose` refuses a second registration because
      `GuiApp` holds one handler and the replaced one is as likely as not the one that knew about the
      unsaved documents. Only the before-`ATTACH` refusal is covered; the duplicate case needs a real
      `GuiApp`. Now unblocked: `WakeSeamsTest` runs one, so the pattern to copy is its `ProbeWiring`
      registering a second gate in `attach` and expecting the throw.

- [ ] **The designer's comment describes the mode the framework does not use** (fix belongs in
      `vexelray-designer`). `Viewport.java` says *"turning is a displacement, so the pointer is held for
      the gesture and warped back each frame"* — warping is `PointerLockMode.RECENTER`, and
      `PointerLock` defaults to `RAW` precisely so that nothing warps and the cursor reappears where it
      vanished. The line was written when nothing carried the intent out at all, so it described an
      intention rather than an observation. Now that it does, the comment is the only place on the
      stack that still says the cursor moves.

- [ ] **Fully-qualified names inline where every other file imports.** `Shell.onClose` takes a
      `java.util.function.Consumer<CloseRequest>`, and `Pacing` and `FrameHooks` write
      `java.util.Objects.requireNonNull` and `java.util.Comparator` in place. Cosmetic. The one
      deliberate case should stay as it is: `InputBackend.perWindow` spells both `NativeWindow` types
      out in full because *"importing either shadows the other."*

## Upstream

Cannot be fixed from this repo. The one that blocks v1 is under **Blocks v1**; these do not.

- [x] **`Modals` never receives the application's theme** (`vexelray-gui-widget`) — **fixed upstream and
      taken here.** It built its own `new Gui()`, which defaults to `Theme.DARK`, so every dialog the
      framework installed drew dark whatever `Appearance.theme()` said — against a class whose own Javadoc
      promises a dialog *"drawn with the same chrome as the rest of the application"*. Invisible in
      `text-editor-vexel-demo`, whose theme *is* `Theme.DARK`; visible in `calculator-vexel-demo`.

      The signature this note predicted is the signature it got: `Modals.install(app, Consumer<Gui>)`,
      applied to the dialogs' tree *before* it is built, because a role resolves at the moment a widget
      writes a colour. `VexelApplication` now calls it with `appearance::applyTo` — the same seam the
      designer's viewport window and the editor's other two use, and the case `Appearance.applyTo`'s own
      Javadoc was written about.

      Two things worth knowing, both decided upstream. The one-argument `install` stayed, documented as the
      library default look for an application with no decision to pass on, so the wrong call is now a choice
      rather than an accident. And the dialogs re-derive their page and message colour per dialog, so an
      application that changes its look at runtime gets a correct *next* dialog — the buttons were already
      being rebuilt, and those two were the only parts that would have stayed stale.

      Guarded by `DialogsWearTheApplicationsLookTest` in `vexelray-gui-harness`, which asserts the seam was
      handed the tree the dialogs are actually built on rather than merely a `Gui`. Writing it needed a fix
      of its own (`vexelray-gui` §6.11): the harness made its first window on the calling thread and the
      rest on the loop thread, and a dialog is modal, which was enough to leave no thread able to tear them
      all down.

- [ ] **`GuiApp.maxFrameRate` does not cap frames a component's wake earns** (`vexelray-gui-core`). Found by
      the long-running witness: a component ticking at about 100 Hz drove **104 frames a second** against a
      60 Hz ceiling. The ceiling is a timed park, a posted wake ends the park early, and a component's wake
      is the same message as OS input. Harmless at 100 Hz and a busy loop at a kilohertz, since only the
      presenter blocking would slow it. The fix is a wake that earns a frame no sooner than the ceiling
      allows and is remembered rather than dropped. It changes frame timing under an unchanged signature, so
      it is a decision for `vexelray-gui`, and the witness prints the number so the fix has something to move.

- [ ] **`settle` says `ok` for an application that never goes quiet** (`vexelray-gui-automation`, and this
      repo's `Driver` if it grows a verb). The long-running witness's metronome ticks to itself, and `settle`
      answered `ok` between two ticks, as its javadoc says it may: it is exact about the frame loop and the
      clock and blind to application work in flight. Nothing in the socket can say *this application does
      not go quiet*, so a script that settles before a photograph of an animating window photographs it
      mid-change. Either the framework offers a way for an application to declare it is busy, which
      `settle` waits on, or the documentation says loudly that a photograph wants a landmark to wait on.

- [ ] **The pointer lock is asked for on the press, not on a drag** (`vexelray-gui-core`).
      `InputDispatcher` calls `requestPointerLock(dragLocks.contains(...))` inside its `ButtonPressed`
      case, beside `fireDrag(START)` — so the intent arrives before the pointer has moved at all, and a
      plain click on a viewport asks for the cursor to be hidden and then released a few frames later.
      Carried out literally, that is a flicker on every click on the designer's canvas.

      Worked around downstream rather than fixed: `PointerLock` holds the intent until the pointer has
      travelled `DEFAULT_THRESHOLD_PX`, so a press that releases without moving never hides anything.
      That is the right place for *a* threshold — travel is a fact about the device, which is the
      framework's side of the seam — but it is not the right place for *this* one. The dispatcher
      already owns a recogniser that draws exactly this distinction, in `DragGesture`'s own words:
      *"distance decides whether it was a drag"*. A lock requested on the promoted drag rather than on
      the press would need no threshold here at all, and would fix it for every consumer of
      `vexelray-gui` rather than for applications that happen to run under this framework.

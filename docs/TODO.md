# TODO

Work on this framework that is known about and not done. Most of it is what the ports turned up —
what [the text editor found](architecture.md#what-porting-the-text-editor-found), and what
[the designer found](architecture.md#what-porting-the-designer-found) — where the gaps each port
closed, and the reasoning behind each, are recorded.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than
ticking it. An entry whose fix belongs in a sibling repo says which one; the ones under **Upstream**
cannot be fixed from here at all.

## Next

- [ ] **The wiring is generated; what it does not do yet.** The processor writes `<App>Wiring` from
      `@VexelApp`, `@Provides`, `@Component`, `@Setting`, `@BeforeFrame`, `@OnMode` and `@ConditionalOnType`,
      the `vexel-desktop` template ships a `Recipes` configuration instead of a hand-written wiring, and
      `-Pacceptance` builds and drives the result. What is left, roughly in the order it wants doing:
      - **Most of the framework's defaults still cannot be replaced.** The look, the input backend and the
        clipboard can — a `@Provides` returning one is handed back to `Shell` (see architecture.md, *the
        framework's own defaults are handed back*). Window memory, the icon, the dialogs and pacing cannot, and
        the README lists them among the defaults. Each wants a reason to be replaced before it gets a setter;
        none has one on the stack yet.
      - **`Driver` wants to be a starter.** The template provides it by hand, with
        `@Provides Driver driver(Shell shell)`; guarded by `@ConditionalOnType`, depending on
        `-automation` would be the whole of the decision.
      - **A provider returning `null` is passed on as `null`.** `@Provides` says absence is a supported answer,
        that dependents which tolerate it still build, and that the framework reports it once. Today the
        dependents are built with the `null` and nothing reports it; the consumers the wiring itself calls
        (`appearance`, `register`, the hooks) are guarded, and that is all.
      - **Types match exactly.** A parameter asking for an interface is resolved only by a provider
        returning that interface, never by one returning a subtype. Deliberate for now — a subtype match is
        the ambiguity `@Default` exists to rule out — but it is a decision nobody has argued.
      - **One round.** The graph is taken in the round the `@VexelApp` is seen, which is every source of a
        clean build; anything another processor generates in a later round is not in it.
      - **Two promises need a method body.** A `@Provides` calling another directly, and T2.5's capture
        rule, are both about what code *does* rather than what it declares. The Trees API can read them;
        doing so is a decision, since *the processor reads declarations* is load-bearing in
        architecture.md's argument about placement.
      - **The copier (T2.4)** and the message-graph checks (§4) wait on the seam that declares a channel.

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

- [ ] **The designer's comment describes the mode the framework does not use** (fix belongs in
      `vexelray-designer`). `Viewport.java` says *"turning is a displacement, so the pointer is held for
      the gesture and warped back each frame"* — warping is `PointerLockMode.RECENTER`, and
      `PointerLock` defaults to `RAW` precisely so that nothing warps and the cursor reappears where it
      vanished. The line was written when nothing carried the intent out at all, so it described an
      intention rather than an observation. Now that it does, the comment is the only place on the
      stack that still says the cursor moves.

## Later

- [ ] **An application that configures a non-default zoom range will disagree with the text editor's
      other two windows.** `FolderWindow` and `EditorWindow` apply `Appearance.ZoomRange.DEFAULT`
      rather than the running application's, because both also run under MainFrame where there is no
      `Shell` to ask. Correct today, since nothing configures a range; wrong the moment something
      does. The fix is a constructor parameter defaulting to `DEFAULT`, and it is not worth the churn
      on two library classes until a host wants one.

- [ ] **`-diagnostics`, and move `FpsProbe` into it.** It is `text-editor-vexel-demo`'s, about 250
      lines, and generic apart from the one thing that makes it worth having: it deliberately pokes
      each wake path — a timeline post, a node mutated off the frame thread, a handler that changes
      nothing — to prove each still produces a frame. That is what `--profile` should turn on, and it
      is the module the architecture doc already lists as the Actuator analogue. Until it exists,
      `profile` is a name `Launch` reserves and nothing in the framework honours — documented on
      `FRAMEWORK_KEYS` and in
      [architecture.md](architecture.md#the-two-reserved-keys), along with why the real fix is
      `@ConditionalOnType` and not a runtime check.

      **The name is now taken upstream.** `../vexelray` ships a `vexelray-diagnostics` for a different
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

- [ ] **A test for the second close gate.** `Shell.onClose` refuses a second registration because
      `GuiApp` holds one handler and the replaced one is as likely as not the one that knew about the
      unsaved documents. Only the before-`ATTACH` refusal is covered; the duplicate case needs a real
      `GuiApp`. Now unblocked: `WakeSeamsTest` runs one, so the pattern to copy is its `ProbeWiring`
      registering a second gate in `attach` and expecting the throw.

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

- [ ] **One seam carries three of the four differentiators, and it is the one not built.** A
      `@Subscribe` that generates its `Pump` registration in `ATTACH` and its teardown in the
      `Disposer` is at once the actor model's substrate (above), the extension and macro API, and the
      reactive half of automation. That third one is easy to miss: `Automation` is command-in,
      response-out — `click`, `type`, `shot`, `await` — so a user-authored macro can *drive* the
      application but cannot *react* to it, and an extension that wants to run when something happens
      has nowhere to attach. All three want the same thing, which is why it is worth building once
      rather than three times.

- [ ] **The container now gives a component a thread and a mailbox; what is left is the colour rule.**
      `Shell.lanes()` owns the application's threads and `Shell.place(name)` puts a component on one of
      them with its mailboxes, its wake and its drain-then-stop — `Lanes` in `-core` (pure JDK, so the
      container stays testable with no GPU) and `Placement` in `-shell` (a mailbox is atchung's, and that
      edge already falls there). The upstream half landed with it: `Gui` takes both lanes rather than
      building a `newCachedThreadPool` whatever it is handed, and closes only the lanes it built itself.

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

- [ ] **Nothing in the model covers work that outlasts a frame, and the stack already named the
      answer.** `kronometer/docs/architecture.md` §10 specifies it: *"`offload(work)` remains available
      for work that is genuinely unbounded — file I/O, network, image decode — moving it to an ordinary
      executor (**its own**, never the kernel's single carrier) and delivering completion as a timeline
      event."* Specified and unbuilt — `offload` appears in no Java source in that repo. The framework
      wants the same lane, and naming it here is what stops the component model being read as the answer
      to a question it does not answer.

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

- [ ] **Fully-qualified names inline where every other file imports.** `Shell.onClose` takes a
      `java.util.function.Consumer<CloseRequest>`, and `Pacing` and `FrameHooks` write
      `java.util.Objects.requireNonNull` and `java.util.Comparator` in place. Cosmetic. The one
      deliberate case should stay as it is: `InputBackend.perWindow` spells both `NativeWindow` types
      out in full because *"importing either shadows the other."*

## Upstream

Cannot be fixed from this repo.

- [ ] **`Gui`'s topics are `static`, so one bus can carry one tree** (`vexelray-gui-core`). Every
      instance subscribes to the same `vexelray.gui.mutations`, so two trees on one bus each receive the
      other's mutations — into a mailbox bounded at 65,536 with `Backpressure.BLOCK`, drained only while
      that tree is being presented. The second `Gui` fills and then blocks the first one's node setters
      for good: a freeze after tens of thousands of edits, with nothing thrown and nothing logged. This
      framework shipped it for three commits by putting `Modals` on `Shell.bus()`, and the designer found
      it by putting a second window there and watching the viewport stop marching.

      Constrained rather than fixed: `Gui(Atchung)`'s javadoc now says which things may share a bus.
      **It is also the ceiling on one inspectable fabric per application**, which is the point of having
      a bus at all — a second window cannot join it. The fix is topics named per instance rather than
      per class, and it is a real change in `vexelray-gui` rather than a line here.

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

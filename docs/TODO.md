# TODO

Work on this framework that is known about and not done. Most of it is what the ports turned up —
what [the text editor found](architecture.md#what-porting-the-text-editor-found), and what
[the designer found](architecture.md#what-porting-the-designer-found) — where the gaps each port
closed, and the reasoning behind each, are recorded.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than
ticking it. An entry whose fix belongs in a sibling repo says which one; the ones under **Upstream**
cannot be fixed from here at all.

## Next

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

- [ ] **The container has no way to give a component a thread and a mailbox, which is the design.**
      The model is now written down — [the concurrency model](architecture.md#the-concurrency-model),
      including the three mismatches it leaves for the processor, the `WakeSource` a component mailbox
      owes, and why `Disposer` needs *drain then stop*. What is missing is entirely on this side, and
      [what is actually wired today](architecture.md#what-is-actually-wired-today) is the inventory:
      no `Executor`, no `Thread`, no `sibarum.atchung` and no `sibarum.kronometer` anywhere in the four
      modules' main sources.

      **The first of the two constructor arguments is taken.** `Shell` owns an `Atchung` and hands it
      out as `Shell.bus()`; the framework's `Gui` is built on it and so are the dialogs, through a new
      `Modals.install(app, bus, appearance)` upstream. So there is one fabric per application rather
      than one per `Gui`, and the calculator no longer runs two.

      **The second is the handler executor, and it is not one line.** `Gui(Atchung, Executor)` exists,
      but `Gui`'s worker pool is a field initializer and `Gui.work()` submits to *that* pool, so a
      `Gui` builds a `newCachedThreadPool` whatever it is handed. Passing an executor redirects input
      handlers and leaves the pool. Making one application mean one set of threads is therefore an
      upstream change in `vexelray-gui` — worth doing before components are placed, because the point
      of owning the executor is that placement is decided in the wiring rather than by how many trees
      an application happens to hold.

      **The fault policy is decided and owned**, in `Faults` and in
      [architecture.md](architecture.md#what-a-full-mailbox-does-and-where-survivability-actually-lives):
      a bus fault still halts, for upstream's reason rather than ours, and a wedged component is answered
      by the `Backpressure` of its channel rather than by a process policy. What that leaves is the part
      neither half covers — **nothing notices that a component has stopped draining, or names it.** That
      is supervision, and it wants `Overrun` per grouping before it is built on anything but a timeout.

      **The first component exists, in `vexelray-designer`**, and what writing it by hand found is in
      [architecture.md](architecture.md#the-first-component-written-by-hand). The one that needs an
      answer from this side: **`Pump` cannot be a component's inbound mailbox**, because `drain()` is
      non-blocking and nothing signals arrival — a `Pump` is drained by an owner that already has a
      wake. The designer's mailbox is therefore a field and a park, and the bus carries results
      outward. The second component decides whether atchung grows an await or the framework owns the
      parking; **do not extract the designer's `Mailbox` before there is a second one**, on the same
      grounds the second automation socket is waiting for a second witness.

      **Also add the `FrameHooks` note while it is cheap.** The doc records that a flat `Runnable[]`
      walked on one thread is the barrier's N=1 case; the file itself does not say so, and its
      no-allocation rigour will get defended into a shape that cannot grow if nobody writes it there.

- [ ] **Fully-qualified names inline where every other file imports.** `Shell.onClose` takes a
      `java.util.function.Consumer<CloseRequest>`, and `Pacing` and `FrameHooks` write
      `java.util.Objects.requireNonNull` and `java.util.Comparator` in place. Cosmetic. The one
      deliberate case should stay as it is: `InputBackend.perWindow` spells both `NativeWindow` types
      out in full because *"importing either shadows the other."*

## Upstream

Cannot be fixed from this repo.

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

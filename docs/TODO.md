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

- [ ] **Six dropped-capability reports should go through `dev.vexelray.diag.Diagnostics`** (new in
      `../vexelray`). `InputBackend.open`, `InputBackend.attach`, `ClipboardBackend.open` and
      `Driver.open` each print to `System.out`; `installMark` prints to `System.err`; and
      `InputBackend.perWindow` returns `WindowInput.NONE` saying nothing at all — a second window that
      takes no input and reports it nowhere, which is this repo's own instance of the fault that module
      was built for: *"nothing threw, nothing warned, and each produced a plausible picture."*
      `Diagnostics.dropped(key, what, why)` is warn-once per call site, on by default, and `recorded()`
      is what makes one assertable — which none of the six is today. The module depends on nothing at
      all, so it reaches `-shell` without dragging anything with it.

- [ ] **The javadoc describes the processor in the present tense, and the processor does not exist.**
      Every annotation in `-api` is inert, all ten of them — `@VexelApp`, `@Component`, `@Provides`,
      `@Configuration`, `@Default`, `@ConditionalOnType`, `@OnMode`, `@Setting`, `@MainThread` and
      `@BeforeFrame`; the only live types in that module are the two enums. Nothing in the repo
      implements `AbstractProcessor`, and the sole mention of `RoundEnvironment` is inside a javadoc.
      Those same files read *"the processor rejects a component with more than one non-private
      constructor"* and *"two non-default providers for one type are a compile error"* as statements of
      fact, so a reader cannot tell which sentences describe code and which describe intent. The same
      gap shows in `Shell`, whose phase guards throw `IllegalStateException` where this repo's rule is
      that *a compile error beats a startup error beats a runtime error* — and which says so itself,
      calling them *"the honest description of a backstop."* The fix is a future tense, or one line at
      the top of `package-info` saying which half is built. It is the single thing that most makes this
      framework read as rougher than it is.

- [ ] **`Wiring` is an accidental functional interface**, so the framework's central contract can be
      satisfied by a lambda. `info()` is its only abstract method and all six phase methods are
      `default`, which makes `VexelApplication.run(() -> myAppInfo, args)` compile and yield an
      application whose every build phase silently does nothing. A second abstract method, or an
      abstract class, closes it. The phase defaults are worth keeping either way, since *"most
      applications have nothing in most phases."*

- [ ] **`@VexelApp`'s javadoc example does not compile.** It shows
      `VexelApplication.run(TextEditorAppWiring::new, args)` — a constructor reference — against a
      signature that takes a `Wiring` instance, and all three ported applications correctly write
      `run(new XWiring(), args)`. Wrong in the one place a reader looks first.

- [ ] **`calculator-vexel-demo`'s `Capture` still builds its tree by a second route** (in
      `../calculator-vexel-demo`). `Capture.build()` does `new Gui()`, `gui.theme(Look.THEME)`,
      `gui.minSize(46em, 30em)` and a `TitleBar` against `WindowControls.NONE` by hand — a second copy
      of what `CalculatorWiring.config` says, and exactly the hazard `VexelApplication.toTree`'s
      Javadoc records from the text editor: *"a capture that built its tree by a second route would be
      a capture of a different application."* The editor's capture already goes through
      `VexelApplication.tree`. This one has a zoom ladder and a shot per rail panel, so it needs the
      `Shell` that comes back rather than a straight swap — which is why `tree` returns one.

- [ ] **Regenerate `mainframe-template`'s `vexel-desktop` scaffold to emit a `Wiring`** (in
      `../mainframe`). Its `App.java` is ~350 lines and is this framework's specification written
      longhand — so every new application on this stack still starts from the file the framework
      exists to replace. Its `docs/TODO.md` even ships an entry telling the author to *"decide what
      closing the window means... an application with unsaved state wants `GuiApp.onCloseRequest`"*,
      which is now `Shell.onClose`.

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
      owes, and why `Disposer` needs *drain then stop*. What is missing is entirely on this side: no
      `Executor`, no `Thread` and no async seam anywhere in the four modules' main sources, so today
      the only expression of the model is `InputBackend`'s two bridge lines. The substrate upstream is
      finished, so this is the framework's half and nobody else's.

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

- [ ] **`Modals` never receives the application's theme** (`vexelray-gui-widget`). It builds its own
      `new Gui()`, which defaults to `Theme.DARK`, so every dialog the framework installs draws dark
      whatever `Appearance.theme()` says — against a class whose own Javadoc promises a dialog *"drawn
      with the same chrome as the rest of the application"*. Invisible in `text-editor-vexel-demo`,
      whose theme *is* `Theme.DARK`; visible in `calculator-vexel-demo`. Predates the framework
      installing them — the editor had the same dialogs and the same problem — but the framework has
      now made it every application's. `Modals.install` needs to take a `Theme`.

      The signature is now obvious, which it was not when this was written: `Modals.install(app,
      Consumer<Gui>)`, called with `shell.appearance()::applyTo`. That is the same seam the designer's
      viewport window and the editor's other two use, and a dialog's `Gui` is exactly the case it
      describes — a window the framework installs and the application's look never reaches.

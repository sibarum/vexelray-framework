# TODO

Work on this framework that is known about and not done. Most of it is what the ports turned up —
what [the text editor found](architecture.md#what-porting-the-text-editor-found), and what
[the designer found](architecture.md#what-porting-the-designer-found) — where the gaps each port
closed, and the reasoning behind each, are recorded.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than
ticking it. An entry whose fix belongs in a sibling repo says which one; the ones under **Upstream**
cannot be fixed from here at all.

## Next

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
      `GuiApp`, so it wants the harness rather than `ShellTest`.

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

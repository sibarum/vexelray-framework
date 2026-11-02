# The template as the manual

*Proposed 2026-11-02, written by the coding agent as the template's customer, at the author's ask. Nothing here is
ruled yet.*

**The goal (the author's, 2026-11-02):** building a well-polished application without compromise should be *easy*,
for the author and for a coding agent, and should work on the first try. **The proposal:** a generated project carries
a worked example of everything an application reaches for, so that after generating one, nothing outside the project
has to be read to know *how* to do something. The framework's docs keep the *why*.

**Why the template and not more guides:** the template's files are compiled and tested by `VexelDesktopTest` and the
acceptance run against the framework as it is. A guide can go stale without anyone noticing; an example cannot. And an
example that cannot be written cleanly is a gap in the framework, found without rebuilding an application, which is
the same job the early app rebuilds are doing now (Rasterware, the Inspector, MainFrame, Vexplore), only cheaper.

## What I need from an example

Written as the customer: what makes the difference between copying an example correctly and copying it wrong.

1. **It is the polished way, never a demo shortcut.** Whatever is in the template gets copied, mostly by an agent. A
   corner cut in an example is cut in every application made from it, so the examples meet the same bar as the
   editor: motion, landmarks, `Cuts`-clean, no workaround without its FN.
2. **One question per example**, named as the question ("keep reading from outside for as long as it runs"), not as
   the feature (`@Source`). I arrive with a question, not with the annotation's name.
3. **A short header on the example's main file:** what it shows, the message flow in one line (who sends what to
   whom, on which lane), and the one trap. Three to six lines, pointing to the framework doc for the reasoning.
4. **Its test, and its `ottermate` script.** How to check a thing is half of what I copy. Today the template ships
   tests but no `ottermate` script, so every agent writes its first one from the guide. Each example gets
   `checks/<example>.txt` that drives it and takes a screenshot.
5. **An index.** `EXAMPLES.md` at the project's top: question → files → test → check script. It is the one file I read
   to find where something is shown. Generated projects also say in `CLAUDE.md`: *look here before inventing a way*.
6. **Easy to remove.** The real cost of a large template is taking out what an app does not want; a half-deleted
   message-wired feature leaves keymap entries, mailboxes and message types behind. So each example lives in its own
   package in each module, touches no shared file except one line in each of a few registries (`Core`, `Recipes`,
   `Messages`' imports if any, the keymap), and removing it is deleting its folders and those lines. The processor
   reporting what dangles is what keeps that safe.

## The questions

Grouped by what I am building at the time. **Status** says where it stands today: **shown** (the editor already shows
it, so the example is the editor and the index points there), **to show** (the framework has it, the template does
not show it), or **open** (I could not tell whether the framework has a good answer; writing the example is how to
find out, and the author may want to rule first). **★** marks the ones I would reach for soonest or get wrong most
easily, which is the order I would build them in.

### A. Parts: deciding, waiting and reaching outside

| # | Question | Status | Notes |
| --- | --- | --- | --- |
| A1 | Add a decision | shown | a step on `Editor`'s ledger |
| A2 | Run something slow and get the answer back | shown | `ReadFile`, `WriteFile`: a `@Job`, failure as a field of the reply |
| A3 | Wait on the person or the disk in the middle of a decision | shown | closing, as a chain |
| A4 ★ | Keep reading from outside for as long as it runs | to show | `@Source`. Example: follow a file as it grows (a log), stopping when it is closed. Nothing native, so it builds everywhere |
| A5 ★ | Write outside, and read and write one connection | to show | `@Destination`, and a part that is both. Example: a loopback socket the example opens to itself, a line typed in, the echo shown |
| A6 ★ | Heavy compute that must never stall input or drawing | open | Rasterware's case. Example: a tiled render with progress. **Open:** how a superseded run is cancelled (the person changes the input mid-run), and whether progress is a reply per tile or a source |
| A7 ★ | Do something later, or every so often | open | A step cannot ask for a message later; ANY and NONE with a time branch, ruled 2026-11-02 in [chains.md](chains.md), not yet built |
| A8 ★ | Wait until the typing stops (search as you type) | open | Found writing slice 1: the same gap. NONE within a time, ruled 2026-11-02 in [chains.md](chains.md), not yet built |
| A9 | Put a part on its own lane | to show | `@Component(lane = ...)`, and when that is the right call instead of a job |
| A10 | Pick a part by run mode or by what is on the class path | to show | `@OnMode`, `@ConditionalOnType`, replacing a `@Default` |

### B. Settings and state that outlive a run

| # | Question | Status | Notes |
| --- | --- | --- | --- |
| B1 ★ | A preference the person sets, kept across runs | to show | `@Setting`, read by a part and changed from the view |
| B2 | The session comes back | shown | `ReadSession`, `WriteSession` |
| B3 ★ | Undo and redo | open | The demo has `UndoChapter` and `ValueUndo`, but in the message model the history belongs in `-core`'s state. **Open:** whether the framework should hold the shape (a ledger with history) or each application writes it |

### C. The view

| # | Question | Status | Notes |
| --- | --- | --- | --- |
| C1 | Show what the controller decides; tell it what the person did | shown | `View`, `Intent` |
| C2 | A text field that stays responsive at every keystroke | shown | the editor's buffer |
| C3 | A tree | shown | `Navigator` |
| C4 ★ | A long list or table, with selection and sorting | to show | `Table`, `ListView`; ten thousand rows, so the example shows it stays smooth |
| C5 ★ | Draw something no widget draws | to show | `@Draw`, from [custom-view.md](../developers/custom-view.md)'s bars, with hit boxes and a landmark |
| C6 ★ | Show pixels or data produced off the GUI thread as it arrives | to show | a feed crossing as a handle. Pairs with A6: the tiled render lands through it |
| C7 | Zoom and scroll a canvas | to show | `ZoomKeys`, the scroll module, and the motion guide's zoom section |
| C8 ★ | Ask the person: confirm, choose, pick a file | to show | `Dialogs`, `Ask`, the native file chooser; one example per kind is too many, one with all three is right |
| C9 | Menus, a context menu, a rebindable command with its chord | partly shown | the keymap is shown (`cea345c`); a context menu is not |
| C10 | Drag and drop inside the application | to show | from the demo's `DragChapter` |
| C11 | A tooltip, a status message | to show | small, but every app needs them |
| C12 | Swap one view for another, things arriving and leaving, a value that eases | partly shown | `Motion.java`; the swap from the motion guide (`2e0d530`) is not |
| C13 | The look: tokens, light and dark | shown | `Look`, `Type` |
| C14 | An icon of the application's own | shown in prose | `CLAUDE.md` says how; one real baked icon would make it copyable |

### D. Windows and the desktop

| # | Question | Status | Notes |
| --- | --- | --- | --- |
| D1 ★ | A second window, opened, remembered and closed | to show | a named window. The examples' own window (below) is this example |
| D2 | Full screen, and the other window commands | to show | `WindowCommand` (`741e7ca`) |
| D3 ★ | Copy and paste | to show | `tactroller-clipboard` |
| D4 | Refuse to close while there is unsaved work | shown | the close gate |
| D5 | Files dropped onto the window from outside | open | I did not find whether the stack takes OS drops |

### E. Checking it

| # | Question | Status | Notes |
| --- | --- | --- | --- |
| E1 | Test a step, a job, the view headless | shown | `EditorTest`, `FilesTest`, `ViewTest` |
| E2 ★ | Drive it and screenshot it | to show | one `checks/*.txt` per example (above), plus one for the editor |
| E3 | Replay a run and get the same writes | open | ROADMAP §5's item 5. If it is built, an example test is the clearest statement of it |
| E4 | See its frames and messages in the Inspector | to show | the diagnostics socket (`3f964d3`); a line in the README and a check that attaches |

## Where they live

**The editor stays the spine.** It already reaches most seams, and an application made from the template is usually
an editor of something. Everything marked *shown* stays where it is; the index points into it.

**Everything else goes in an Examples window**, opened from the Help menu and a chord: a list of the questions down
the left, the chosen example live on the right, its header text above it. That window is D1's example, and it keeps
the editor's own window exactly as it is now, so turning examples off changes nothing the person sees in the editor.

Files: `${artifactId}-core/.../examples/<name>/` and `${artifactId}-gui/.../examples/<name>/`, the test beside each,
`checks/<name>.txt`, and a row in `EXAMPLES.md`. Messages an example sends are records in its own package, not in the
editor's `Messages.java`, so deleting the folder takes them too.

**One question when generating:** `examples`, a flag, default yes ("worked examples of what an application reaches
for, in an Examples window; delete any you do not want"). Each `file` line of an example says `when examples`. A
question per example can come later, if the same few keep being deleted. `VexelDesktopTest` and the acceptance run
generate and build both ways, so the flag cannot quietly break.

## What would stop this

- **The examples becoming the second application nobody maintains.** They are compiled and tested with the template
  on every framework change, so the cost lands where the framework changes, which is the point. An example whose
  upkeep keeps costing more than it teaches is cut, not left to rot.
- **An example hiding a gap.** The rule is the generated `CLAUDE.md`'s: if an example needs a workaround, it waits
  and the gap goes in [TODO.md](../TODO.md) under *Found by the template*, rather than a workaround shipping in every
  new project.
- **Size.** The template is about 4,700 lines today. Twenty examples at 150 to 300 lines each roughly doubles it. That
  is what `examples = no` is for.

## Order

1. **The frame:** the `examples` flag, the Examples window (D1), `EXAMPLES.md`, the check-script convention (E2), and
   the first example to prove them end to end: A7/A8 (the timer and the debounce), the smallest that crosses a lane.
   *Starting it on 2026-11-02 found that a step cannot ask for a message later, so A7/A8 wait on the waits proposed
   in [chains.md](chains.md) (ALL, ANY, NONE).*
2. **The ones Rasterware and MainFrame found:** A4, A5, A6 with C6.
3. **The everyday view:** C4, C5, C8, B1, D3.
4. **The rest**, in the table's order, with **open** rows put to the author before their example is written.

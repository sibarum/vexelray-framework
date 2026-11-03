# The template in modules

*Proposed 2026-11-02, written by the coding agent at the author's ask. Its five questions were answered the same day,
each **accepted as recommended**: see [The author's rulings](#the-authors-rulings). The rest of the plan is still a
proposal.*

**The ask (the author's, 2026-11-02):** make the template modular, so that its parts can be included or left out
when a project is generated, or all of them taken at once. **The concern, also the author's:** what it costs to keep
up. This plan is mostly about that cost: a modular template that is cheap to keep has a few hard rules, and the rules
are what this proposes.

**Why now:** every rebuild so far (Calculator, MainFrame, Vexplore, the Inspector) began by deleting the editor
the template ships with, and Vexplore's M0 made the deletion a milestone of its own. Deleting a message-wired feature
by hand leaves topics, steps, landmarks and chords behind, and that is work every new application repeats. And the
[worked examples](template-examples.md) are about to grow from one to many, which is the same question asked from the
other side: what may a part of the template touch, so that it can be taken out?

## What exists already

- **The manifest already includes files by flag.** `template.manifest` lists every file the template writes, and a
  line may carry conditions (`when tests`, `when examples`, both at once), all of which must hold. A module is more
  of those lines; the generator needs no new engine to leave one out.
- **The examples already found the seam.** [Slice 1, as built](template-examples.md#slice-1-as-built), finding 1: an
  example touches no shared file at all. The processor reads every `@Configuration` in `-gui`'s sources, such a
  configuration may list a `-core` component, and a view declares its own chords (`keys.action("help.examples", ...)`
  in `ExamplesView`). So `Recipes`, `Core`, the app declaration and `Messages` never name an example, and the
  `examples` flag needed no conditional line in any file.
- **What the examples sidestepped.** An example lives in a window of its own. Nothing yet adds a piece to *the main
  window*, which is what most modules would do.

## The shape

**A base and modules.** The base is what every project gets and cannot opt out of: the parent and module poms, the
app declaration, `Look`, `Type`, `Motion`, `Landmarks`' own entries, the main window with nothing in it, the docs a
project keeps (`CLAUDE.md`, `framework-notes.md`, `retrospective.md`, `TODO.md`), the native build and the icon. A
**module** is a feature you keep: a folder in each code module, its tests, its `checks/` script, its doc fragment, and
a block in the manifest naming it.

**An example is a module whose purpose is to teach.** Same mechanism, same rules; it is meant to be deleted once read,
a module is meant to stay. The `examples` flag becomes the umbrella for the teaching modules.

**What is asked when generating:** one question, `modules`, listing every module with a one-line summary each, preset
to all of them. *All* is a word the answer may be, and is the default. A module another needs is taken with it, and
the generator says so.

A manifest block, as it might read:

```
module navigator
    summary   a folder's tree beside the main view; Enter or a double click opens a file
    needs     files

file ${artifactId}-core/src/main/java/${packagePath}/core/navigator/ListFolder.java  from navigator/ListFolder.java
...
```

A `file` line inside a block is written only when its module is chosen, as `when` does today.

## The rules that keep it cheap

Each rule is checked by the build, not by review, because a rule held by review is the first thing to go.

1. **No conditional inside a file.** A module is a set of whole files, written or not. Once a shared file carries
   *if module X* sections, every combination is a different program, and the combinations are what make a template
   rot. **Checked by:** the engine has no such syntax, and gets none.
2. **The base never names a module, and a module names only the base and the modules it `needs`.** **Checked by:** a
   test in `VexelDesktopTest` that reads each module's files for the other modules' packages, and the base's for any
   module's. A module that needs another declares it, and the generator refuses an answer that leaves one out
   before anything is written (a generate-time error, before a compile error).
3. **Shared things are gathered, never edited.** Parts, windows and chords are gathered already, by the processor
   (every `@Configuration`) and by each view declaring its own chords. Messages go in the module's own `Messages`
   file in its own package, as the examples' do (`LaterMessages`). Docs are fragments the generator gathers: see
   ruling 4. Whatever cannot be gathered is a gap in the framework and goes in [TODO.md](../TODO.md), not in the
   template as a workaround.
4. **The tests grow with the modules, not with their combinations.** With rules 1 to 3 holding, a module can only
   break the base or itself, so three kinds of generated build cover the matrix: the **base alone**, **each module
   alone** (with what it needs), and **all of them**. That is *n* + 2 builds, not 2ⁿ. `VexelDesktopTest` checks
   every one's file list without building; the acceptance run (`-Pacceptance`) generates and builds all of them,
   and drives *all* live, as it drives the template today.
5. **All is the reference.** The template's manifest says it is "what the framework is verified against". That stays
   true of *all modules on*: one tree, one verified shape, and the one a framework change is checked on first.

## What it costs to keep

- **Per framework change:** what it costs today, since the same files are compiled and tested. The one new cost is
  the *n* + 2 builds in the acceptance run. Each is a full Maven build of a generated project, so the run's time
  grows by roughly one generated build per module; slice 1 measures it before more modules come in. If it grows too
  slow, *each module alone* can move to a nightly run while *base* and *all* stay on every change.
- **Per new module:** its block, its files, its doc fragment, its test and check script, and nothing else, since rule
  2 forbids touching anything shared.
- **What it saves:** every new application deletes nothing by hand, the template's own size stops being an argument
  against adding to it, and an example's upkeep and a module's are the same upkeep.

## What the editor needs first

The examples could be modules from the start because they were written that way. The editor was not: today the base
*is* the editor. `Editor.java` (803 lines) holds tabs, the navigator's folder, the session and the close gate as one
`State` on one ledger; `Messages.java` (299 lines) carries all of them; `View.java` (415 lines) lays out the navigator
beside the tabs; and `Recalled`/`Remember` carry the navigator's folder and the editor's files in one record. The
template is about 5,500 lines of Java, and most of it is that one feature.

The split (ruling 1):

| Module | What it takes from today's files | Needs |
| --- | --- | --- |
| **files** | `ReadFile`, `WriteFile`, `FindPath`, `CreateFile`, `TextFile`: the disk jobs and the text policy | - |
| **editor** | tabs, `Buffer`, `Workspace`, the editing steps of `Editor`, the unsaved-work close | files |
| **navigator** | `ListFolder`, `Navigator`, the folder steps | files |
| **markdown** | `Markdown`, `MarkText`: colour for Markdown in the editor | editor |

**The session stays in the base** (ruling 1): `ReadSession` and `WriteSession` become the base's, keeping one key a
module, and each module reads and writes its own piece (the editor its open files and the front one, the navigator its
folder).

**Four gaps this split meets, found by reading, not yet by building.** Each is a framework question first; each goes
to [TODO.md](../TODO.md) under *Found by the template* if the author agrees it is one.

1. **A module adding a piece to the main window.** The examples avoided it with a window of their own; the navigator
   and the editor cannot. The window needs named regions a module fills (a side, a centre, a status line), declared
   by the base and filled by a module's configuration, so that the base does not name what fills them.
2. **Several modules having a say in closing.** The close gate is one chain in `Editor` today. With modules, the
   editor's unsaved tabs are one voice among others, and closing waits on all of them: an ALL wait over branches,
   which [chains.md](chains.md) has, but nothing yet gathers the branches from parts that do not know each other.
3. **Each module's piece of the session.** One record holding the navigator's folder and the editor's files has to
   become one key a module, each read and written by its own module, so that leaving one out leaves its key unread
   rather than a field to delete. Ruling 1 puts the keeping in the base; what is open is how a module hands the base
   its piece without the base naming it.
4. **A recipe in a package of its own returns an interface** ([slice 1, finding 2](template-examples.md#slice-1-as-built)).
   It costs an example two files where one would do; across every window piece of every module it is a tax. Whether
   a public class an application owns may be returned is already the author's call there.

## The author's rulings

Answered 2026-11-02, each **accepted as recommended**: the author took the recommendation without discussion, so each
is sound as far as its reason goes and is the first to re-check when something depends on it ([TODO.md](../TODO.md)
says what the label means).

1. **The modules are files, editor, navigator and markdown; the session is the base's.** The base keeps the session,
   one key a module, and each module reads and writes its own piece. **Why:** every application has something that
   outlives a run, so keeping it is not a feature to opt out of, while *what* is kept belongs to the feature that
   owns it. A session module would have had to name every other module's state, breaking rule 2.
2. **The base alone is an empty window**: the title bar, the app menu with About and the key bindings, and an empty
   main region. **Why:** *all* is the reference (rule 5) and is what reaches every seam; a small feature kept in the
   base only to reach them would be one more thing to keep up, and the first thing every rebuild deletes.
3. **The default answer is all.** **Why:** it is the examples flag's default and the reference build, so the project a
   person gets without choosing is the verified one; taking a module out is deleting its folders, which the rules
   make safe.
4. **Docs are gathered.** Each module brings a fragment, and the generator writes `README.md`, `CLAUDE.md` and
   `EXAMPLES.md` from the base's text and the fragments of the modules chosen. **Why:** a doc that lists a module the
   project does not have misleads an agent, which reads the docs before the code; gathering is one engine feature,
   tested once, where a doc per module leaves nowhere to start reading.
5. **Main-window regions are settled in the framework first**, argued in its docs and ruled before the navigator
   moves, as `@Source` and the chains were; the navigator is then the regions' first witness. **Why:** a shape drawn
   from one module fits that module, and the editor, the examples and every application's own panels would inherit
   it.

## What would stop this

- **The editor not splitting cleanly.** If carving it out needs a shared object or a callback between modules, that
  is the gap, written up rather than worked around, and the editor stays one module until the framework has an
  answer.
- **The matrix growing slow.** Measured in slice 1; the nightly fallback is above.
- **A rule bending once.** One conditional section in a shared file, or one module reaching into another, and the
  matrix stops covering the combinations. The checks in rules 1 and 2 are what stop that, so they land before the
  second module does.

## Order

1. **The mechanism, with nothing moved.** `module` blocks and `needs` in the manifest, the `modules` question with
   *all* as its default, gathered docs (ruling 4), the reference check (rule 2), the *n* + 2 matrix in
   `VexelDesktopTest` and the acceptance run, and the examples turned into the first modules (they already obey the
   rules). The base is still the editor. Measured: the acceptance run's time per module.
2. **Regions, in the framework** (ruling 5): argued in its docs, with gaps 2 and 3 (closing, a module's piece of the
   session) written up beside it, and put to the author.
3. **The navigator out**, the smallest piece with one edge into the editor (opening a file), and the regions' first
   witness.
4. **Markdown out**, then **the session's keeping into the base** (ruling 1), then **the editor**, leaving the base an
   empty window (ruling 2).
5. Worked examples keep coming in as modules from slice 1 on: [template-examples.md](template-examples.md)'s slice 2
   (A4, A5, A6 with C6) is the second and third module to obey the rules, which is the evidence the rules hold.

## Slice 1, as built

Built 2026-11-02: modules in the manifest and the engine, the `modules` question, gathered docs, the reference check,
the matrix in `VexelDesktopTest` and the acceptance run (`ModuleMatrixTest`), and the examples turned into the first
two modules. The base is still the editor. What building it changed in the plan above:

1. **A file says its module on its own line** (`file ... in later`), rather than sitting inside a `module` block. The
   manifest's rule is that a line's first word is what it is and indentation is for reading, so a block would have
   been the one place position mattered. A module's own keys are `about` and `requires`, not `summary` and `needs`,
   because the top level has both.
2. **A module comes with what it requires, and the generator says so** (`note: later requires examples, so it comes
   too`), as *The shape* says. Rule 2's "refuses an answer that leaves one out" was the other reading, and was not
   built: there is nothing to refuse once the requirement is taken. What is refused, before anything is written, is a
   name that is not a module (with a "did you mean"), and `all` or `none` mixed with names.
3. **The `examples` flag is gone.** The Examples window, its index and its chord are the `examples` module, and each
   example is a module that requires it; `none` is the base alone. That is the "umbrella" of *The shape*, made of
   `requires` rather than a second question.
4. **Parts** are `part <file> <marker> from <source> in <module>`, filling a `<!-- part: marker -->` line. A marker no
   part line names, and a part whose marker is not in its file, are refused whatever was chosen, so a misspelling
   cannot hide behind a module that happened to be left out; a part may add to the base's files, its own module's, and
   those of modules it requires, checked when the manifest is read. Two exist: later's row in `EXAMPLES.md`, and the
   examples' "look in `EXAMPLES.md` first" in `CLAUDE.md`, which had said "if this project kept its worked examples".
5. **The reference check found two reaches on its first runs.** `ExamplesTest`, the frame's test, also tested the
   later example, so the frame could not be built without it: later's half is `LaterWindowTest` now, in later. Then the
   rewritten `ExamplesTest`'s javadoc named `LaterWindowTest`. The check reads Java sources only, by the simple names
   of the classes a module's files declare and the packages only its files are in; docs, scripts and poms are not
   read, and a part is how a module speaks in a shared doc.
6. **The matrix is `Template.matrix()`**, shared by the unit test and the acceptance run so the two cannot drift. With
   later requiring examples, later alone is all, so the matrix is three sets: `none`, `examples`, `all`.
   `ModuleMatrixTest` builds the first two; `all` is the default, which `GeneratedProjectTest` builds and drives.
7. **Measured: a module set costs about 9 s.** On this machine, warm, with the stack installed: `none` (50 files,
   19 tests) built in 8 s and `examples` (57 files, 21 tests) in 9 s, against 9 s for the default project's build in
   `GeneratedProjectTest`; the two matrix builds added 18 s to an acceptance run of these two tests that took 49 s.
   *What would stop this*'s worry about the matrix growing slow is a long way off: ten modules would add about a
   minute and a half, and the nightly fallback stays unbuilt until a run says otherwise.

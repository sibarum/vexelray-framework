# The template in modules

*Proposed 2026-11-02, written by the coding agent at the author's ask. Nothing here is ruled yet. The open decisions
are in [The author's questions](#the-authors-questions).*

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
   question 4. Whatever cannot be gathered is a gap in the framework and goes in [TODO.md](../TODO.md), not in the
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

The split, as candidates for the author to rule on (question 1):

| Module | What it takes from today's files | Needs |
| --- | --- | --- |
| **files** | `ReadFile`, `WriteFile`, `FindPath`, `CreateFile`, `TextFile`: the disk jobs and the text policy | - |
| **editor** | tabs, `Buffer`, `Workspace`, the editing steps of `Editor`, the unsaved-work close | files |
| **navigator** | `ListFolder`, `Navigator`, the folder steps | files |
| **markdown** | `Markdown`, `MarkText`: colour for Markdown in the editor | editor |
| **session** | `ReadSession`, `WriteSession`: what comes back on the next run | - |

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
   rather than a field to delete.
4. **A recipe in a package of its own returns an interface** ([slice 1, finding 2](template-examples.md#slice-1-as-built)).
   It costs an example two files where one would do; across every window piece of every module it is a tax. Whether
   a public class an application owns may be returned is already the author's call there.

## The author's questions

1. **The module list.** The five above, or cut differently? In particular: is *session* its own module or part of
   the base, as settings that outlive a run are something every application has?
2. **What the base shows on its own.** The template's manifest says the editor was chosen because it is "the smallest
   real application that reaches every seam". The base alone reaches few of them (no job, no close gate, little
   motion). Is the bare base allowed to be an empty window, with *all* as the one that proves the seams, or does the
   base keep one small thing of its own?
3. **Is the editor still the default answer?** *All* is proposed as the default, as the examples flag defaults to
   yes. The alternative is a default of the base and nothing else, for the rebuilds that delete the editor anyway.
4. **Docs.** `README.md`, `CLAUDE.md` and `EXAMPLES.md` describe what is there. Proposed: each module brings a
   fragment, and the generator writes one gathered file from the fragments of the modules chosen (one small engine
   feature, tested once), so no doc lists a module that was left out. The alternative is a doc per module under
   `docs/modules/`, with no gathering and no index.
5. **Main-window regions** (gap 1). Settle the shape in the framework first, as `@Source` and the chains were, or let
   the first module that needs one (the navigator) propose it?

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
   *all*, the reference check (rule 2), the *n* + 2 matrix in `VexelDesktopTest` and the acceptance run, and the
   examples turned into the first modules (they already obey the rules). The base is still the editor. Measured: the
   acceptance run's time per module.
2. **The questions above, put to the author**, with gaps 1 to 3 written up for the framework.
3. **The navigator out**, the smallest piece with one edge into the editor (opening a file), and the first to fill a
   region of the main window.
4. **Markdown out**, then **the session**, then **the editor**, leaving the base.
5. Worked examples keep coming in as modules from slice 1 on: [template-examples.md](template-examples.md)'s slice 2
   (A4, A5, A6 with C6) is the second and third module to obey the rules, which is the evidence the rules hold.

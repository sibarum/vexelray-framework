# Regions: a module's piece of a window

*Proposed 2026-11-03, written by the coding agent: step 2 of [template-modules.md](template-modules.md), whose ruling 5
asks for main-window regions to be settled in the framework before the navigator moves out of the template's editor.
Its four questions were answered the same day, each **accepted as recommended**: see
[The author's rulings](#the-authors-rulings). Nothing of it is built yet.*

The modular template needs three things the framework does not give it today, found by reading the editor for its
split ([template-modules.md](template-modules.md), *What the editor needs first*):

1. **A module adding a piece to a window** whose layout another part owns, without that part naming it.
2. **Several modules having a say in closing**, each about its own unsaved work.
3. **Each module's piece of the session.**

This proposes an answer to each. The first is most of it.

## 1. Regions

### What exists: a region made by hand

The Examples window already is one. `ExamplesWindow` has an `add(Example)`, and each example's recipe takes the window
and adds itself while the tree is built (`LaterParts.later`). The window names no example, and deleting an example's
folders takes it out. So the shape works, and nothing in the framework had to change for it. What it lacks:

- **The adding is a side effect inside a recipe.** `later(ExamplesWindow)` returns the panel and, on the way, adds it
  to the window. A recipe is meant to say what it provides; this one also changes another part.
- **The order is the processor's, and hidden.** Examples arrive in whatever order the generated wiring calls their
  recipes, and `ExamplesPage` sorts them by question so nobody sees that order.
- **Nothing is checked.** A place that takes exactly one piece (a window's centre) and gets two, or one that must be
  filled and is not, is found when the window looks wrong, never at compile time. The stack's rule is that a compile
  error beats a startup error beats a runtime error.
- **Every container writes it again.** The main window would be a second `add` registry, and every application with
  panels a third.

### The proposal: a recipe may take every provider of a type

The framework's part is one rule in the processor; the regions themselves stay the application's.

- **`All<T>`, a new parameter shape for a recipe:** every provider of `T` in the build, zero or more, as an immutable,
  ordered list. The providers of a type taken as `All<T>` are exempt from *exactly one provider wins*; that is what
  they are for.
- **`Optional<T>`, the other one:** zero or one provider. Two is the error it is today, naming both.
- **A region is a type its owner declares, and a piece is a provider of it.** The base declares what its main window
  holds, as interfaces in its own package (`SidePiece`, `Centre`, `StatusPiece`), and builds the window in one recipe:

  ```java
  @Provides
  Ui ui(Gui gui, TitleBar titleBar, All<SidePiece> sides, Optional<Centre> centre, All<StatusPiece> status) { ... }
  ```

  A module fills one from its own configuration (`@Provides SidePiece navigator(...)`), as `LaterParts` does today, but
  by returning the piece rather than adding it. The base names no module, a module names only the base's interface,
  and leaving a module out leaves one fewer provider. *The empty window* (template-modules ruling 2) is `sides` empty
  and `centre` absent, and builds.

**Checked by the processor, at compile time:**

- a type taken as `All<T>` anywhere and as a plain `T` elsewhere is refused: it cannot be both a set and a single
  part;
- a `@Default` provider of a type taken as `All<T>` is refused, since there is nothing for it to stand back from;
- `Optional<T>` with two providers is the existing conflict error;
- the phase rule is unchanged: a recipe runs in the latest phase of what it takes, so `ui` runs after every piece.

**Why the framework does not declare the regions itself** (a `MainWindow` with a side, a centre and a status line):
the applications built so far lay their windows out differently (the Inspector's panels, Vexplore's rail beside the
list, MainFrame's terminal), and a fixed set would fit the template and few of them. A set of providers fits all of
them, and also fits what is not a window at all: the Examples window's list, a settings page's sections, a menu's
contributions.

**Why `All<T>` and not `List<T>`:** a recipe can already provide a `List<String>` as a value, and a parameter of that
type would be ambiguous between *that value* and *every provider of `String`*. A framework type says which, and lets
the check be exact.

**What it does not fix:** a module's view still needs its own widgets by a type of its own (`LaterPanel`), and a
recipe outside the top package returns an interface ([template-examples.md](template-examples.md), slice 1,
finding 2). A piece is then two providers: the module's panel, and its `SidePiece`, made from the panel by a one-line
recipe. That cost is recorded, not solved here.

**Its first witness needs no editor change:** the Examples window takes `All<Example>`, and each example provides
its `Example` instead of calling `add`. The navigator is the second, in step 3 of template-modules.md.

### The order of `All<T>`

The order has to be stable, since a window built in a different order on each build would be a bug no test pins
down. It need not mean anything. **Proposed:** the order is the providers' qualified names (configuration class, then
method), documented as carrying no meaning, and a region that has an order of its own says so in its piece type (a
`place()`, or sorting by name, as the Examples window sorts by question). *The alternative:* an order declared on each
provider (`@Order(10)`), which says the order where the provider is but makes every module know its neighbours'
numbers. Question 2.

## 2. Closing

**Today:** `CloseRequested` has one answerer, and the processor refuses two (*a close is answered once*). The editor
is that answerer, through its quit chain. With the editor a module, the base cannot answer for it, and a second
module with unsaved work (a canvas, a form) could not answer at all.

**Three shapes, each with its cost:**

| | Shape | For | Against |
| --- | --- | --- | --- |
| **A** | **Asked in turn.** Every part that hears `CloseRequested` is asked, one at a time, in the order of question 2; the next is asked only when the last said proceed, and the first refusal keeps the window open and ends the round. | One dialog at a time. Nothing acts on a quit that another part is about to refuse. Application code is as it is now: hear it, answer the ticket. | The framework addresses each hearer, not the topic: it delivers to each hearer's inbox in turn, which is new. |
| **B** | **Asked at once.** Every hearer gets the same request; the window closes when all have said proceed. | Smallest change: drop the one-answerer rule and count answers. | Two dialogs at once, and a part that saved or discarded on *proceed* has acted on a quit another part then refuses. |
| **C** | **One gatherer in the base.** Each module publishes what it has unsaved on a state topic keyed by module; the base answers the close, with one dialog listing everything. | One dialog for all of it, as many applications do. | The base must then ask each module to save or discard and wait for all of them: a wait over a set known only at run time, which chains do not have ([chains.md](chains.md) waits over declared branches). |

**Recommended: A.** It keeps each module's question its own (the editor's still names its files), it is the only one
of the three in which nothing acts before every part has agreed, and it asks nothing of the application that it does
not do now. The processor's rule becomes *every part that hears `CloseRequested` sends `CloseAnswer`*, which it
already checks for the one. Question 3.

## 3. The session

**This is not a framework gap.** The session is kept in `Settings`, which is a key-value store already, and each key
is read and written by a job (`ReadSession`, `WriteSession`). A module keeps its own piece by its own jobs, under keys
that begin with its name (`editor.files`, `navigator.folder`), and a module left out leaves its keys unread, which
costs nothing. That is what template-modules ruling 1 asked of the base: the store is the base's (the framework's,
in fact), and what is kept is each module's.

**The one thing to decide with it is the command line.** Today `ReadSession` reads the arguments and the editor
decides: a file opens in a tab, a folder in the navigator. Split, each module reads `Launch` and takes the arguments
of its own kind (the editor files, the navigator folders), and an argument that no module takes is ignored, as one
that is not a path is today. Proposed as the template's convention, written in its `CLAUDE.md`, rather than as a
framework rule. Question 4.

## The author's rulings

Answered 2026-11-03, each **accepted as recommended**: taken without discussion, so sound as far as its reason goes,
and the first to re-check when something depends on it ([TODO.md](../TODO.md) says what the label means).

1. **Regions are `All<T>` and `Optional<T>`**, recipe parameters the processor checks, and each window's layout is the
   application's. **Why:** the applications built so far lay their windows out differently, so regions the framework
   declared would fit the template and few others; a set of providers fits every layout, and the Examples window and
   other non-window collections besides.
2. **The order is stable by qualified name and means nothing**, for `All<T>` and for the parts asked to close; a region
   that has an order says so in its piece. **Why:** a stable order is all a build needs to be repeatable, and a
   declared one (`@Order`) makes every module know its neighbours' numbers, which is a reach across modules of its own.
3. **Closing is asked in turn (A).** **Why:** one dialog at a time, nothing acts on a quit another part then refuses,
   and the application's code is as it is now; the cost is the framework delivering to each hearer in turn.
4. **The session and the command line are a template convention**: each module's keys prefixed by its name, kept by
   its own jobs, and each module taking the arguments of its own kind, written in the generated `CLAUDE.md`. **Why:**
   `Settings` is already a keyed store, so a framework mechanism would add machinery for a gap that is not there.

## Order of work, once ruled

1. **`All<T>` and `Optional<T>` in the processor**, with its tests (the four checks above), and the generated wiring
   passing them.
2. **The Examples window moves to `All<Example>`**: its first witness, in the template, with nothing of the editor
   changed. `noFileNamesAModuleItDoesNotRequire` and the matrix hold it.
3. **Closing, asked in turn**, with the windows witness in the acceptance run (`Gate`) given a second answerer.
4. **The navigator out of the editor** (template-modules step 3): the main window's first region, with the session's
   folder key and the command line's folders going with it.

## As built

**Step 1, 2026-11-03:** `All<T>` and `Optional<T>` in the processor, recorded in
[components.md](../components.md) section 13. What building it found:

1. **A piece is an interface, and so is anything else a recipe returns.** The processor's rule that a public recipe
   returns an interface, and that a record behind `@Provides` is refused even package-private, applies to pieces as
   to any value: the tests' pieces are an interface (`Piece`) built from a package-private record, and an optional
   `Centre` is an interface built from a lambda. This is the cost *What it does not fix* names, met at once.
2. **Each piece is a field of its own in the wiring**, named for its recipe, so it is readable through an accessor as
   any part is; a type taken as `All<T>` never shares the one field a mode's single provider would write.
3. **Both shapes exist in every mode.** A piece built only in some modes is null in the others and left out, and an
   `Optional<T>` whose one provider is not built is empty, so the part taking either is built in every mode it
   declares rather than narrowed to its pieces'.

**Step 2, 2026-11-03:** the Examples window takes `All<Example>`, in the template, with nothing of the editor
changed. What it changed:

1. **The window and its page are two recipes.** An example builds its part into the window's `Gui`, so the window
   has to exist before any example, and the page has to come after all of them: `examples(Windows)` provides the
   `ExamplesWindow` (the window, nothing else), and `page(ExamplesWindow, All<Example>)` lays out the page. The
   processor orders them by their parameters; `ExamplesWindow.add` is gone, and `ExamplesList` is the page as its
   view sees it.
2. **`Example` is an interface with an `of`**, since the processor refuses a record behind `@Provides`; it was a
   record. An example now provides two things, its panel for its view and its `Example` for the page, the second a
   one-line recipe from the first (`LaterParts.laterExample`): the two-provider cost *What it does not fix* named.
3. **An empty page says why.** Seen live with `--modules examples`, which builds the window with no example in it:
   a blank page, with nothing to say whether something was broken. It now says the project was made without
   examples and how to read or add one.

Checked by the template's tests, the acceptance matrix (`none`, `examples`, and the default, whose `LaterWindowTest`
and `ExamplesTest` run in its build), the generated wiring (`page(examples, All.of(laterExample))`), and
`checks/later.txt` driven live, with the page shown on the later example's question.

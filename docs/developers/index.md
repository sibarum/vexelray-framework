# Developer guide

This is the place to start for building a desktop application on VexelRay, whether a coding agent writes most of
it, you do, or the two of you take turns. Every other document on the stack is linked from here, so if you are
looking for something, look in the table below first.

## I want to…

| I want to… | Read |
| --- | --- |
| Know what I am signing up for | [What you are signing up for](#what-you-are-signing-up-for), below |
| Install the stack | [Getting started §1–2](../getting-started.md#1-what-you-need): one script, `bootstrap.sh` |
| Make a new project and run it | [Getting started §3–4](../getting-started.md#3-generate-a-project) |
| Find my way around a generated project | [Getting started §5](../getting-started.md#5-find-your-way-around) |
| Turn the generated editor into something that is not an editor | [Getting started: Turning it into your application](../getting-started.md#turning-it-into-your-application) |
| See how something is done before doing it | The generated `EXAMPLES.md`, and F1 in the running application |
| Add a part, a setting, or replace a default | [Getting started §5](../getting-started.md#adding-a-part), then [overview.md](../overview.md#phases) for the order parts are built in |
| Work with a coding agent | [Building with an LLM](with-an-llm.md) |
| Write code by hand | [Coding in VexelRay IDE](in-vex.md), or any other Java IDE |
| Build a view the widgets don't have (a timeline, a map, a board) | [Building a custom view](custom-view.md) |
| Make changes animate instead of jump | [Making it move](motion.md) |
| Run something in the background without freezing the window | [Threading conventions](../threading.md), and the generated README's *Threading, in one paragraph* |
| Drive the app, take screenshots, test it end to end | [Getting started §7](../getting-started.md#7-drive-it-and-take-screenshots), then the [ottermate guide](https://github.com/sibarum/vexelray-gui/blob/master/docs/guides/ottermate.md) |
| Look up an ottermate verb | [The automation reference](https://github.com/sibarum/vexelray-gui/blob/master/docs/reference/automation.md), or `ottermate help` |
| Log, and find the log | [Getting started §8](../getting-started.md#8-logging) |
| Build the executable and an installer | [Shipping it](shipping.md) |
| Look up a widget or a GUI API | Its javadoc: Ctrl+Enter in [VexelRay IDE](in-vex.md), or another IDE. The GUI's design documents are [indexed here](https://github.com/sibarum/vexelray-gui/blob/master/docs/README.md) |
| Know which repository does what | [The repositories](../repositories.md) |
| Understand how the framework works | [Overview](../overview.md), then [Architecture](../architecture.md) for the deep version |
| Tell a gap in the framework from a mistake of mine | [TODO.md](../TODO.md), and [what v1 means](../v1.md) |
| Report something missing or wrong | Write it in your project's `docs/framework-notes.md`, then [open an issue](https://github.com/sibarum/vexelray-framework/issues/new) |
| Look back at a finished milestone | [Looking back at a milestone](retrospective.md): the retrospective, in your project's `docs/retrospective.md` |

## What you are signing up for

- **Java 25**, compiled to a native executable with GraalVM. The window is drawn with Vulkan, so anything you can
  draw, you can put in it.
- **Windows or Linux.** Windows is the most complete; Linux builds, runs and ships. macOS is a skeleton.
- **Before v1.** This is 0.2, an experimental prerelease, built from the stack's `v0.2` branches. It is not
  released yet: the applications the [one-line installer](https://sibarum.github.io/) installs, VexelRay IDE among
  them, are 0.1.2, and they work on a 0.2 project all the same. Names still change between versions, and
  [TODO.md](../TODO.md) is honest about what is missing. [What v1 means](../v1.md) says what will stop changing.
- **Not on Maven Central.** You clone the stack and install it to your own Maven repository, once, with one
  script. It takes about half an hour, most of it the first build.

## The way through

| | Step | Where |
| --- | --- | --- |
| 1 | Install the tools and build the stack | [Getting started §1–2](../getting-started.md#1-what-you-need) |
| 2 | Generate a project from the template, and run it | [Getting started §3–4](../getting-started.md#3-generate-a-project) |
| 3 | Learn your way around what was generated, and decide what of the editor to keep | [Getting started §5](../getting-started.md#5-find-your-way-around), and its [Turning it into your application](../getting-started.md#turning-it-into-your-application) |
| 4 | Turn it into your application: | |
| | with a coding agent | **[Building with an LLM](with-an-llm.md)** |
| | by hand, in VexelRay IDE | **[Coding in VexelRay IDE](in-vex.md)** |
| | when it needs a view of its own | **[Building a custom view](custom-view.md)** and **[Making it move](motion.md)** |
| 5 | Check it the way a person would, with `ottermate` | [Getting started §7](../getting-started.md#7-drive-it-and-take-screenshots), and the [ottermate guide](https://github.com/sibarum/vexelray-gui/blob/master/docs/guides/ottermate.md) |
| 6 | At the end of each milestone, read it back and write down what it showed | **[Looking back at a milestone](retrospective.md)** |
| 7 | Build the executable and give people a one-line installer | **[Shipping it](shipping.md)** |

Steps 4 to 6 repeat once per milestone. Most people do step 4 both ways: an agent for the large, well-described changes, and an editor for reading what it
wrote and for the small fixes that are quicker to make than to explain.

## What the template gives you

The template, `vexel-desktop`, does not generate an empty window. It generates a small but complete text editor:
tabs, a file navigator, Markdown in colour, a question before closing with unsaved work, and a session that comes
back the next time. An editor is the smallest real application that reaches every part of the framework, so the
project starts out showing you how each part is done. Beside it come the worked examples: `EXAMPLES.md` lists each
question an application reaches for ("do something later", "a second window") with the files that answer it, their
test and their `ottermate` check, and F1 opens them in the running application. An editor of some kind keeps the
shape and replaces what it does; anything else takes the editor out first and keeps the rest
([Getting started](../getting-started.md#turning-it-into-your-application) says what that leaves).

[VexelRay IDE](../apps/text-editor.md), [MainFrame](../apps/mainframe.md), [Vexplore](../apps/vexplore.md),
[Rasterware](../apps/pix.md) and VexelRay Inspector were all generated from this template for 0.2. Their source is the
best guide to what a generated project grows into, each on its `v0.2` branch:

| Application | Read it for |
| --- | --- |
| [text-editor-vexel-demo](https://github.com/sibarum/text-editor-vexel-demo) | An editor that kept the template's shape: grammars and highlighting off the GUI thread, the OS file dialogs |
| [mainframe](https://github.com/sibarum/mainframe) | The editor taken out first; a native process (ConPTY) behind the window, a settings window, workspaces. Its `docs/rebuild.md` is a whole rebuild planned as milestones |
| [vexplore](https://github.com/sibarum/vexplore) | The editor taken out first; regions of its own, actions that can be undone, a dock |
| [vexelray-inspector](https://github.com/sibarum/vexelray-inspector) | Panels on a timeline that can be rewound, fed from another running application |

Calculator and Scheduler started from the 0.1 template and have not been brought to 0.2 yet, so read them for ideas,
not for idioms.

## Where to ask

Something did not build, a step here was wrong, or the framework is missing something you needed:
[open an issue](https://github.com/sibarum/vexelray-framework/issues/new). Your project's
`docs/framework-notes.md` is where you write down each gap as you find it, so when you report one you will already
have written it up.

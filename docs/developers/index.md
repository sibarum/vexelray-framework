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
| Add a part, a setting, or replace a default | [Getting started §5](../getting-started.md#adding-a-part), then [overview.md](../overview.md#phases) for the order parts are built in |
| Work with a coding agent | [Building with an LLM](with-an-llm.md) |
| Write code by hand | [Coding in Vex](in-vex.md), or any Java IDE |
| Build a view the widgets don't have (a timeline, a map, a board) | [Building a custom view](custom-view.md) |
| Make changes animate instead of jump | [Making it move](motion.md) |
| Run something in the background without freezing the window | [Threading conventions](../threading.md), and the generated README's *Threading, in one paragraph* |
| Drive the app, take screenshots, test it end to end | [Getting started §7](../getting-started.md#7-drive-it-and-take-screenshots), then the [ottermate guide](https://github.com/sibarum/vexelray-gui/blob/master/docs/guides/ottermate.md) |
| Look up an ottermate verb | [The automation reference](https://github.com/sibarum/vexelray-gui/blob/master/docs/reference/automation.md), or `ottermate help` |
| Log, and find the log | [Getting started §8](../getting-started.md#8-logging) |
| Build the executable and an installer | [Shipping it](shipping.md) |
| Look up a widget or a GUI API | Its javadoc: Ctrl+Enter in [Vex](in-vex.md), or your IDE. The GUI's design documents are [indexed here](https://github.com/sibarum/vexelray-gui/blob/master/docs/README.md) |
| Know which repository does what | [The repositories](../repositories.md) |
| Understand how the framework works | [Overview](../overview.md), then [Architecture](../architecture.md) for the deep version |
| Tell a gap in the framework from a mistake of mine | [TODO.md](../TODO.md), and [what v1 means](../v1.md) |
| Report something missing or wrong | Write it in your project's `docs/framework-notes.md`, then [open an issue](https://github.com/sibarum/vexelray-framework/issues/new) |

## What you are signing up for

- **Java 25**, compiled to a native executable with GraalVM. The window is drawn with Vulkan, so anything you can
  draw, you can put in it.
- **Windows or Linux.** Windows is the most complete; Linux builds, runs and ships. macOS is a skeleton.
- **Before v1.** This is 0.1.x, an experimental prerelease. Names still change between versions, and
  [TODO.md](../TODO.md) is honest about what is missing. [What v1 means](../v1.md) says what will stop changing.
- **Not on Maven Central.** You clone the stack and install it to your own Maven repository, once, with one
  script. It takes about half an hour, most of it the first build.

## The way through

| | Step | Where |
| --- | --- | --- |
| 1 | Install the tools and build the stack | [Getting started §1–2](../getting-started.md#1-what-you-need) |
| 2 | Generate a project from the template, and run it | [Getting started §3–4](../getting-started.md#3-generate-a-project) |
| 3 | Learn your way around what was generated | [Getting started §5](../getting-started.md#5-find-your-way-around) |
| 4 | Turn it into your application: | |
| | with a coding agent | **[Building with an LLM](with-an-llm.md)** |
| | by hand, in Vex | **[Coding in Vex](in-vex.md)** |
| | when it needs a view of its own | **[Building a custom view](custom-view.md)** and **[Making it move](motion.md)** |
| 5 | Check it the way a person would, with `ottermate` | [Getting started §7](../getting-started.md#7-drive-it-and-take-screenshots), and the [ottermate guide](https://github.com/sibarum/vexelray-gui/blob/master/docs/guides/ottermate.md) |
| 6 | Build the executable and give people a one-line installer | **[Shipping it](shipping.md)** |

Most people do step 4 both ways: an agent for the large, well-described changes, and an editor for reading what it
wrote and for the small fixes that are quicker to make than to explain.

## What the template gives you

The template, `vexel-desktop`, does not generate an empty window. It generates a small but complete text editor:
tabs, a file navigator, Markdown in colour, a question before closing with unsaved work, and a session that comes
back the next time. An editor is the smallest real application that reaches every part of the framework, so the
project starts out showing you how each part is done. You keep its shape and replace what it does.

[Vex](../apps/text-editor.md), [Calculator](../apps/calculator.md), [MainFrame](../apps/mainframe.md) and Scheduler
all started as this template. Their source is the best guide to what a generated project grows into:

| Application | Read it for |
| --- | --- |
| [text-editor-vexel-demo](https://github.com/sibarum/text-editor-vexel-demo) | Grammars and highlighting off the GUI thread, the OS file dialogs, a second mode of the same panel |
| [calculator-vexel-demo](https://github.com/sibarum/calculator-vexel-demo) | A computation engine behind a small window, and plots drawn on the canvas |
| [mainframe](https://github.com/sibarum/mainframe) | A native process (ConPTY) behind the window, and workspaces |
| `vexelray-scheduler` (not yet published) | A view of its own (lanes of bars placed on a timeline), and motion: nothing on screen changes in one frame |

## Where to ask

Something did not build, a step here was wrong, or the framework is missing something you needed:
[open an issue](https://github.com/sibarum/vexelray-framework/issues/new). Your project's
`docs/framework-notes.md` is where you write down each gap as you find it, so when you report one you will already
have written it up.

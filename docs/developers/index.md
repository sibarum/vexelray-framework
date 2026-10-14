# Build your own

The applications in the suite are built from one template, on a framework anyone can use. This part of the site is
for building your own desktop application on it, whether a coding agent writes most of it, you do, or the two of
you take turns.

## What you are signing up for

- **Java 25**, compiled to a native Windows executable with GraalVM. The window is drawn with Vulkan, so anything
  you can draw, you can put in it.
- **Windows only, for now.** The engine's Linux and macOS backends are skeletons.
- **Before v1.** This is 0.1.0, an experimental prerelease. Names still change between versions, and
  [TODO.md](../TODO.md) is honest about what is missing. [What v1 means](../v1.md) says what will stop changing.
- **Not on Maven Central.** You clone the stack and install it to your own Maven repository, once. It takes about
  half an hour, most of it the first build.

## The way through

| | Step | Where |
| --- | --- | --- |
| 1 | Install the tools and build the stack | [Getting started §1–2](../getting-started.md#1-what-you-need) |
| 2 | Generate a project from the template, and run it | [Getting started §3–4](../getting-started.md#3-generate-a-project) |
| 3 | Learn your way around what was generated | [Getting started §5](../getting-started.md#5-find-your-way-around) |
| 4 | Turn it into your application: | |
| | with a coding agent | **[Building with an LLM](with-an-llm.md)** |
| | by hand, in Vex | **[Coding in Vex](in-vex.md)** |
| 5 | Check it the way a person would, with `ottermate` | [Getting started §7](../getting-started.md#7-drive-it-and-take-screenshots), and the [ottermate guide](https://github.com/sibarum/vexelray-gui/blob/main/docs/guides/ottermate.md) |
| 6 | Build the executable and give people a one-line installer | **[Shipping it](shipping.md)** |

Most people do step 4 both ways: an agent for the large, well-described changes, and an editor for reading what it
wrote and for the small fixes that are quicker to make than to explain.

## What the template gives you

The template, `vexel-desktop`, does not generate an empty window. It generates a small but complete text editor:
tabs, a file navigator, Markdown in colour, a question before closing with unsaved work, and a session that comes
back the next time. An editor is the smallest real application that reaches every part of the framework, so the
project starts out showing you how each part is done. You keep its shape and replace what it does.

[Vex](../apps/text-editor.md), [Calculator](../apps/calculator.md) and [MainFrame](../apps/mainframe.md) all started
as this template. Their source is the best guide to what a generated project grows into:

| Application | Read it for |
| --- | --- |
| [text-editor-vexel-demo](https://github.com/sibarum/text-editor-vexel-demo) | Grammars and highlighting off the GUI thread, the OS file dialogs, a second mode of the same panel |
| [calculator-vexel-demo](https://github.com/sibarum/calculator-vexel-demo) | A computation engine behind a small window, and plots drawn on the canvas |
| [mainframe](https://github.com/sibarum/mainframe) | A native process (ConPTY) behind the window, and workspaces |

## Where to ask

Something did not build, a step here was wrong, or the framework is missing something you needed:
[open an issue](https://github.com/sibarum/vexelray-framework/issues/new). Your project's
`docs/framework-notes.md` is where you write down each gap as you find it, so when you report one you will already
have written it up.

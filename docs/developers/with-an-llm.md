# Building with an LLM

The VexelRay stack was largely written by a coding agent working alongside its author, and it is laid out for one.
Every repository has a `CLAUDE.md` that states the rules the code does not show. A generated project gets one too.
And `ottermate` lets an agent drive and photograph the window it built, so it can check its own work instead of
reporting that it compiled.

This page covers setting that up, what to ask for, and what to check before you believe an answer.

## What to use

| Tool | Why |
| --- | --- |
| **A coding agent that runs commands** | It needs to build, run the tests and run `ottermate` itself. [Claude Code](https://claude.com/claude-code) is what the stack was built with, and it reads `CLAUDE.md` on its own. Another agent works too: point it at `CLAUDE.md` in your first message, or copy the file to whatever name it reads (`AGENTS.md`, say) |
| **`ottermate` on the agent's `PATH`** | So it can launch the application, press keys, read what is on screen and take screenshots. [Getting started §7](../getting-started.md#7-drive-it-and-take-screenshots) says where it is |
| **The stack's checkouts next to your project** | The framework's source is its most complete documentation. An agent that can read `vexelray-gui` answers its own questions; one that cannot guesses |
| **Git** | Commit before every request, so everything the agent changes is a diff you can read and undo |
| **An editor to read along in** | [Vex](in-vex.md) or anything you like. You will review a lot more code than you write |

A chat assistant that cannot run anything can still explain the code and draft it. But then you run every build and
take every screenshot yourself, and most of the advantage is gone.

## Set up the workspace

Generate the project into the same folder as the stack's checkouts ([Getting started §3](../getting-started.md#3-generate-a-project)),
so the layout is:

```
vexelray/
  intrabuf/  atchung/  ...  vexelray-gui/  vexelray-framework/
  my-app/            <- generated; the agent works here
```

Then commit it as generated, before the first request:

```bash
cd my-app && git init && git add -A && git commit -m "Generated from vexel-desktop"
```

Start the agent in `my-app`, and let it read the folder above. With Claude Code:

```bash
claude --add-dir ..
```

Read the generated `CLAUDE.md` yourself before you start. It is short, and it is what the agent will be holding
your code to. As you settle things about your application ("the model is never null", "every command has a
landmark"), add them to it. It is the only thing the agent remembers from one session to the next.

## The first request: a plan, not code

The project starts as a text editor, so the first job is deciding what to keep. Ask for that before any code:

```
This project was generated from the vexel-desktop template, which is a small text editor.
I'm turning it into a log viewer: it opens a .log file, follows it as it grows, colours
lines by level, and filters them by a word typed into a field above the text.

Read CLAUDE.md, README.md, Recipes.java, Model.java and Ui.java first. Then tell me which
of the generated parts we keep as they are, which we change, and which we delete, and
propose the order to do it in, smallest working step first. Don't change any files yet.
```

Correct the plan. That is much cheaper than correcting the code. Then ask for one step of it at a time.

## Requests that work

**One slice at a time, model to screen, with its test.** "Add level colouring: a pure function from a line to its
spans in `text/`, unit-tested, called from where `Buffer` calls `Markdown.spans` now." A slice like that is small
enough to review in one sitting. "Build the log viewer" is not.

**Say what done looks like, in a form that can be checked.** Name the `ottermate` run that proves it:

```
Done means: mvn test passes, and an ottermate script that opens fixtures/app.log,
types ERROR into the filter field and awaits status.message "3 lines" succeeds.
Take a screenshot of that state and look at it before telling me it works.
```

**Point at a precedent.** The template and the suite's applications have already solved most problems once. "Read
how Vex runs its highlighter on a worker and do the same for the filter" gets you code shaped like the rest of the
stack. Without the pointer, the agent invents something.

**Ask for the framework note.** "If you have to work around something the framework doesn't do, write it in
`docs/framework-notes.md` as an FN entry when you write the workaround." That is how the gaps get found and fixed
upstream instead of copied into every application.

**Say what is off limits.** In your project the stack is read-only. The repositories' own `CLAUDE.md` files say the
siblings are modifiable, but that is written for the stack's author. Say it once: "Don't edit anything outside
`my-app`. If the fix belongs in the framework, write the note and tell me."

## Requests that go badly

- **"Make it look better."** The agent cannot know what you think better is. Give it a sketch, a screenshot of
  something you like, or a specific change: "the filter field is too far from the text; put it in the status bar".
- **Several features in one request.** You get a large diff with the mistakes spread evenly through it.
- **Its own way of taking screenshots.** It may offer to add a `--capture` flag, a robot, or a screen grab. Say no.
  Screenshots come from `ottermate`, which takes them on the application's own GPU device, so they show what the
  application really drew.
- **Accepting "it compiles" or "the tests pass" for a change to the window.** Ask for the screenshot, and open it
  yourself.

## The rules worth repeating

The generated `CLAUDE.md` states these. They are listed again here because each one is a mistake an agent makes
confidently, and the result is usually a hang or a stutter rather than an error:

- **No reflection.** The application compiles to a native image, and the framework's wiring is generated at compile
  time so nothing needs it.
- **Nothing in the frame loop allocates, logs or blocks.** That code runs on every frame.
- **The GPU and the window stay on the main thread.** Handlers run on workers, and disk work runs on the offload
  lane. The generated README's *Threading, in one paragraph* is the whole model.
- **All input comes through tactroller.** No polling the keyboard, and no listener of your own on the OS.
- **Log through `sibarum.probe.Log`.** stdout is reserved for lines another program reads, like the
  `automation: localhost:<port>` line `ottermate` waits for.
- **Nothing appears, moves or grows on hover**, and only command keys auto-repeat.
- **A compile error is better than a startup error, and a startup error is better than a runtime one.** When the
  agent is choosing between a check in the processor and a check when the program runs, it should choose the
  processor.

## Checking its work

Let the agent run this loop itself. You read the result.

```bash
mvn test
```

```bash
ottermate --script check.txt --launch mvn.cmd compile exec:exec -Dautomation=0
```

where `check.txt` is a few lines naming things by their landmarks (the names in `Landmarks.java`):

```
settle
click path
type C:/work/fixtures/app.log
key ENTER
await status.file app.log
shot opened.png
```

Each command is echoed above its reply, and the script stops at the first one that fails, so the transcript shows
which step went wrong. `key` takes chords too (`key CTRL+S`), so every shortcut can be tested. While it runs,
the application logs everything at trace level to `target/logs/<app>.log`. Ask the agent to read that log when a run
fails, before it starts changing code.

## When it gets stuck

| Symptom | Usually |
| --- | --- |
| `Could not resolve dependencies` for `dev.vexelray...` | A stack repository was never installed, or a newer one was pulled and not reinstalled. `mvn install` it, and everything after it in [the table](../getting-started.md#2-install-the-stack) |
| The window freezes, nothing is thrown | A lock held while committing to the model, or one taken from a model listener. Both rules are written on the generated `Workspace` |
| It works under `mvn exec:exec` and not as a native executable | A missing native-image registration. The generated README's *A native executable* section has the fix |
| The agent keeps rewriting the same thing | Stop it. Clear the session, and start again with a smaller request and the file it should copy |
| A framework feature you need is missing | Not your bug. Write it up in `docs/framework-notes.md` and [open an issue](https://github.com/sibarum/vexelray-framework/issues/new) |

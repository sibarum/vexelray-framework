# Coding in Vex

[Vex](../apps/text-editor.md) is the suite's text editor, and it is a good place to write VexelRay code by hand. It
was built from the same template your project was, it highlights Java and can jump into the framework's source,
and it works alongside a terminal and a file explorer from the same suite. Use it on its own, or to read and fix up
what a coding agent wrote.

## Getting it

Install the suite from [sibarum.github.io](https://sibarum.github.io/). One line of PowerShell installs Vex,
[MainFrame](../apps/mainframe.md) (the terminal) and [Vexplore](../apps/vexplore.md) (the file explorer). Each one
is also put on your `PATH`, so they can open each other and you can open them from a prompt.

Or build it from source like any generated project:
[text-editor-vexel-demo](https://github.com/sibarum/text-editor-vexel-demo), `mvn compile exec:exec`.

## A working layout

Open the project in Vex, and a terminal in the same folder beside it:

```bash
text-editor C:/work/vexelray/my-app
```

```bash
mainframe --dir=C:/work/vexelray/my-app --dir=C:/work/vexelray/vexelray-gui
```

That gives Vex with your project as the navigator's root, and MainFrame with one tab in your project (for
`mvn compile exec:exec`, `mvn test` and `ottermate`) and one in the stack (for searching it). A list of folders you
open often can be saved as a MainFrame workspace and reopened with `mainframe --workspace=<name>`.

## Finding the project's root

Click the folder's name at the top of the navigator to switch it to **Open**. While you move over the folders, the
editor shows what each one is: its Maven artifactId and modules, whether it is a module of a bigger build, whether
it is a git repository, its languages, and its Java classes, records and most-used annotations. A folder with
`@VexelApp` and `@Provides` in that list is a VexelRay application. In a folder of checkouts, that is the quickest
way to find the one you want.

## Reading the framework while you write

**Ctrl+Enter** on a name in Java goes to its declaration: in your project, or in a library on the class path,
which includes the whole stack. A library opens read-only, and decompiled if it was installed without sources.
Press it again to move to the next declaration of the same name.

This is how to learn the framework. Put the caret on `@Provides`, `Gui`, `State` or `TextField` in your
`Recipes.java`, press Ctrl+Enter, and read the javadoc where the type is declared. Much of the framework's
documentation is written there, next to the code it describes.

## The keys you will use most

| Keys | Does |
| --- | --- |
| Ctrl+Enter | Go to declaration (Java); again for the next one |
| Ctrl+F | Find in the file. Enter and Shift+Enter step through the matches |
| Ctrl+B / Ctrl+Shift+E | Show or hide the navigator / put the keyboard in it |
| Ctrl+O / Ctrl+Shift+O | Open a file / open a folder as the root |
| Ctrl+Tab, Ctrl+Shift+Tab | Next / previous tab |
| Alt+Z | Word wrap on or off |
| Ctrl+S / Ctrl+W | Save / close the tab |

Every tab and every row in the navigator has **Open in Vexplore** in its menu, to see a file in its folder.

## What it does not do

Vex is an editor, not an IDE, so know where it stops:

- **No completion and no error marks while you type.** It has its own Java index, not a language server. Errors
  come from `mvn compile` in the terminal next to it.
- **Find is within one file.** To search the project or the stack, use `grep` (or `git grep`) in MainFrame.
- **No debugger, no refactoring, no git view.**

When you need those, use an IDE: a generated project is an ordinary Maven project and opens in IntelliJ IDEA,
Eclipse or VS Code as it is. The two work well together: the IDE for large refactors, and Vex open on the same
folder for reading and quick edits. Vex comes back with the same folder and files open every time.

## Together with an agent

If a coding agent is writing most of the code ([Building with an LLM](with-an-llm.md)), Vex is where you review its
work. Keep the project open in Vex while the agent works. Read each file it says it changed, run `git diff` in
MainFrame, and fix small things yourself instead of explaining them. Save before you hand the agent the next
request, so you are both working on the same file.

# Vex

**A tabbed text editor with a file navigator, that remembers where you were.**

Open a folder and it becomes the navigator beside your tabs. Close the window and open it again, and it comes
back where you left it: the same folder, the same files, and the one you were looking at still in front.
Unsaved work is never remembered silently. Closing asks about it first.

## Choosing a folder

- **Ctrl+O** opens a file and **Ctrl+Shift+O** a folder, through the system's own file dialogs.
- **Click the folder's name** at the top of the navigator to switch it to **Open**: folders only, your recent
  ones first, then the folder you are in among its neighbours, with a path above to climb as far as the top of
  the drive. A click on a folder makes it the one you work in. The arrow keys only move, so looking around changes
  nothing.
- **While you look, the editor steps aside** for a picture of the folder under the pointer: its languages, its
  Maven modules, whether it is a git repository, and for Java its classes, records and most-used annotations.
- A window with nothing open starts in Open.

## Editing the way editors do

- **Sixteen formats highlighted**: Java, Python, JavaScript, JSON and JSONC, Markdown, HTML, CSS, XML, YAML,
  INI and `.properties`, shell, PowerShell, batch, diff and Dockerfile.
- **Double-click a word, triple-click a line**, and keep the button down to extend by whole words or lines.
- **Home** goes to where the indentation ends, then to the margin. **Enter** keeps the indentation, and **Tab**
  over several lines indents all of them.
- **Find** with Ctrl+F, **word wrap** with Alt+Z, **zoom** with Ctrl+= and Ctrl+-.
- **Go to declaration** in Java with Ctrl+Enter, and again for the next one.
- **Whitespace that matters shows as a faint dot**: indentation, trailing blanks, and runs of two or more. The
  single space between words is left alone.

## Careful with your files

- **It keeps line endings.** A CRLF file saves as CRLF, and a file whose line endings are not your system's ends
  each line with a faint `¬`, so you can see how it will be saved.
- **A save cannot half-happen.** It writes beside the file and then moves over it, so a failed save leaves the
  old file whole.
- **It refuses what it would corrupt.** Binary files, files with control characters, lines over 100,000
  characters and files over 8 MB are refused with the reason, rather than opened and damaged on save.
- **It never quits over unsaved work.** Closing anything unsaved asks: Save, Don't save, or Cancel. A save that
  fails cancels the close.

## With the rest of the suite

Every tab and every row in the navigator has **Open in Vexplore**, which shows the file in
[Vexplore](vexplore.md) with the file selected. Installing adds `text-editor` to your PATH, so
`text-editor <folder or files>` opens a new window from any terminal, [MainFrame](mainframe.md) included. It
also registers for text and source file types: it is always listed under **Open with**, and becomes the default
only for types no other program has claimed.

## Status

It has not been released yet.

[Install the suite](../install/) · [Source](https://github.com/sibarum/text-editor-vexel-demo)

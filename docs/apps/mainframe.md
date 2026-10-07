# MainFrame

**A terminal window for the shell you already have.**

MainFrame is a real terminal emulator, not a shell of its own. PowerShell runs unmodified inside a Windows
pseudoconsole (ConPTY), and MainFrame draws what it prints and sends it your keys. It is the same shell you had
before, in a different window.

## What it does

- **Tabs**, with rename and an animated switch (slide, fade, or none).
- **The shell of your choice for each new tab**: Windows PowerShell, PowerShell 7 when it is on the PATH, or
  Command Prompt. A command line given at launch replaces the shell for that run.
- **Copy and paste that behave**: Ctrl+Shift+C and Ctrl+Shift+V always work, and right-click copies a selection
  or pastes. With *Remap keybindings* on, which is the default, plain Ctrl+C and Ctrl+V copy and paste, and Esc
  sends the interrupt instead.
- **Scrollback** with the wheel or Shift+PageUp and Shift+PageDown.
- **Box drawing, block elements and braille are drawn, not taken from the font**, so the tools that use them
  line up.
- **Settings in a panel** docked to the side: font size, the tab animation, key remapping and the default shell.
  Changes apply at once.

## With the rest of the suite

Installing adds `mainframe` to your PATH. The other applications are on your PATH too, so
`text-editor <folder>` or `vexplore <folder>` in a MainFrame tab opens that folder in a new window.

## Status

Windows PowerShell on Windows is the first target. The design is not tied to Windows. It has not been released
yet.

[Install the suite](../install/) · [Source](https://github.com/sibarum/mainframe)

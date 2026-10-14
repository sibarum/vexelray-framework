# Install VexelRay

Open **PowerShell** on Windows and paste this line:

```powershell
irm https://raw.githubusercontent.com/sibarum/vexelray-dist/main/install.ps1 | iex
```

That installs all seven applications below, for your user only. No administrator rights,
no restart, nothing else to download first.

You need Windows 10 or 11 (64-bit) and a graphics card with an up-to-date driver: everything here draws with
Vulkan.

This is **0.1.0, an experimental prerelease**: early, not v1, and expect rough edges. Please
[say what you find](#feedback).

## What you get

| | |
| --- | --- |
| [**Vexplore**](../apps/vexplore.md) | A file explorer that suggests instead of asking, and where every action can be undone |
| [**Vex**](../apps/text-editor.md) | A tabbed text editor with a file navigator, which comes back where you left it |
| [**Pix**](../apps/pix.md) | An image viewer: one picture, as large as the window allows, and the rest of its folder a key press away |
| [**MainFrame**](../apps/mainframe.md) | A terminal window for the shell you already have |
| [**Calculator**](../apps/calculator.md) | An ordinary-looking calculator that divides by zero |
| [**Fluid Sim**](https://github.com/sibarum/vexelray-sim-fluid) | Fluid simulation experiments: dam breaks, mixing and heat, live on the GPU |
| [**Rigid Sim**](https://github.com/sibarum/vexelray-sim-rigid) | Spheres that collide, bounce, spin and roll, live on the GPU |

They are separate programs that know about each other: a text file in Vexplore opens in Vex and a picture in
Pix, and Vex can show any file in Vexplore. Each one lands in the Start menu and on the desktop.

## What it changes on your computer

Everything is per user, and everything is undone by uninstalling:

- each application in its own folder under `%LOCALAPPDATA%\Programs`
- desktop and Start menu shortcuts
- your user `PATH`, so `vexplore`, `text-editor`, `pix`, `mainframe` and `calculator` work from a terminal
- **Open with** entries for the file types each application reads (text and source files for Vex, images for
  Pix). One becomes the *default* only for a type no other program has claimed; Windows does not let a new
  program take over an existing one, and neither does this
- an entry for each application in **Settings > Apps**

To leave some of that out, set any of these in the same PowerShell window *before* the install line:

```powershell
$env:INSTALL_NO_SHORTCUTS = '1'     # no desktop or Start menu shortcuts
$env:INSTALL_NO_PATH = '1'          # leave PATH alone
$env:INSTALL_NO_ASSOCIATIONS = '1'  # do not register for any file types
```

### Only some of them

```powershell
$env:INSTALL_ONLY = 'vexplore,text-editor,pix'
```

The ids are `vexplore`, `text-editor`, `pix`, `mainframe`, `calculator`, `fluid-sim` and `rigid-sim`.

## Updating

Run the same install line again. Each application is updated to its latest version, and one that is already
current is left alone.

## Uninstalling

Use **Settings > Apps**, where each application has its own entry. Or, from PowerShell, list what is installed
and remove all of it:

```powershell
& "$env:LOCALAPPDATA\Programs\Vexplore\uninstall.cmd" -List
& "$env:LOCALAPPDATA\Programs\Vexplore\uninstall.cmd" -All
```

The uninstaller removes exactly what was installed: files, shortcuts, `PATH` entries, file types and the
Settings entries. Files you put in those folders yourself are left alone.

## Is it safe to paste a line from the internet?

It is a fair question, so read the script first. It is one file:
[`install.ps1`](https://raw.githubusercontent.com/sibarum/vexelray-dist/main/install.ps1). What it does:

1. For each application, it downloads the application's
   [manifest](https://github.com/sibarum/vexelray-dist/tree/main/apps) and its installer, and refuses the
   installer unless its SHA-256 matches the one the manifest records.
2. That installer downloads the application from its
   [GitHub release](https://github.com/sibarum/vexelray-dist/releases), and refuses it unless its SHA-256 matches
   too. Nothing is written to your disk until every download has checked out.

The scripts, the manifests and the releases are all in one public repository,
[sibarum/vexelray-dist](https://github.com/sibarum/vexelray-dist).

## Feedback

Anything is useful: it did not install, a window looked wrong, something was confusing, or you just liked it.
[Open an issue](https://github.com/sibarum/vexelray-framework/issues/new) and say which application, what you did
and what happened. A screenshot helps.

If an application will not start, its log is the most useful thing to attach. It is in your home folder, under
the application's id: `%USERPROFILE%\.pix\logs` for Pix, `%USERPROFILE%\.text-editor\logs` for Vex, and so on.

macOS and Linux are not supported yet.

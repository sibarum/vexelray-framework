<meta http-equiv="refresh" content="0; url=https://sibarum.github.io/">

# Install VexelRay

The install page has moved to **[sibarum.github.io](https://sibarum.github.io/)**. Open **PowerShell** on Windows
and paste this line:

```powershell
irm https://sibarum.github.io/install.ps1 | iex
```

The old command, `irm https://raw.githubusercontent.com/sibarum/vexelray-dist/main/install.ps1 | iex`, still works
and installs the same thing.

On Linux, open a terminal and paste this line (no `sudo`: everything goes under `~/.local`). For now it installs
snapshot builds, <span data-version="linux">0.1.2-SNAPSHOT</span>:

```sh
curl -fsSL https://raw.githubusercontent.com/sibarum/vexelray-dist/main/preflight/install.sh | sh
```

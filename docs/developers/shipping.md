# Shipping it

Once your application runs, there are two steps to getting it onto someone else's computer: build a native
executable, and publish it with an installer people can run in one line, as the suite's applications are.

## 1. The executable

From a Visual Studio developer prompt, with a GraalVM 25 JDK as `JAVA_HOME`:

```bash
mvn -Pnative-release package -DskipTests
```

That writes `target/my-app.exe`, the build you ship: it has no console window, and its automation sockets are off
until the launch or the title bar's plug switches them on.
[Getting started §6](../getting-started.md#6-build-a-native-executable) explains the two builds, and the
generated README explains what to do when the native executable fails at something the JVM run did not.

Before you publish, run the release executable yourself on a clean folder. A native image only contains what was
reachable when it was built, so a code path you only ever tried under `mvn exec:exec` has not really been tested.

Replace the icon first: `src/main/rc/my-app.ico` is the framework's placeholder mark.

## 2. A one-line installer

[vexelray-installer](https://github.com/sibarum/vexelray-installer) turns the executable into a per-user Windows
installer served from a GitHub repository. It is the same tool behind the suite's install line. Clone it beside the
stack and build it:

```bash
git clone https://github.com/sibarum/vexelray-installer.git && (cd vexelray-installer && mvn install -DskipTests)
```

Describe your application in an `installer.json` at the root of your project:

```json
{
  "id": "my-app",
  "name": "My App",
  "publisher": "you",
  "description": "One line about what it does.",
  "repo": "you/my-app",
  "windows": {
    "binaries": [
      {
        "name": "my-app",
        "source": "target/my-app.exe",
        "icon": "src/main/rc/my-app.ico",
        "addToPath": true,
        "shortcuts": { "desktop": true, "startMenu": true, "label": "My App" }
      }
    ]
  }
}
```

Generate the installer, and publish the executable as a GitHub release. Publishing needs a token in `GITHUB_TOKEN`
with permission to write to `you/my-app`:

```bash
java -jar ../vexelray-installer/installer-core/target/installer-core-0.1.1-SNAPSHOT-cli.jar --config installer.json --version 0.1.0 --out dist --publish
```

(The jar's name carries the installer's own version. Use whichever `-cli.jar` your build wrote.) Leave out
`--publish` to see what would be written first.

Commit what it wrote to `dist` (`install.ps1`, `uninstall.ps1`, `update.ps1`, `manifest.json` and a README) to the
repository named in `repo`. Then anyone on Windows can install your application with:

```powershell
irm https://raw.githubusercontent.com/you/my-app/main/install.ps1 | iex
```

The installer checks every download against its SHA-256, needs no administrator rights, and adds an uninstall entry
to Settings. Running the same line again updates the application. File associations, a `PATH` entry, signing, and
jars instead of executables are all options; the [installer's README](https://github.com/sibarum/vexelray-installer#readme)
lists them, and `"fileAssociations"` in [Vex's installer.json](https://github.com/sibarum/text-editor-vexel-demo/blob/main/installer.json)
is a worked example.

## 3. Telling people

Say what it is, what it needs (Windows 10 or 11, a GPU with an up-to-date Vulkan driver), and that it is built on
a framework before v1. If you would like it listed alongside the suite's applications,
[open an issue](https://github.com/sibarum/vexelray-framework/issues/new) with a link and a screenshot.

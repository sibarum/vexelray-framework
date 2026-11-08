# Getting started

This takes you from nothing to a running VexelRay application, then through what was generated and how to grow it
into your own. Allow about half an hour, most of it the first build of the stack.

## 1. What you need

| | |
| --- | --- |
| **Windows 10 or 11, or Linux** | Windows is the most complete. Linux builds and runs; macOS is a skeleton |
| **A GPU with a Vulkan driver** | Any recent NVIDIA, AMD or Intel driver has one |
| **JDK 25** | A GraalVM 25 JDK if you also want native executables (step 6) |
| **Maven** and **Git** | On `PATH`. On Windows, Git for Windows also brings the Bash the commands below are written for |
| **A Rust toolchain** | From [rustup.rs](https://rustup.rs). One repository's image decoders are Rust, built from source |
| **Visual Studio Build Tools** | Windows only. Rust's default Windows toolchain links with them; native-image needs `link.exe` and the icon step `rc.exe` |
| **MSYS2** (Windows) or **make, cmake, ninja, nasm, pkg-config** (Linux) | The title bar's camera records video, through an FFmpeg holding only the AV1 encoders, which is built from source. On Windows, install [MSYS2](https://www.msys2.org) at `C:\msys64`, then in it: `pacman -S make diffutils mingw-w64-ucrt-x86_64-{gcc,cmake,ninja,pkgconf,nasm,ffnvcodec-headers,amf-headers,libvpl}` |

## 2. Install the stack

The stack is not on Maven Central yet. Each piece is its own repository, and they are built and installed to your
local Maven repository (`~/.m2`) once, side by side in one folder. One script does it:

```bash
mkdir vexelray && cd vexelray
```

```bash
git clone --branch v0.2 https://github.com/sibarum/vexelray-framework.git
```

```bash
./vexelray-framework/bootstrap.sh
```

It checks for the tools above, clones the other twelve repositories beside `vexelray-framework` (each on its `v0.2`
branch if it has one, and on `develop` if not: 0.2 needs both), builds
`imagelib-wrapper`'s native library and `ffmpeg-wrapper`'s video encoder for your OS, and runs `mvn install` in
each, **in dependency order**. Each
build's output goes to `vexelray-framework/target/bootstrap/`, and the first failure stops it and names its log.
`--tests` runs each repository's tests as it goes; `--check` only says what is missing.

When you pull a newer version of one repository, reinstall it and everything after it:

```bash
./vexelray-framework/bootstrap.sh --from vexelray
```

The order, and what each repository is. [The repositories](repositories.md) says more about each, and lists the
optional ones that add pictures and video, shipping, and simulations.

| Repository | What it is |
| --- | --- |
| `vexelray-build` | The Maven plugin that stamps each build with its exact source. Every other build uses it |
| `intrabuf` | Serialization the message bus carries its envelopes in |
| `atchung` | The typed message bus, and the logger nearly every repository uses |
| `supirvast` | The expression language the engine's shaders are written in |
| `dxgi-bridge` | Presentation on Windows |
| `kronometer` | Time and animation |
| `tactroller` | Input: keyboard, pointer, clipboard |
| `vexelray` | The engine: Vulkan, 2D drawing, text, windowing |
| `imagelib-wrapper` | Image decoding, in Rust behind a small Java API. Your application does not link it; `vexelray-gui`'s icon plugin reads icon art with it |
| `vexelray-gui` | The widgets, layout and theme |
| `ffmpeg-wrapper` | Video through FFmpeg. Your application links only its recording half, `ffmpeg-wrapper-record`: the AV1 encoders and no decoder |
| `vexelray-framework` | This repository |
| `vexelray-codec` | Pictures, video and sound on the GUI. Your application links `vexelray-codec-record`, which is what makes the title bar's camera record video as well as take screenshots |

`imagelib-wrapper`'s native library is built from source by its OS module and is not in the repository. The
script runs `imagelib-wrapper-windows/native/build.sh` (or `-linux/`) before that repository's install; run it by
hand if you install without the script. `ffmpeg-wrapper`'s video encoder is the same:
`ffmpeg-wrapper-record-windows/native/build.sh` (or `-linux/`). With `--tests`, the script also fetches the decoding
FFmpeg those two repositories' tests play recordings back with (`ffmpeg-wrapper-windows/native/build.sh`).

## 3. Generate a project

From the `vexelray-framework` folder:

```bash
java -cp vexelray-framework-template/target/classes dev.vexelray.framework.template.Generate --in .. --name my-app --group dev.example
```

That writes `../my-app`, and its last line is the command that runs it. On Linux the classes are in
`target-linux/classes` instead. `--name` is the folder and the Maven artifactId; the Java package becomes
`dev.example.myapp` and the main class `MyApp` unless you say otherwise. Other options include `--title`,
`--width`, `--height`, `--packageName` and `--className`; `--help` lists them all with their defaults. The project
starts at the framework's own version unless `--version` names another, and its mark is `<name>.ico` unless
`--icon` names the file (without `.ico`) a project bringing its own mark already has. `--modules` says which of the
template's modules to include: `all` (the default), `none`, or their names, and a module comes with any it requires;
`--help` lists them. Today they are the worked examples: `examples`, an Examples window on F1 with `EXAMPLES.md` as
its index, and `later`, how to do something later or once the typing stops. Keep the default the first time. The
examples are how the project answers "how do I…" before you have to ask, and `EXAMPLES.md` says how to take them out
once you no longer need them.

Before writing anything, the generator looks for the stack in your local Maven repository and names any piece it
cannot find (`not installed: ...`), since a missing `mvn install` is the likeliest reason a new project will not
build. It writes the project anyway: you may be about to install it.

### Bringing an existing project over

To move an application to a new version of the framework, generate it again and bring its pieces across, rather
than upgrading the old code in place. The new project then starts with the framework's current idioms and none of
the old ones. Give the generator the old project's names, so the package, the main class and the paths stay the
same:

```bash
java -cp vexelray-framework-template/target/classes dev.vexelray.framework.template.Generate --in <scratch> --name my-app --group dev.example --className MyApp --title "My App" --icon my-mark
```

Generate into a scratch folder, not over the old checkout. Then, on a new branch of the old repository, delete
every tracked file, copy the generated project in, and carry these over from the old tree:

| Carry over | Why |
| --- | --- |
| `LICENSE`, `NOTICE`, third-party notices | They are the project's, and the template has none |
| `installer.json` | The installer's address does not change with the framework |
| `src/main/rc`: the mark and any other icons | Put the mark at `<icon>.ico`, with its source beside it as `<icon>.svg` (rename the source if it was called something else), so the README's account of the mark is true. Leave out the old `.rc` and `.manifest`: the generated ones replace them |
| `docs/retrospective.md` | Sections are never deleted. The old `framework-notes.md` stays behind. Only the findings you are putting off go into the new notes, marked 📋 |

Everything else stays on the old branch: code, fonts, grammars, art and design notes. Copy each piece across only
when the feature you are rebuilding needs it, and write it again the new way as you do.

## 4. Run it

```bash
cd ../my-app
```

```bash
mvn compile exec:exec
```

<!-- image: screenshot of a freshly generated project, the navigator on the left and a Markdown file open -->

A window opens with a small text editor in it: tabs for open files, a navigator, and Markdown shown in colour. The
navigator starts on the folder the application runs in, which under `mvn exec:exec` is the `my-app-gui` module, so
the README is one folder up: press Ctrl+L, type the path of `my-app` (or of `my-app/README.md` to open it straight
away) and press Enter. Change it, save with Ctrl+S, close the window and run it again; it comes back where you left
it. F1 opens the Examples window.

A text editor is the starting point because it is the smallest real application that touches everything a real one
does: animation, background work, a question before closing with unsaved changes, settings that persist, and a
user interface that tests can build without a window. You will change most of it, and keep its shape.

```bash
mvn test
```

runs the tests that came with it, including one that builds the real window contents with no GPU at all.

## 5. Find your way around

Two modules, under the folder `mvn` runs in: `my-app-core` is the controller, `my-app-gui` the view and everything
about the window. They talk by messages and nothing else. Read these in this order:

| File | What it is |
| --- | --- |
| `CLAUDE.md`, `README.md` | The project's rules and its tour. Short, and written for this project: read both |
| `EXAMPLES.md` | **How to do what an application reaches for**, each question answered by files in this project, with its test and its `ottermate` check |
| `my-app-core/.../core/Messages.java` | **Every message the parts send each other**, each record beside its topic. Start here |
| `my-app-core/.../core/Editor.java` | **The controller**: a `@Component` on the framework's logic lane that makes every decision as a declared step on one immutable state, and owns the session (`Doc.java`). Closing is a chain of steps that waits on the person and the disk |
| `my-app-core/.../core/ReadFile.java` and the other jobs | Every touch of the disk, as a `@Job` on the offload lane: a request in, a reply out |
| `my-app-core/.../core/Core.java` | The list of `-core`'s components and jobs, which is how the wiring hears of them |
| `my-app-gui/.../MyApp.java` | The entry point and a few constants (name, title, size, icon). Nothing else |
| `my-app-gui/.../MyAppApp.java` | The `@VexelApp` declaration: the name, the window, and the starters (`Core`, and the automation sockets, [step 6](#6-build-a-native-executable)) |
| `my-app-gui/.../Recipes.java` | What `-gui` builds: one `@Provides` method per part |
| `my-app-gui/.../View.java` | **The view**: a `@View`, run on the GUI thread in each frame, that shows what the editor decides and tells it what the person did. It decides nothing |
| `my-app-gui/.../Intent.java` | What each key chord asks: the message it was declared to send |
| `my-app-gui/.../Ui.java` | The window's contents. Holds no state |

`-core` builds against no widget, so its tests need no window: your own logic belongs there.

What is **not** there is the point: opening input, the clipboard, remembering where the window was, the clock, the
frame loop, the dialogs, the command line and closing everything in order are the framework's.

After a build, open `my-app-gui/target/generated-sources/annotations/.../MyAppAppWiring.java` (the `@VexelApp`
class's name, plus `Wiring`). It is the code the framework wrote from `Recipes.java`, the components and the jobs,
and it shows exactly which part is built when, and on which lane.

### Adding a part

Most new behaviour is a message: a record in `Messages`, a step (or a `@Subscribe` method) where it arrives, and a
`@Publishes` where it is sent. Something with state of its own and an order to keep is a `@Component`, which runs on
the framework's logic lane; build it like `Editor`, with its state as one immutable record and each message it hears
(`@Hears`) as a step. Name a `lane` only for heavy work that should have a thread of its own. Something that waits (on
the person, on a reply, on time) is a chain of steps, not a thread that sleeps; the `later` example shows time, and
`Editor`'s closing shows a reply. Slow work, the disk or the network, is a `@Job`. A component or job in `-core` goes
on `Core`'s list, or nothing builds it.

A part that is built once and handed to others is a method in `Recipes`. Its parameters are what it needs; the
framework supplies them:

```java
@Provides
WordCount wordCount(Ui ui, Gui gui) {
    return new WordCount(ui, gui);
}
```

There is no ordering to declare. Because this takes a `Gui`, it is built after the `Gui` exists; one that took
neither would be built before it. If you ask for something nothing provides, or two parts each need the other, it is
a compile error with the reason in it, not a crash at startup. [overview.md](overview.md#phases) explains how the
order is worked out.

Return an interface when other code should be able to substitute the part. A class of your own package may be
returned as it is. A recipe that wants every part of a kind takes `All<T>`, and one that can do without a part takes
`Optional<T>` ([components.md](components.md) §13); that is how the Examples window finds its examples.

### Replacing a default

Six of the framework's own parts are overridden the same way: `Appearance` (the look; the generated `Recipes`
already provides one), `Fonts`, `Dialogs` (answer the application's questions yourself, in a test say), `InputBackend`,
`ClipboardBackend` and `LivenessPolicy` (what happens when a lane hangs). When you provide one, the framework's is
never created. The rest of the application edge (window memory, pacing, the command line) is not replaceable yet.

### Settings

A value from the settings file or the command line is a parameter:

```java
@Provides
Highlighter highlighter(@Setting(value = "theme", def = "light") String theme) {
    return new Highlighter(theme);
}
```

and `--theme=dark` on the command line overrides it for one launch.

### Turning it into your application

The editor is a worked example, not a layer your application sits on: the framework needs none of it. So the first
decision is how much of it to keep, and it depends on what you are making.

- **Something that edits or shows files** (a log viewer, a notes app, a viewer for a format of your own): keep the
  shape. The tabs, the navigator, the jobs, the session and the close gate all carry over; replace `Markdown` with
  what your files need, and change the editor's steps one at a time.
- **Anything else** (a timer, a dashboard, a board game, a terminal): take the editor out first, as a step of its own,
  before you add anything. Delete `Editor`, `Doc`, every job, `Markdown`, `TextFile` and their tests in `-core`, and
  `Buffer`, `Workspace` and `Navigator` in `-gui`, and `Messages` and `Intent` until your first message and chord
  bring them back. Empty `Ui` and `View` down to a page with nothing on it, `Landmarks` down to that page, `Core`'s
  list to nothing, and `ViewTest` to the empty window. What is left is the title bar over an empty page,
  which still zooms, still closes and still remembers where its window was: the close gate went with the editor,
  because with nothing unsaved, closing just closes. `mvn test` passes and `ottermate` shows the empty window; commit
  that, then build your first slice on it. MainFrame and Vexplore were rebuilt exactly this way.

Then map the idea onto the four kinds of part. A focus timer, say:

| What it does | What it is |
| --- | --- |
| Knows whether it is running, how long is left, and how many sessions are done today | A `@Component` in `-core`, its state one immutable record and each message a step, like `Editor` |
| Counts down | The framework's `Timer` ticking the component once a second; a single "when this runs out" is a chain's wait instead (the `later` example) |
| Shows the dial, Start and Pause | The `@View` and the `Ui`, the dial perhaps a view of its own ([Building a custom view](developers/custom-view.md)) that moves rather than jumps ([Making it move](developers/motion.md)) |
| Keeps today's count when it is closed | A `@Job` that writes it to the disk, and one that reads it back at `WindowOpened`, like `ReadSession` and `WriteSession` |
| Space starts and pauses | A command in `View.commands`, declared with the message it sends, and a landmark in `Landmarks` for the check that presses it |

Build it one slice at a time, each from the message to the screen with its test and its `ottermate` check. The
[developer guide](developers/index.md) goes on from here, with [a coding agent](developers/with-an-llm.md) or
[by hand](developers/in-vex.md).

## 6. Build a native executable

With a GraalVM 25 JDK as `JAVA_HOME`, on Windows through the generated `native-build.cmd` (`./native-build.cmd` from
Git Bash). It is `mvn` inside a Visual Studio developer environment: native-image needs MSVC's `link.exe` and the
icon step `rc.exe`, and under Git Bash coreutils' own `link.exe` would be found first. Plain `mvn` works only from a
Visual Studio developer prompt.

```bash
./native-build.cmd -Pnative-release package -DskipTests
```

writes `my-app-gui/target/my-app.exe`, the build you ship: no console window.

```bash
./native-build.cmd -Pnative package -DskipTests
```

writes `my-app-gui/target/my-app-debug.exe`, with a console, for testing: it prints the `automation: localhost:<port>` line.

On Linux there is one build, with plain `mvn`:

```bash
mvn -Pnative-linux package -DskipTests
```

writes `my-app-gui/target-linux/my-app`.

Every build carries the automation sockets, remote control and diagnostics, and starts with them off. A person turns
them on from the plug in the title bar, which restarts the application with them open; its lamps show each socket
open or not and anything connected now or earlier. A launch opens them when it asks, with `--automation=<port>`, or
`-Dautomation=<port>`, or, when the launch names no automation setting at all, the environment variable
`VEXELRAY_AUTOMATION=<port>` (`0` for a free port), which an agent's shell sets so every application it starts can
be driven. That includes `mvn compile exec:exec`, whose `automation` property is empty unless you set it. Each run's port is in its discovery file,
`~/.vexelray/automation/<app>-<pid>.auto`, and `ottermate` with no `--port` reads it.
`--automation.available=none` (or `control`, or `diagnostics`; `VEXELRAY_AUTOMATION_AVAILABLE` in the environment)
takes them out of a run altogether, and with neither available the plug is not shown.

The sockets come from one annotation: `my-app-gui/src/main/java/.../MyAppApp.java` declares the app with the automation starter.

The application's icon is `my-app-gui/src/main/rc/my-app.ico`. It starts as the framework's "new app" mark; replace that one
file and the window, the taskbar button and the `.exe` all change together. The generated project's README says how
to render one from an SVG.

## 7. Drive it and take screenshots

`ottermate` drives a running application the way a person does: keys, clicks, reading what is on screen, and
screenshots taken from the application's own GPU device. It is how a VexelRay application is tested end to end. It
lives at `vexelray-gui/vexelray-gui-automation-cli/ottermate` (`ottermate.cmd` from cmd or PowerShell), built by
`vexelray-gui`'s install in step 2, and the examples assume that folder is on your `PATH`. If it says it is not
built, run `mvn -pl vexelray-gui-automation-cli package` in `vexelray-gui`.

```bash
ottermate shot out.png --launch mvn.cmd compile exec:exec -Dautomation=0
```

```bash
ottermate --size 1600x900 --zoom 1.5 shot big.png --launch mvn.cmd compile exec:exec -Dautomation=0
```

The application writes the picture, so a relative path is taken from the folder it runs in: `out.png` lands in
`my-app-gui`. Give an absolute path to put it anywhere else.

Its guide is [ottermate.md](https://github.com/sibarum/vexelray-gui/blob/master/docs/guides/ottermate.md) in
`vexelray-gui`. The names a script may rely on are the ones in your project's `Landmarks.java`. The window
remembers its last size, so a changed default size never shows on a machine that has run the app before: give
`--size` whenever you compare screenshots.

## 8. Logging

The framework sets logging up before your code runs. Running from your checkout, the console shows info and
`target/logs/my-app.log` has debug; a native build logs warnings to the console and info to
`~/.my-app/logs/`. Log through `sibarum.probe.Log`, not `System.out`:

```java
private static final Log LOG = Log.of("my-app.files");

LOG.warn("could not save {}", path, exception);
```

`--log=debug` changes the level for one launch.

## Where next

- **[Developer guide](developers/index.md)**: turning the generated project into your application,
  [with a coding agent](developers/with-an-llm.md) or [by hand in VexelRay IDE](developers/in-vex.md), and
  [shipping it](developers/shipping.md).
- **[overview.md](overview.md)**: how the framework is put together, at a readable altitude.
- **[threading.md](threading.md)**: before you write anything that runs in the background.
- **`docs/framework-notes.md`** in your project: write down anything you had to work around. It is how the framework
  learns what it is missing.
- **[TODO.md](TODO.md)**: what is known to be missing, so you can tell a gap from a mistake.

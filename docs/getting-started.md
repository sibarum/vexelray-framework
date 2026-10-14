# Getting started

This takes you from nothing to a running VexelRay application, then through what was generated and how to grow it
into your own. Allow about half an hour, most of it the first build of the stack.

## 1. What you need

| | |
| --- | --- |
| **Windows 10 or 11** | The engine's Windows backend is the complete one; Linux and macOS are skeletons |
| **A GPU with a Vulkan driver** | Any recent NVIDIA, AMD or Intel driver has one |
| **JDK 25** | A GraalVM 25 JDK if you also want native executables (step 6) |
| **Maven** and **Git** | On `PATH`. Git for Windows also brings the Bash the commands below are written for |
| **A Rust toolchain** | From [rustup.rs](https://rustup.rs). One repository's image decoders are Rust, built from source |
| **Visual Studio Build Tools** | Rust's default Windows toolchain links with them; native-image needs `link.exe` and the icon step `rc.exe` |

## 2. Install the stack

The stack is not on Maven Central yet. Each piece is its own repository, and they are built and installed to your
local Maven repository (`~/.m2`) once, side by side in one folder:

<!-- image: dependency graph of the ten repositories, arrows showing which needs which -->

```bash
mkdir vexelray && cd vexelray
```

```bash
for r in intrabuf atchung supirvast dxgi-bridge kronometer tactroller vexelray imagelib-wrapper vexelray-gui vexelray-framework; do git clone https://github.com/sibarum/$r.git; done
```

Then install them, **in this order**, because each needs the ones before it:

| Repository | What it is |
| --- | --- |
| `intrabuf` | Serialization the message bus carries its envelopes in |
| `atchung` | The typed message bus, and the logger nearly every repository uses |
| `supirvast` | The expression language the engine's shaders are written in |
| `dxgi-bridge` | Presentation on Windows |
| `kronometer` | Time and animation |
| `tactroller` | Input: keyboard, pointer, clipboard |
| `vexelray` | The engine: Vulkan, 2D drawing, text, windowing |
| `imagelib-wrapper` | Image decoding, every format a GUI shows, in Rust behind a small Java API |
| `vexelray-gui` | The widgets, layout and theme |
| `vexelray-framework` | This repository |

`imagelib-wrapper` has one step before its install: its native library is built from source and is not in the
repository.

```bash
(cd imagelib-wrapper && ./native/build.sh)
```

Then all ten:

```bash
for r in intrabuf atchung supirvast dxgi-bridge kronometer tactroller vexelray imagelib-wrapper vexelray-gui vexelray-framework; do (cd $r && mvn install -DskipTests) || break; done
```

`-DskipTests` only makes the first install quicker; drop it to run each repository's suite as you go. When you pull
a newer version of one of them, `mvn install` it again, and then the ones after it in the table.

## 3. Generate a project

From the `vexelray-framework` folder:

```bash
java -cp vexelray-framework-template/target/classes dev.vexelray.framework.template.Generate --in .. --name my-app --group dev.example
```

That writes `../my-app`. `--name` is the folder and the Maven artifactId; the Java package becomes
`dev.example.myapp` and the main class `MyApp` unless you say otherwise. Other options include `--title`,
`--width`, `--height`, `--packageName` and `--className`; `--help` lists them all with their defaults.

Before writing anything, the generator looks for the stack in your local Maven repository and names any piece it
cannot find (`not installed: ...`), since a missing `mvn install` is the likeliest reason a new project will not
build. It writes the project anyway: you may be about to install it.

## 4. Run it

```bash
cd ../my-app
```

```bash
mvn compile exec:exec
```

<!-- image: screenshot of a freshly generated project, the navigator on the left and a Markdown file open -->

A window opens with a small text editor in it: tabs for open files, a navigator over the working directory, and
Markdown shown in colour. Open the README from the navigator (Ctrl+L puts the caret in its path field), change it,
save with Ctrl+S, close the window and run it again; it comes back where you left it.

A text editor is the starting point because it is the smallest real application that touches everything a real one
does: animation, background work, a question before closing with unsaved changes, settings that persist, and a
user interface that tests can build without a window. You will change most of it, and keep its shape.

```bash
mvn test
```

runs the tests that came with it, including one that builds the real window contents with no GPU at all.

## 5. Find your way around

Read these in this order:

| File | What it is |
| --- | --- |
| `MyApp.java` | The entry point and a few constants (name, title, size, icon). Nothing else |
| `MyAppApp.java` | The `@VexelApp` declaration, written twice: see [step 6](#6-build-a-native-executable) |
| `Recipes.java` | **What the application is made of.** One `@Provides` method per part. New parts go here |
| `Model.java`, `Doc.java` | All of the application's state, as one immutable value that changes by being replaced |
| `Ui.java` | The window's contents. Holds no state; it redraws from the model |
| `Actions.java` | Every command, the questions some of them ask, and the close check |
| `Session.java` | What is remembered between runs |
| `text/` | Code with no user interface in it, in public classes, tested in milliseconds. Your own logic belongs in packages like this |

What is **not** there is the point: opening input, the clipboard, remembering where the window was, the clock, the
frame loop, the dialogs, the command line and closing everything in order are the framework's.

After a build, open `target/generated-sources/annotations/.../MyAppAppWiring.java` (the `@VexelApp` class's name,
plus `Wiring`). It is the code the framework wrote from `Recipes.java`, and it shows exactly which part is built
when.

### Adding a part

A part is a method in `Recipes`. Its parameters are what it needs; the framework supplies them:

```java
@Provides
WordCount wordCount(Model model, Gui gui) {
    return new WordCount(model, gui);
}
```

There is no ordering to declare. Because this takes a `Gui`, it is built after the `Gui` exists; one that took only
`Model` would be built before it. If you ask for something nothing provides, or two parts each need the other, it is
a compile error with the reason in it, not a crash at startup. [overview.md](overview.md#phases) explains how the
order is worked out.

Return an interface when other code should be able to substitute the part. A class of your own package may be
returned as it is.

### Replacing a default

Five of the framework's own parts are overridden the same way: `Appearance` (the look; the generated `Recipes`
already provides one), `Dialogs` (answer the application's questions yourself, in a test say), `InputBackend`,
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

## 6. Build a native executable

From a Visual Studio developer prompt, with a GraalVM 25 JDK as `JAVA_HOME`:

```bash
mvn -Pnative-release package -DskipTests
```

writes `target/my-app.exe`, the edition you ship: no console window, and no automation socket linked in.

```bash
mvn -Pnative package -DskipTests
```

writes `target/my-app-debug.exe`, with a console and the automation socket linked in, for testing. The socket only
opens when a launch asks for it, with `--automation=<port>`.

The difference is one annotation, written twice: `src/edition-debug` declares the app with the automation starter
and `src/edition-release` without it. Keep the two identical apart from that.

The application's icon is `src/main/rc/my-app.ico`. It starts as the framework's "new app" mark; replace that one
file and the window, the taskbar button and the `.exe` all change together. The generated project's README says how
to render one from an SVG.

## 7. Drive it and take screenshots

`ottermate` drives a running application the way a person does: keys, clicks, reading what is on screen, and
screenshots taken from the application's own GPU device. It is how a VexelRay application is tested end to end. It
lives at `vexelray-gui/vexelray-gui-automation-cli/ottermate` (`ottermate.cmd` from cmd or PowerShell), and the
examples assume that folder is on your `PATH`:

```bash
ottermate shot out.png --launch mvn.cmd compile exec:exec -Dautomation=0
```

```bash
ottermate --size 1600x900 --zoom 1.5 shot big.png --launch mvn.cmd compile exec:exec -Dautomation=0
```

Its guide is `vexelray-gui/docs/guides/ottermate.md`. The names a script may rely on are the ones in your project's
`Landmarks.java`.

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

- **[Build your own](developers/index.md)**: turning the generated project into your application,
  [with a coding agent](developers/with-an-llm.md) or [by hand in Vex](developers/in-vex.md), and
  [shipping it](developers/shipping.md).
- **[overview.md](overview.md)**: how the framework is put together, at a readable altitude.
- **[threading.md](threading.md)**: before you write anything that runs in the background.
- **`docs/framework-notes.md`** in your project: write down anything you had to work around. It is how the framework
  learns what it is missing.
- **[TODO.md](TODO.md)**: what is known to be missing, so you can tell a gap from a mistake.

# vexelray-framework

An application framework for **GraalVM native-image desktop applications with realtime graphics** —
compile-time dependency injection, typed configuration, and a frame-aware lifecycle for the VexelRay
stack.

Everything Spring Boot does with runtime classpath scanning, reflection and generated proxies, this
does with an annotation processor. An application compiles to a native binary with **no reachability
metadata of the framework's own**, and startup costs what the equivalent hand-written `main` cost —
which on this stack is the whole reason for being on native-image at all.

- **Java 25 · Maven · GraalVM native-image**
- Wires the sibling stack: [vexelray](../vexelray) (Vulkan engine),
  [vexelray-gui](../vexelray-gui) (retained-mode GUI), [tactroller](../tactroller) (input),
  [atchung](../atchung) (bus), [kronometer](../kronometer) (time)
- [docs/architecture.md](docs/architecture.md) is the deep version of this document
- [docs/TODO.md](docs/TODO.md) is what is known about and not done

## The problem

`mainframe-template`'s scaffold — where a new VexelRay application starts — is ~350 lines of wiring,
and its own Javadoc explains why:

> The **application edge** — the part a client of vexelray-gui has to write for itself... Every one of
> those is a decision the framework deliberately does not take on an application's behalf.

Four applications now hold near-identical copies. They have drifted: the flags parse in different
orders, `--capture`'s optional path is read three different ways, and two carry a comment warning the
reader not to open `Settings` twice, because nothing stops them:

> One `Settings` for the whole application, shared rather than opened twice: two instances over the
> same file each hold their own copy of it, so the second one to save would drop whatever the first
> had added.

That is a singleton-scope invariant maintained by vigilance. It is also the clearest one-sentence
argument for a container.

## The target

```java
@VexelApp(name = "text-editor", title = "Text Editor", width = 800, height = 592)
public final class TextEditorApp {

    public static void main(String[] args) {
        VexelApplication.run(new TextEditorWiring(), args);
    }
}
```

Input, clipboard, window memory, the app icon, dialogs, the theme and the zoom range, pacing, wakes
and argument parsing are defaults. Overriding one is a `@Provides` method returning that type; the
framework's stops being generated, and there is no precedence documentation to read.

The close gate is the exception that proves the direction: the framework owns the *place* it is
registered (`Shell.onClose`, from phase `ATTACH`) and installs none of its own, because the default
has to be that closing closes.

```java
@Configuration
final class Editor {

    /**
     * Phase GUI, inferred: it takes a Gui and a clock, so it cannot be built before either exists —
     * and it is therefore buildable before there is a window, which is what makes --capture work.
     */
    @Provides
    Workspace workspace(Gui gui, KronoGui krono, SourceIndex index) {
        return new Workspace(gui, krono, index);
    }

    /** Phase CONFIG: a bound setting and nothing else, so it is built first. */
    @Provides
    Highlighter highlighter(@Setting(value = "theme", def = "editor") String theme) {
        return new Highlighter(theme);
    }

    /**
     * Only in a session. A capture never opens an input backend, so this is not constructed at all
     * on a machine that has none — rather than constructed and then found to be null.
     */
    @Provides
    @OnMode(RunMode.WINDOWED)
    @ConditionalOnType("sibarum.tactroller.clipboard.Clipboard")
    ClipboardBinding clipboards(Workspace workspace) {
        return new ClipboardBinding(workspace.windows());
    }
}
```

## What is built

| Module | State |
| --- | --- |
| `vexelray-framework-api` | **built** — the annotation vocabulary |
| `vexelray-framework-core` | **built** — phases, launch, frame stages, pacing, disposal (37 tests) |
| `vexelray-framework-shell` | **built** — the absorbed edge, and the window chrome (34 tests) |
| `vexelray-framework-automation` | **built** — the driving socket, in its own module |
| `vexelray-framework-processor` | next — generate the wiring the three ports below wrote by hand |

Three applications run on it, each with its wiring hand-written in its own repo. That is what the
processor's output has to reproduce, and they were chosen so that each could find what the others
could not:

| Application | What it proved |
| --- | --- |
| [`calculator-vexel-demo`](../calculator-vexel-demo) | `CalculatorWiring` — one window, a marched viewport, a device-backed component in `WINDOW` |
| [`text-editor-vexel-demo`](../text-editor-vexel-demo) | `TextEditorWiring` — three windows, an OS clipboard on all of them, unsaved documents behind a close gate, an application mark, and its own headless capture. It is what found the four gaps in [docs/architecture.md](docs/architecture.md#what-porting-the-text-editor-found) |
| [`vexelray-designer`](../vexelray-designer) | `DesignerWiring` — two windows on one device and one frame loop, the second one ray-marched by the application into a target the host mints, and the only one of the three that lets the OS draw its frame. See [what it found](docs/architecture.md#what-porting-the-designer-found) |

`-api` and `-core` are JDK-only, so the container's decisions are testable on a machine with no GPU.
`-shell` is the only Vulkan-aware module.

The processor comes **last** on purpose: a code generator whose output has never been written by hand
is a generator whose output nobody has checked the shape of.

## The vocabulary

| Annotation | Does |
| --- | --- |
| `@VexelApp` | the entry class, and the settings-directory name |
| `@Component` | a container-managed singleton; one constructor, phase inferred |
| `@Configuration` / `@Provides` | recipes for types the application does not own |
| `@Default` | the framework's answer *unless the application has one* |
| `@MainThread` | may only be touched on the main thread — checked at compile time |
| `@BeforeFrame(FrameStage)` | per-frame work, in a named stage |
| `@OnMode(RunMode)` | exists only in some run modes |
| `@ConditionalOnType` | contribute only if a type is on the compile classpath |
| `@Setting` | bind one configuration value, by key |

## Building

The siblings install to the local Maven repo first, in dependency order
(see [vexelray-gui/CLAUDE.md](../vexelray-gui/CLAUDE.md)):

```bash
cd ../supirvast && mvn install && cd ../vexelray && mvn install && cd ../tactroller && mvn install && cd ../vexelray-gui && mvn install
```

Then this repo. `-api` and `-core` need none of them:

```bash
mvn test
```

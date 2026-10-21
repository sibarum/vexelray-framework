# VexelRay

<!-- image: hero — the VexelRay wordmark, and beneath it a screenshot strip of the suite's applications -->

**VexelRay is a Java stack for desktop applications that draw everything themselves, on the GPU, and ship as one
native executable.** `vexelray-framework` is the way into it: you write the parts of your application, and the
framework wires the window, the input, the clock, the frame loop, the threads and the shutdown for you.

```java
@VexelApp(name = "notes", title = "Notes", width = 1180, height = 720)
final class NotesApp {
}

@Configuration
final class Recipes {

    @Provides
    Model model() {
        return new Model();
    }

    @Provides
    Ui ui(Gui gui, KronoGui clock, Model model) {   // built once the Gui and its clock exist
        return new Ui(gui, clock, model);
    }
}
```

```java
public static void main(String[] args) {
    VexelApplication.run(new NotesAppWiring(), args);
}
```

`NotesAppWiring` is not something you write. An annotation processor generates it while the project compiles: it
works out what has to exist before what, builds each part once in that order, and refuses at compile time what
would otherwise fail at startup or, worse, silently at runtime.

- **Java 25, Maven, GraalVM native-image.** An application compiles to a native binary with no reflection
  metadata of the framework's own, and starts as fast as a hand-written `main` would.
- **Compile-time wiring, not runtime magic.** Dependency injection, typed settings and conditional parts in the
  spirit of Spring Boot, done with an annotation processor instead of classpath scanning and proxies.
- **Never a frozen window.** Your code runs off the main thread by default. A part that hangs takes only its own
  thread with it; the window keeps painting and can always be closed.
- **Sensible defaults.** Input, clipboard, window memory, dialogs, the theme, pacing and argument parsing are the
  framework's. The theme, input, clipboard, dialogs and the liveness policy can each be replaced with a single
  `@Provides` method; the rest are not replaceable yet.

## Get started

**New here? The [developer guide](https://sibarum.github.io/vexelray-framework/developers/) is the place to start.**
It takes you from an empty folder to a published application, and every other page links from it.

On Windows (from Git Bash) or Linux, with JDK 25, Maven and a Rust toolchain, this clones the stack's other
repositories beside this one and installs all of them to your local Maven repository, in dependency order:

```bash
./bootstrap.sh
```

```bash
java -cp vexelray-framework-template/target/classes dev.vexelray.framework.template.Generate --in .. --name my-app --group dev.example
```

```bash
cd ../my-app && mvn compile exec:exec
```

That opens a working application: a small tabbed text editor with a file navigator, which is the starting point
you change into whatever you are building. **[docs/getting-started.md](docs/getting-started.md)** walks through
it properly: prerequisites, what the generated project contains, adding a part, and building the native
executable.

## How it fits together

<!-- image: stack diagram — the layers below as stacked bands, your application on top -->

```
  your application          @VexelApp, @Configuration recipes, @Component actors
  ─────────────────────────────────────────────────────────────────────────────
  vexelray-framework        generated wiring · phases · frame loop · threads · windows · dialogs
  ─────────────────────────────────────────────────────────────────────────────
  vexelray-gui              retained-mode widgets, layout, theme, the Gui and its frame
  kronometer                time and animation          atchung      the typed message bus
  tactroller                every input device          vexelray     Vulkan engine, text, windowing
  supirvast                 the shaders' language       dxgi-bridge  presentation on Windows
  imagelib-wrapper          image decoding              intrabuf     the bus's serialization
```

Each one is its own repository. The framework sits on top and is the only one an application has to know about
to get going; the others are where the widgets, the drawing and the input come from once you need them directly.

**[docs/overview.md](docs/overview.md)** is the architecture at a readable altitude: what the framework owns, how
a part's place in startup is worked out, what happens in a frame, and which thread runs what.

## Documentation

| Start here | |
| --- | --- |
| [Getting started](docs/getting-started.md) | From nothing to a running application, and the tour of what was generated |
| [Overview](docs/overview.md) | The architecture: the stack, the wiring, phases, the frame, threads |

| Reference | |
| --- | --- |
| [Threading conventions](docs/threading.md) | The rules: lanes, ownership, channels, lifecycle, and what enforces each |
| [The component model](docs/components.md) | What a component is and what the framework owes it |
| [Units of position and size](docs/units.md) | Every position and size number on the stack, and which space it is in |

| Design and status | |
| --- | --- |
| [Architecture](docs/architecture.md) | The deep version, and the decision record: why each choice was made |
| [What v1 means](docs/v1.md) | The bar for v1: an application built on it needs no major refactor afterwards |
| [Readiness](docs/status.html) | The v1 surfaces graded by whether their shape is final (open in a browser) |
| [TODO](docs/TODO.md) | What is known about and not done, split by whether it blocks v1 |

The same documents are published at <https://sibarum.github.io/vexelray-framework/>, which is also the home of the
applications built on the stack and their installer.

## Built with it

| Application | |
| --- | --- |
| [Vexplore](docs/apps/vexplore.md) | A file explorer that suggests instead of asking, and where every action can be undone |
| [Vex](docs/apps/text-editor.md) | A tabbed text editor with a file navigator |
| [Pix](docs/apps/pix.md) | An image viewer |
| [Calculator](docs/apps/calculator.md) | A calculator and plotter |

The fluid and rigid-body simulation demos are built on it too. The applications install together on Windows; see
[the install page](https://sibarum.github.io/).

## Status

**0.1.0 is an experimental prerelease, not v1.** It is tagged so that the applications above could be installed
and tried, not as a version to build on: nothing in it is stable, and the next version may change any of it without
notice. Every module below is built and tested, and the applications run on it daily. What v1 still needs is for
the remaining abstractions to be final, so that an application written against it will not need restructuring later; [docs/v1.md](docs/v1.md) is that bar and [docs/status.html](docs/status.html)
is where each surface stands.

Windows is the complete platform. The engine's Linux and macOS backends are skeletons.

| Module | What it is |
| --- | --- |
| `vexelray-framework-api` | The annotations an application writes. JDK-only |
| `vexelray-framework-core` | Phases, launch, frame stages, pacing, disposal, threads and the watchdog. JDK-only, testable with no GPU |
| `vexelray-framework-processor` | The annotation processor: compile-time checks, then the generated wiring |
| `vexelray-framework-shell` | The application edge: input, clipboard, window memory, the frame loop, windows, chrome, dialogs |
| `vexelray-framework-automation` | The automation sockets (remote control and diagnostics), in every build and off until asked for |
| `vexelray-framework-template` | The project generator, and the reference application it writes |
| `vexelray-framework-acceptance` | Under `-Pacceptance`: generates a project, builds it, opens it and drives it |

## Working on the framework

`-api`, `-core`, `-processor` and `-template` need nothing else installed:

```bash
mvn test -pl vexelray-framework-api,vexelray-framework-core,vexelray-framework-processor,vexelray-framework-template
```

A bare `mvn test` goes on to `-shell` and stops there without the stack. Everything else needs the sibling
repositories installed first: `./bootstrap.sh` (and `./bootstrap.sh --from <repo>` after pulling one). The acceptance run then generates a
project, builds it, and drives a real window for a few seconds; its logs and screenshots land in
`vexelray-framework-acceptance/target/acceptance/`:

```bash
mvn clean install -Pacceptance
```

[CLAUDE.md](CLAUDE.md) holds the constraints on this repository's own code (nothing reflects, nothing allocates in
the frame, `-api` and `-core` stay JDK-only) and where each comes from.

The applications are published from [vexelray-dist](https://github.com/sibarum/vexelray-dist): its installers, their
manifests and the binaries' releases. How a release goes out is its
[DEPLOYING.md](https://github.com/sibarum/vexelray-dist/blob/main/DEPLOYING.md). The install page,
[docs/install/index.md](docs/install/index.md), is written by hand and only changes when the list of applications
or the install command does.

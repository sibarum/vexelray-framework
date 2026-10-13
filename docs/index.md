# VexelRay

Desktop applications built on the VexelRay stack, and the framework they are built with.

## The applications

| | |
| --- | --- |
| [**Vexplore**](apps/vexplore.md) | A file explorer that suggests instead of asking, and where every action can be undone |
| [**Vex**](apps/text-editor.md) | A tabbed text editor with a file navigator, which comes back where you left it |
| [**Pix**](apps/pix.md) | An image viewer: one picture, as large as the window allows, and the rest of its folder a key press away |
| [**MainFrame**](apps/mainframe.md) | A terminal window for the shell you already have |
| [**Calculator**](apps/calculator.md) | An ordinary-looking calculator that divides by zero |
| [**Fluid Sim**](https://github.com/sibarum/vexelray-sim-fluid) | Fluid simulation experiments: dam breaks, mixing and heat, live on the GPU |
| [**Rigid Sim**](https://github.com/sibarum/vexelray-sim-rigid) | Spheres that collide, bounce, spin and roll, live on the GPU |

They are separate programs that know about each other: a text file in Vexplore opens in Vex and a picture in
Pix, and Vex can show any file in Vexplore.

**[Install them](install/index.md)** with one line of PowerShell, on Windows, for your user only. This is 0.1.0, the
first release:

```powershell
irm https://raw.githubusercontent.com/sibarum/vexelray-dist/main/install.ps1 | iex
```

## The framework

`vexelray-framework` is an application framework for **GraalVM native-image desktop applications with
realtime graphics**: compile-time dependency injection, typed configuration, and a frame-aware lifecycle.
Everything Spring Boot does with runtime classpath scanning, reflection and generated proxies, it does with
an annotation processor. An application compiles to a native binary with no reachability metadata of the
framework's own, and starts up as fast as the equivalent hand-written `main`.

```java
@VexelApp(name = "text-editor", title = "Vex", width = 800, height = 592,
          icon = "/editor.ico", starters = AutomationStarter.class)
final class TextEditorApp {

    public static void main(String[] args) {
        VexelApplication.run(new TextEditorAppWiring(), args);
    }
}
```

`TextEditorAppWiring` is generated from the annotation while the application compiles; nobody writes it.

The framework is before v1. [The source and the tour are on GitHub](https://github.com/sibarum/vexelray-framework).

| Document | What it is |
| --- | --- |
| [Getting started](getting-started.md) | From nothing to a running application, and a tour of what was generated |
| [Overview](overview.md) | The architecture at a readable altitude: the stack, the wiring, phases, the frame, threads |
| [What v1 means](v1.md) | The bar: an application built on v1 needs no major refactor afterwards |
| [Readiness](status.html) | The v1 surfaces graded by whether their shape is final, and where each capability lives |
| [Architecture](architecture.md) | The deep version, and the decision record |
| [Threading conventions](threading.md) | Lanes, ownership, channels and lifecycle, each with whether anything enforces it yet |
| [The component model](components.md) | What a component is and what the framework owes it |
| [Units of position and size](units.md) | An audit of every position and size number on the stack |
| [TODO](TODO.md) | What is known about and not done, split by whether it blocks v1 |

The framework wires sibling repositories:
[vexelray](https://github.com/sibarum/vexelray) (the Vulkan engine),
[vexelray-gui](https://github.com/sibarum/vexelray-gui) (retained-mode GUI),
[tactroller](https://github.com/sibarum/tactroller) (input),
[atchung](https://github.com/sibarum/atchung) (the typed bus) and
[kronometer](https://github.com/sibarum/kronometer) (time).

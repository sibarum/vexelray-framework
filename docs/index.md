# VexelRay

Desktop applications built on the VexelRay stack, and the framework they are built with.

## The applications

| | |
| --- | --- |
| [**Vexplore**](apps/vexplore.md) | A file explorer that suggests instead of asking, and where every action can be undone |
| [**Text Editor**](apps/text-editor.md) | A tabbed text editor with a file navigator, which comes back where you left it |
| [**MainFrame**](apps/mainframe.md) | A terminal window for the shell you already have |

They are separate programs that know about each other: a text file in Vexplore opens in the editor, and the
editor can show any file in Vexplore.

**[Install them](install/)** with one line of PowerShell, on Windows, for your user only. None of them has been
released yet.

## The framework

`vexelray-framework` is an application framework for **GraalVM native-image desktop applications with
realtime graphics**: compile-time dependency injection, typed configuration, and a frame-aware lifecycle.
Everything Spring Boot does with runtime classpath scanning, reflection and generated proxies, it does with
an annotation processor. An application compiles to a native binary with no reachability metadata of the
framework's own, and starts up as fast as the equivalent hand-written `main`.

```java
@VexelApp(name = "text-editor", title = "Text Editor", width = 800, height = 592)
public final class TextEditorApp {

    public static void main(String[] args) {
        VexelApplication.run(new TextEditorWiring(), args);
    }
}
```

The framework is before v1. [The source and the tour are on GitHub](https://github.com/sibarum/vexelray-framework).

| Document | What it is |
| --- | --- |
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

# vexelray-framework

An application framework for **GraalVM native-image desktop applications with realtime graphics**:
compile-time dependency injection, typed configuration, and a frame-aware lifecycle for the VexelRay
stack.

Everything Spring Boot does with runtime classpath scanning, reflection and generated proxies, this does
with an annotation processor. An application compiles to a native binary with no reachability metadata of
the framework's own, and startup costs what the equivalent hand-written `main` cost.

```java
@VexelApp(name = "text-editor", title = "Text Editor", width = 800, height = 592)
public final class TextEditorApp {

    public static void main(String[] args) {
        VexelApplication.run(new TextEditorWiring(), args);
    }
}
```

The framework is before v1. [The source and the tour are on GitHub](https://github.com/sibarum/vexelray-framework).

## The documents

| Document | What it is |
| --- | --- |
| [What v1 means](v1.md) | The bar: an application built on v1 needs no major refactor afterwards |
| [Readiness](status.html) | The v1 surfaces graded by whether their shape is final, and where each capability lives |
| [Architecture](architecture.md) | The deep version, and the decision record |
| [Threading conventions](threading.md) | Lanes, ownership, channels and lifecycle, each with whether anything enforces it yet |
| [The component model](components.md) | What a component is and what the framework owes it |
| [Units of position and size](units.md) | An audit of every position and size number on the stack |
| [TODO](TODO.md) | What is known about and not done, split by whether it blocks v1 |

## The stack

The framework sits on top of sibling repositories, and wires them:
[vexelray](https://github.com/sibarum/vexelray) (the Vulkan engine),
[vexelray-gui](https://github.com/sibarum/vexelray-gui) (retained-mode GUI),
[tactroller](https://github.com/sibarum/tactroller) (input),
[atchung](https://github.com/sibarum/atchung) (the typed bus) and
[kronometer](https://github.com/sibarum/kronometer) (time).

# Working in this repo

`vexelray-framework` is a Spring-Boot-shaped application framework for the VexelRay stack: compile-time
DI, typed configuration, and a frame-aware lifecycle for GraalVM native-image desktop applications.
[README.md](README.md) is the tour; [docs/architecture.md](docs/architecture.md) is the deep version and
the decision record. [docs/TODO.md](docs/TODO.md) is what is known about and not done, which is
where anything you notice and do not fix belongs.

It sits at the **top** of a stack of sibling checkouts under `C:\Users\User\Documents\GitHub\`. As in
`vexelray-gui`, most of what is confusing is not in this repo.

## The siblings

All of these are the author's own code and are **modifiable**. `sibarum.*` reads like a third-party
groupId but is theirs. A cross-repo change needs the dependency `mvn install`ed to the local `.m2`
before this repo sees it.

| Repo | What it holds |
| --- | --- |
| **vexelray-gui** | What this framework wires: `Gui`, `GuiApp` (the frame loop), `Settings`, `AppHome`, `WindowMemory`. Read its `CLAUDE.md` first — its constraints are this framework's requirements |
| **vexelray** | The engine: Vulkan runtime, `Canvas`, MSDF text, windowing |
| **tactroller** | Input. **Every** device event flows through it; there is no side channel |
| **atchung** | The typed bus. Also `elektroq` — **the house precedent for this repo's mechanism**: annotate a record, a processor emits code, native-image needs no `reflect-config.json` |
| **kronometer** | Timing and animation, wrapped as `vexelray-gui-krono` |
| **mainframe** | A terminal/shell, and `mainframe-template` — **the project builder, which is moving into this repo and becoming the primary witness**. Its `vexel-desktop` template is already framework-shaped, and its engine (`Scaffold`, `Blueprint`, `Template`, `Manifest`) is pure JDK with no mainframe imports; only `…template.shell` binds to the shell. **Read the template's `files/App.java` and `files/Wiring.java`** — they are what a correct application looks like. mainframe is also a library importing `WindowMemory` and `Settings`, so it is inside the absorption blast radius |
| **calculator-vexel-demo**, **text-editor-vexel-demo** | **Deliberately emptied — do not restore them.** They were built to find out what the framework needed, and what they found is written up in [architecture.md](docs/architecture.md)'s two *what porting … found* sections. Keeping them ported cost a tax on every framework change, in three separate house styles. A generated project is the witness now |
| **vexelray-designer** | No longer tracked. Still on disk, still a `Wiring`, and useful to read — but it is not the reference for anything and nothing here should be shaped to keep it compiling |

## Where the design comes from

Nearly every decision here is a constraint that already existed in the stack as prose. When adding a
feature, find the comment it derives from — if there isn't one, be suspicious that the feature is
invented rather than needed. The load-bearing ones:

- **`Phase`** — construction-order constraints from `mainframe-template`'s scaffold comments (theme
  before widgets, clock before widgets, handle only after the window).
- **`MainThread`** — `vexelray-gui/CLAUDE.md`, under *constraints that are not visible in the code*:
  "Vulkan, the window and present stay on the main thread."
- **`FrameStage`** — the `pump / tick / poll` order, and the documented reason for it.
- **`DeadlineSource` / `WakeSource`** — the hand-written `pacing` expression and the two `onWork`
  calls, whose omission is a parked loop rather than an exception.
- **`CONFIG` phase** — the "two `Settings` instances would fight" comment, which appears in two repos.

## Constraints on this repo specifically

- **`-api` and `-core` stay JDK-only.** Not for portability — it is where the dependency edges fall,
  and it is what makes the container testable with no GPU. A graphics import in either is a layering
  break, not a convenience. Vulkan-aware code goes in `-shell`.
- **Nothing reflects.** No `Class.forName`, no `MethodHandles` on the startup path, no runtime
  annotation reads. Retention is `CLASS`, deliberately, and `package-info.java` in `-api` explains why
  it is neither `RUNTIME` nor `SOURCE`.
- **No allocation in `FrameHooks.run` or `Pacing.nanosUntilNextFrame`.** These are the only framework
  code inside the frame budget. No iterators, no boxing, no lambdas created per call.
- **A compile error beats a startup error beats a runtime error**, in that order, always. The whole
  reason the mechanism is a processor is to move failures leftward.
- **Follow `vexelray-gui`'s style rules** — they apply here too. No sealed `switch`, no throwing
  `default`; invert to a method or a sink.

## Building

`-api` and `-core` need no siblings installed:

```bash
mvn test
```

The siblings, in dependency order, once `-shell` exists:

```bash
cd ../supirvast && mvn install && cd ../vexelray && mvn install && cd ../tactroller && mvn install && cd ../vexelray-gui && mvn install
```

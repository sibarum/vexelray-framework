# Worked examples

How to do what an application reaches for, worked out in this project and kept compiling and tested with it. **Look
here before inventing a way.** Each row is a question, the files that answer it, the test that checks it, and the
`ottermate` script that drives it (run from this folder):

```
ottermate --script checks/<name>.txt --launch mvn.cmd compile exec:exec -Dautomation=0
```

The examples run in their own window: **F1** opens it (rebindable, from the title bar's key button). The editor is an
example too, of everything it does; its rows point into it.

## The examples

| Question | Files | Test | Check |
| --- | --- | --- | --- |
<!-- part: rows -->
| A second window, opened by a chord and remembered | `-gui` `examples/` (`Examples`, `ExamplesWindow`, `ExamplesView`) | `ExamplesTest` | any example's check opens it |

## Shown by the editor

| Question | Where |
| --- | --- |
| Add a decision | a step on `Editor`'s ledger (`-core` `Editor.java`) |
| Run something slow and get the answer back | a `@Job`: `ReadFile`, `WriteFile`; what can go wrong is a field of the reply |
| Wait on the person or the disk in the middle of a decision | closing, a chain in `Editor` (`closing`), tested in `EditorTest` |
| Show what the controller decides; tell it what the person did | `View`, and the chords in `Intent` |
| A text field that stays responsive at every keystroke | `Buffer` |
| A tree | `Navigator` |
| The session comes back | `ReadSession`, `WriteSession` |
| Refuse to close while there is unsaved work | the close gate: `Editor`'s quit chain |
| The look: tokens, light and dark | `Look`, `Type` |
| Test a step, a job, the view headless | `EditorTest`, `FilesTest`, `ViewTest` |

## Taking them out

Each example lives in its own folder in each module and is named nowhere else: its `@Configuration` adds its part and
its piece of the Examples window. To take one out, delete its two `examples/<name>/` folders, `checks/<name>.txt`, its
window test in `-gui`'s top test package (`<Name>WindowTest`, which sits beside the generated wiring because the
wiring is package-private) and its row above. To take them all out, delete both `examples/` folders, every
`*WindowTest` and `ExamplesTest`, `checks/` and this file. If anything was left behind, the build says so. A project
made without one never had it: the template asks which to include.

# Looking back at a milestone

**At the end of every milestone, read the milestone back and write down what it shows.** Your project's
`docs/framework-notes.md` holds the findings one at a time, written when each workaround was written. A retrospective
is the pass over them that looks for *shapes* rather than instances: what several findings have in common, what
single change upstream would have stopped them, what went wrong in how you worked, and what carried the project and
should not be touched. It goes in your project's `docs/retrospective.md`, and the framework takes its proposals from
there.

This page says when to do one, what it covers, and how it reaches the framework. The decision to make it a standing
step was made on 2026-10-22. The details below are **accepted as recommended** (in [TODO.md](../TODO.md)'s sense), and
each one gives its reason.

## Why it is a step, not a habit

Two applications have had one. Both times the pass found more than the notes held, and both times most of it was
lost:

- **The calculator's** (September 2026) read twenty-six findings across four milestones and found four shapes. One
  of them was five findings that each produced a plausible picture while silently doing less than asked. It also
  found three mistakes of its author's own, and they turned into a rule. The `vexelray-gui` plan that came out of
  it, `docs/plans/loudness.md`, is still the stack's best piece of work driven by an application. The document
  itself was deleted when the calculator was rebuilt, and now survives only in that plan's quotations and in the
  calculator's git history (`c698c25`).
- **The scheduler's** (October 2026) was done in a conversation and never written down. What survives is what it
  caused: [Building a custom view](custom-view.md), [Making it move](motion.md), and three entries in
  [TODO.md](../TODO.md) dated 2026-10-21. Its reasoning, and anything it found that did not become one of those, is
  gone.

So the step has two rules that a habit would not keep: it happens at a fixed point, and it is written in a file
that outlives the code.

## When

| | When | Why |
| --- | --- | --- |
| **Always** | At the end of each milestone, before the next one starts | One finding is an instance; a shape needs several, and a milestone is the first point where there are several. Leave it later and whoever did the work no longer remembers the cost of each finding |
| **Always** | Before a rebuild, an archive, or a "starting over" | It is the calculator's lesson. The rebuild is the moment the notes are thrown away, so it is the last moment to read them |
| **Worth it** | When `framework-notes.md` has gained five or more findings since the last one | It is cheap at that size, and shapes start to show |
| **Not needed** | A milestone with no new findings | Write one line under its date saying so. That is a result too: the framework carried a milestone without a gap |

What counts as a milestone is the application's own: whatever it calls done enough to show someone. Whoever built
the milestone writes its retrospective, a coding agent included, because only the builder can say what each
finding cost and which mistakes were their own.

## What it covers

Read back over `docs/framework-notes.md` since the last retrospective, the milestone's git log (reverts, a change
made twice, a commit that undoes an earlier one), and the application's `docs/TODO.md`. Then write five sections.
The calculator's retrospective is the model for every one of them.

**1. The count, in one line.** How many findings, over which milestone, and how many of them were architectural.
The answer is usually *none*, and it is worth saying, because it tells the framework to fix the surface and keep
the seams.

**2. The shapes.** Group findings that share a cause, in a table: the promise, what happened, and *how it looked*.
The last column is the one that matters. A finding that throws costs minutes. A finding that produces a plausible
picture costs an afternoon, and that difference is what decides which shape gets fixed first. A finding that fits
no shape is left in the notes, because the retrospective is not a second copy of them.

**3. What would stop each shape.** One structural change per shape, not one fix per finding. Prefer a change that
moves the failure leftward (a compile error before a startup error before a runtime one) or one that makes the
rule into an object, so that nobody has to remember it. *"Any value the framework serialises into generated code
should have a framework-side inverse"* is a proposal. *"Add `project()` to `SdfComposer`"* is one fix inside it.
Number the proposals so that a plan can cite them.

**4. Your own mistakes.** The ones made while building, by you or the agent, in the same form as the framework's:
what was trusted, what it turned out to be, and the rule that covers them. This is the section most worth writing
and the easiest to leave out. The calculator's three mistakes (a performance sweep whose flags never reached the
JVM, a conclusion drawn from a trace that had never compiled in, a script that toggled a panel twice) became the
rule *move one knob to an absurd value and confirm the number moves*. It is now built into `vexelray`'s smoke tests
(`Smoke` runs each one twice, once with a knob absurd, and fails if the numbers agree), and it would not have existed if this section had been skipped.

**5. What to keep exactly as it is.** Name what carried the project, and say why. It is the one section that
tells the framework what *not* to change, and without it a retrospective reads as a list of complaints, which it
usually is not.

Keep it to a page or two. A retrospective that is longer than the notes it read has turned back into a backlog.

## Where it goes

- **In the application:** `docs/retrospective.md`, with one section per milestone, newest first, each headed with
  the milestone and its date. Sections are never deleted, and a rebuild carries the file over with the notes it
  cites (or moves it to the framework, below). A generated project starts with this file, holding the format and
  no entries.
- **Into the framework:** each numbered proposal becomes an entry in [TODO.md](../TODO.md), under *Found by
  \<application\>*, that links back to the section it came from. A proposal whose fix belongs in a sibling becomes
  that sibling's plan, as `loudness.md` did, and the TODO entry points there. The proposals are recommendations,
  not rulings, until someone weighs them.
- **When the shapes say something about the design:** if the retrospective concludes that a seam is right or wrong
  (not only that something is missing), that conclusion goes in [architecture.md](../architecture.md), as a *what
  \<application\> found* section. TODO entries are deleted when they are done, and architecture.md is where the
  reason survives.

An application outside the stack, which cannot commit here, opens
[an issue](https://github.com/sibarum/vexelray-framework/issues/new) that links to its `docs/retrospective.md`, and the
framework side does the intake from there.

## With a coding agent

Ask for it as its own request, after the milestone is committed and before the next one is described:

> The milestone is done. Write its retrospective in `docs/retrospective.md`, following the framework's
> `docs/developers/retrospective.md`: the shapes in `docs/framework-notes.md` since the last one, what would stop
> each, your own mistakes this milestone, and what to keep.

Do it in the same session that built the milestone, while the conversation still holds what each finding cost and
where the agent went wrong. A fresh session can read the notes, but it cannot remember the afternoon. Then read
section 4 yourself: an agent tends to leave out its own mistakes, or to describe them as the framework's.

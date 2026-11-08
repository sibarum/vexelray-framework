package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;

import java.util.List;
import java.util.function.Consumer;

/**
 * What the user is shown when an undo or a redo cannot happen, installed on every window the framework builds
 * (docs/plans/undo.md §2, in {@code vexelray-gui}).
 *
 * <ul>
 *   <li><b>Refused, with a target</b> — the control is disabled, or the change declined. The user has already been
 *       taken to it, so the answer is on the control itself: the refusal ring, pulsed twice. A prompt would cover
 *       the very thing they were just shown.</li>
 *   <li><b>Unreachable, a conflict, or a refusal with nothing to point at</b> — a prompt, asked as any part asks
 *       ({@link OwnAsks}), so whichever dialogs answer the application show it. There is either
 *       no control on screen to mark, or the reason (something changed since) is not one a flash can say.</li>
 *   <li>Everything else — applied, revealing, nothing to do — shows nothing here: the change, or the arrival, is
 *       what the user sees.</li>
 * </ul>
 *
 * <p>An application that wants another look replaces it with {@code gui.onUndo(...)}; there is one sink per tree.
 */
final class UndoFeedback implements Consumer<UndoEvent> {

    /** How long the refusal ring takes: two pulses, each long enough to read as insistence rather than a flicker. */
    private static final Timing RING = Timing.millis(520, Timing.Curve.LINEAR);

    private final Gui gui;
    /** The shell's, so the prompt's id is the framework's and no part takes it for its own question's answer. */
    private final OwnAsks asks;
    /** Built on the first refusal, and timed on the tree's clock, which the window's motion installs. Main thread. */
    private Cues cues;

    UndoFeedback(Gui gui, OwnAsks asks) {
        this.gui = gui;
        this.asks = asks;
    }

    /** Install on {@code gui}, asking as the framework. Told on the main thread, in the frame. */
    static void install(Gui gui, Shell shell) {
        Seams.of(gui).onUndo(new UndoFeedback(gui, shell.ownAsks()));
    }

    @Override
    public void accept(UndoEvent e) {
        Outcome outcome = e.outcome();
        if (!outcome.failed()) {
            return;
        }
        if (outcome == Outcome.REFUSED && e.target() != null) {
            cues().play(e.target(), Cue.ring(gui.theme().color(Role.DANGER)));
            return;
        }
        asks.ask(e.redo() ? "Can't redo" : "Can't undo", message(outcome, e.redo()), List.of("OK"));
    }

    private Cues cues() {
        if (cues == null) {
            cues = new Cues(gui, RING);
        }
        return cues;
    }

    /** Why, in the user's terms: what happened to the thing, never the name of a stack or an outcome. */
    static String message(Outcome outcome, boolean redo) {
        String verb = redo ? "redone" : "undone";
        if (outcome == Outcome.UNREACHABLE) {
            return "What this step changed is no longer here, so it can't be " + verb + ".";
        }
        if (outcome == Outcome.CONFLICT) {
            return "This has been changed again since, so it can't be " + verb
                    + " without overwriting the newer change.";
        }
        return "This can't be changed right now, so it can't be " + verb + ".";
    }
}

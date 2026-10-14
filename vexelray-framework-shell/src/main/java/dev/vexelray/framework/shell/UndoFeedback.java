package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the user is shown when an undo or a redo cannot happen, installed on every window the framework builds
 * (docs/plans/undo.md §2, in {@code vexelray-gui}).
 *
 * <ul>
 *   <li><b>Refused, with a target</b> — the control is disabled, or the change declined. The user has already been
 *       taken to it, so the answer is on the control itself: the refusal ring, pulsed twice. A prompt would cover
 *       the very thing they were just shown.</li>
 *   <li><b>Unreachable, a conflict, or a refusal with nothing to point at</b> — an error prompt. There is either
 *       no control on screen to mark, or the reason (something changed since) is not one a flash can say.</li>
 *   <li>Everything else — applied, revealing, nothing to do — shows nothing here: the change, or the arrival, is
 *       what the user sees.</li>
 * </ul>
 *
 * <p>An application that wants another look replaces it with {@code gui.onUndo(...)}; there is one sink per tree.
 */
final class UndoFeedback implements Consumer<UndoEvent> {

    /** How long the refusal ring takes: two pulses, each long enough to read as insistence rather than a flicker. */
    private static final Dur RING = Dur.ms(520);

    private final Gui gui;
    private final Supplier<Dialogs> dialogs;
    private final Supplier<KronoGui> clock;
    /** Built on the first refusal: the clock does not exist yet when the window's tree is declared. */
    private volatile Cues cues;

    UndoFeedback(Gui gui, Supplier<Dialogs> dialogs, Supplier<KronoGui> clock) {
        this.gui = gui;
        this.dialogs = dialogs;
        this.clock = clock;
    }

    /** Install on {@code gui}, asking {@code shell}'s dialogs and timing the ring by its clock. */
    static void install(Gui gui, Shell shell) {
        gui.onUndo(new UndoFeedback(gui, shell::dialogs, shell::krono));
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
        dialogs.get().info(e.redo() ? "Can't redo" : "Can't undo", message(outcome, e.redo()));
    }

    private Cues cues() {
        Cues c = cues;
        if (c == null) {
            KronoGui krono = clock.get();
            c = new Cues((progress, done) -> krono.ramp(RING, Ease.LINEAR, progress, done));
            cues = c;   // a race builds two, each equally good; one is kept
        }
        return c;
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

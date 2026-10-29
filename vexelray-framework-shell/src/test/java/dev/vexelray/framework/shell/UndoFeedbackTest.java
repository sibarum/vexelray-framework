package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Modal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a window shows when an undo cannot happen (docs/plans/undo.md §2): a refused control is marked where it
 * stands, everything else that failed is a prompt, and nothing that succeeded shows anything at all.
 */
class UndoFeedbackTest {

    private final Gui gui = new Gui(Atchung.create());
    private final Recording dialogs = new Recording();
    private final KronoGui krono = KronoGui.attach(gui);
    private final UndoFeedback feedback = new UndoFeedback(gui, () -> dialogs);
    private final History history = new History();

    @AfterEach
    void close() {
        krono.close();
        gui.close();
    }

    private static final class Recording implements Dialogs {

        final List<Modal> asked = new ArrayList<>();

        @Override
        public void ask(Modal modal) {
            asked.add(modal);
        }

        @Override
        public void pick(dev.vexelray.framework.core.PickFile request,
                         sibarum.atchung.Sender<dev.vexelray.framework.core.Picked> answer) {
        }
    }

    @Test
    void aRefusedControlIsMarkedWhereItStandsWithNoPrompt() {
        Node field = gui.box();
        gui.root().children(field);
        feedback.accept(new UndoEvent(history, false, Outcome.REFUSED, field));
        assertTrue(dialogs.asked.isEmpty(), "the user was just taken to it; a prompt would cover it");
    }

    @Test
    void whatCannotBePointedAtIsAPrompt() {
        feedback.accept(new UndoEvent(history, false, Outcome.UNREACHABLE, null));
        feedback.accept(new UndoEvent(history, true, Outcome.CONFLICT, gui.box()));
        feedback.accept(new UndoEvent(history, false, Outcome.REFUSED, null));
        assertEquals(3, dialogs.asked.size());
        assertEquals("Can't undo", dialogs.asked.get(0).title());
        assertTrue(dialogs.asked.get(0).message().contains("no longer here"), dialogs.asked.get(0).message());
        assertEquals("Can't redo", dialogs.asked.get(1).title());
        assertTrue(dialogs.asked.get(1).message().contains("changed again since"), dialogs.asked.get(1).message());
    }

    @Test
    void successShowsNothing() {
        for (Outcome o : List.of(Outcome.APPLIED, Outcome.REVEALING, Outcome.EMPTY)) {
            feedback.accept(new UndoEvent(history, false, o, null));
        }
        assertTrue(dialogs.asked.isEmpty());
    }
}

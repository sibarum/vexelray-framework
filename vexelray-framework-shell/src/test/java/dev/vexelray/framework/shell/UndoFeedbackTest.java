package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Ask;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.krono.KronoGui;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a window shows when an undo cannot happen (docs/plans/undo.md §2): a refused control is marked where it
 * stands, everything else that failed is asked as an {@link Ask}, and nothing that succeeded shows anything at all.
 */
class UndoFeedbackTest {

    private final Atchung bus = Atchung.create();
    private final Gui gui = new Gui(bus);
    private final List<Ask> asked = new ArrayList<>();
    private final KronoGui krono = KronoGui.attach(gui);
    private final UndoFeedback feedback = new UndoFeedback(gui, new OwnAsks(bus));
    private final History history = new History();

    {
        bus.subscribe(Ask.TOPIC, asked::add);
    }

    @AfterEach
    void close() {
        krono.close();
        gui.close();
    }

    @Test
    void aRefusedControlIsMarkedWhereItStandsWithNoPrompt() {
        Node field = gui.box();
        gui.root().children(field);
        feedback.accept(new UndoEvent(history, false, Outcome.REFUSED, field));
        assertTrue(asked.isEmpty(), "the user was just taken to it; a prompt would cover it");
    }

    @Test
    void whatCannotBePointedAtIsAPrompt() {
        feedback.accept(new UndoEvent(history, false, Outcome.UNREACHABLE, null));
        feedback.accept(new UndoEvent(history, true, Outcome.CONFLICT, gui.box()));
        feedback.accept(new UndoEvent(history, false, Outcome.REFUSED, null));
        assertEquals(3, asked.size());
        assertEquals("Can't undo", asked.get(0).title());
        assertTrue(asked.get(0).message().contains("no longer here"), asked.get(0).message());
        assertEquals("Can't redo", asked.get(1).title());
        assertTrue(asked.get(1).message().contains("changed again since"), asked.get(1).message());
        assertTrue(asked.stream().allMatch(a -> a.id() < 0 && a.buttons().equals(List.of("OK"))),
                "an acknowledgement, under the framework's own ids: " + asked);
    }

    @Test
    void successShowsNothing() {
        for (Outcome o : List.of(Outcome.APPLIED, Outcome.REVEALING, Outcome.EMPTY)) {
            feedback.accept(new UndoEvent(history, false, o, null));
        }
        assertTrue(asked.isEmpty());
    }
}

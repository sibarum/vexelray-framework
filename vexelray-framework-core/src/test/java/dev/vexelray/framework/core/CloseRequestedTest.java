package dev.vexelray.framework.core;

import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Tick;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PhaseArray FN-7: a close request on the bus is addressed to a part. One addressed to none, the form a test hands
 * straight to an inbox, is refused as it is published, naming {@code to} and the form to use, rather than failing on
 * the bus as an NPE that names nothing.
 */
class CloseRequestedTest {

    @Test
    void aRequestAddressedToNoPartIsRefusedOnTheBusNamingTo() {
        Atchung bus = Atchung.create();
        List<CloseRequested> heard = new ArrayList<>();
        // As the wiring hands a part its requests: keyed by the part, which is where the key is read on a publish.
        Tick tick = bus.tick(null);
        tick.inbox("editor").subscribe(CloseRequested.TOPIC, heard::add, 4, Set.of("app.Editor"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bus.publish(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 1L)));
        assertTrue(e.getMessage().contains("to is null") && e.getMessage().contains("new CloseRequested(window, ticket, "),
                e.getMessage());

        CloseRequested addressed = new CloseRequested(CloseRequested.MAIN, 2L, "app.Editor");
        bus.publish(CloseRequested.TOPIC, addressed);
        tick.run(System.nanoTime() + 60_000_000_000L);
        assertEquals(List.of(addressed), heard);
    }
}

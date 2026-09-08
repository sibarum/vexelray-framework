package dev.vexelray.framework.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reverse order, and every close attempted — the two properties a leaked native handle depends on. */
final class DisposerTest {

    private static AutoCloseable logging(List<String> log, String name) {
        return () -> log.add(name);
    }

    @Test
    void closesInReverseConstructionOrder() {
        List<String> log = new ArrayList<>();
        try (Disposer disposer = new Disposer()) {
            disposer.register(logging(log, "device"));
            disposer.register(logging(log, "window"));
            disposer.register(logging(log, "input"));
        }
        // The device outlives what was made with it, so it closes last.
        assertEquals(List.of("input", "window", "device"), log);
    }

    @Test
    void registerReturnsItsArgumentSoItCanWrapAConstruction() {
        Disposer disposer = new Disposer();
        AutoCloseable resource = logging(new ArrayList<>(), "x");
        assertTrue(resource == disposer.register(resource));
    }

    /** An absent optional backend is {@code null} on this stack, and registering one must not need a guard. */
    @Test
    void nullIsAcceptedAndIgnored() {
        try (Disposer disposer = new Disposer()) {
            disposer.register(null);
            assertEquals(0, disposer.size());
        }
    }

    @Test
    void aThrowingCloseDoesNotStopTheRest() {
        List<String> log = new ArrayList<>();
        Disposer disposer = new Disposer();
        disposer.register(logging(log, "device"));
        disposer.register(() -> {
            throw new IllegalStateException("driver said no");
        });
        disposer.register(logging(log, "input"));

        assertThrows(IllegalStateException.class, disposer::close);
        // The point: the device below the failure was still closed rather than leaked.
        assertEquals(List.of("input", "device"), log);
    }

    @Test
    void laterFailuresAreSuppressedOnTheFirst() {
        Disposer disposer = new Disposer();
        disposer.register(() -> {
            throw new IllegalStateException("second");
        });
        disposer.register(() -> {
            throw new IllegalStateException("first");
        });

        IllegalStateException e = assertThrows(IllegalStateException.class, disposer::close);
        assertEquals("first", e.getMessage());
        assertEquals(1, e.getSuppressed().length);
        assertEquals("second", e.getSuppressed()[0].getMessage());
    }

    @Test
    void aCheckedExceptionFromACloseIsReportedRatherThanSwallowed() {
        Disposer disposer = new Disposer();
        disposer.register(() -> {
            throw new java.io.IOException("handle gone");
        });

        IllegalStateException e = assertThrows(IllegalStateException.class, disposer::close);
        assertTrue(e.getCause() instanceof java.io.IOException, String.valueOf(e.getCause()));
    }

    @Test
    void closingTwiceClosesEverythingOnce() {
        List<String> log = new ArrayList<>();
        Disposer disposer = new Disposer();
        disposer.register(logging(log, "input"));

        disposer.close();
        disposer.close();

        assertEquals(List.of("input"), log);
    }

    @Test
    void registeringAfterCloseIsRefused() {
        Disposer disposer = new Disposer();
        disposer.close();
        assertThrows(IllegalStateException.class, () -> disposer.register(logging(new ArrayList<>(), "late")));
    }
}

package dev.vexelray.framework.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ROADMAP <i>Done means</i> 1, for the template: a generated application's sources hold no {@code synchronized}, no
 * {@code volatile}, nothing from {@code java.util.concurrent} and no {@code Thread}. An application's parts talk by
 * messages and the framework owns every thread (T7.3), so there is no allowlist here: every Java file under the
 * template's {@code files/}, its tests too, since a generated project is what its author copies from.
 *
 * <p>{@link #OWED} is not an allowlist. It names the files that break the rule today, each with what it is waiting
 * on, so the build stays green while the debt is in sight. It may only shrink: an entry whose file is clean fails.
 */
class TemplateConcurrencyTest {

    private static final Path FILES = Path.of("src/main/resources/dev/vexelray/framework/template/vexel-desktop/files");

    /** The files that break the rule today, by top-level class (a slot's name stands for the slot). */
    static final Map<String, String> OWED = Map.of();
    // Empty since 2026-11-15: ViewTest and LaterWindowTest hear and wait through the framework's Frames, on the test's
    // own thread, where they kept a CopyOnWriteArrayList, AtomicIntegers, a volatile and Thread.sleep.

    @Test
    void aGeneratedApplicationHasNone() {
        List<String> found = new ArrayList<>();
        for (ConcurrencyScan.Hit h : ConcurrencyScan.scan(List.of(FILES))) {
            if (!OWED.containsKey(h.type())) {
                found.add(h.toString());
            }
        }
        assertEquals(List.of(), found, "a generated application talks by messages and never touches a thread: no "
                + "synchronized, volatile, java.util.concurrent or Thread in the template (T7.3, ROADMAP Done means 1)");
    }

    @Test
    void everyDebtIsStillOwed() {
        Set<String> using = new TreeSet<>();
        for (ConcurrencyScan.Hit h : ConcurrencyScan.scan(List.of(FILES))) {
            using.add(h.type());
        }
        Set<String> paid = new TreeSet<>(OWED.keySet());
        paid.removeAll(using);
        assertEquals(Set.of(), paid, "these files are clean now (or gone): take them off OWED");
    }
}

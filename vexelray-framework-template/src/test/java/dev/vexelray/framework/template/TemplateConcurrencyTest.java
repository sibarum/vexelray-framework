package dev.vexelray.framework.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Map.entry;
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
    static final Map<String, String> OWED = Map.ofEntries(
            // VIOLATION: the generated headless tests.
            entry("packageName.ViewTest", "VIOLATION: it listens with bus().subscribe, whose callbacks run on the "
                    + "editor's lane, so it keeps what it heard in a CopyOnWriteArrayList and counts answers in "
                    + "AtomicIntegers, and it waits with Thread.sleep between frames. Waiting on the framework: a "
                    + "headless way to hear a topic in the frames the test runs, and to run frames until something "
                    + "holds, so a test never touches a thread"),
            // VIOLATION: the same, in the later example's window test.
            entry("packageName.LaterWindowTest", "VIOLATION: the same as ViewTest: a CopyOnWriteArrayList for what "
                    + "it heard on another lane, and Thread.sleep between frames"));

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

package ${packageName};

import ${packageName}.core.examples.later.LaterMessages;
import ${packageName}.core.examples.later.LaterMessages.Settled;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Cuts;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The worked examples, in the whole application built headless, as {@code ViewTest} builds it: the Examples window's
 * tree is built with the main one's, the examples' parts run on the logic lane, and their views in the frames this
 * thread runs. An example's own logic is tested on its own, with a clock of its test's (the {@code -core} tests beside
 * it); this checks that it is wired, that its view tells it what the person did, and that nothing changes in one frame.
 *
 * <p>Time here is real, since the framework's logic lane keeps its bounds with the real timer: so each test waits for
 * what it asked for, and nothing here waits on a reminder.
 */
class ExamplesTest {

    private Shell shell;
    private ${className}AppWiring wiring;
    private final List<Cuts> cuts = new ArrayList<>();
    /** What the examples' parts told their views. */
    private final List<Object> said = new CopyOnWriteArrayList<>();

    @BeforeEach
    void build() {
        wiring = new ${className}AppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
        shell.bus().subscribe(LaterMessages.SETTLED_TOPIC, said::add);
        cuts.add(Cuts.watch(shell.gui()));
        cuts.add(Cuts.watch(wiring.examples().gui()));
    }

    @AfterEach
    void dispose() {
        List<Cuts.Cut> found = new ArrayList<>();
        for (Cuts c : cuts) {
            found.addAll(c.found());
            c.close();
        }
        shell.disposer().close();
        assertEquals(List.of(), found, "changed on screen in one frame; keep the node and change it (see cuts)");
    }

    private void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        VexelApplication.frame(shell);
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
            VexelApplication.frame(shell);
        }
    }

    @Test
    void f1OpensTheExamplesAndThePersonCanRebindIt() {
        Keymap keys = shell.keymap();
        assertEquals(List.of(Shortcut.of(Key.F1)), keys.chords(keys.action("help.examples").orElseThrow()));
    }

    @Test
    void typingInTheLaterExampleSearchesOnceItStops() throws Exception {
        wiring.later().searchField().insert("cat");
        eventually("the search", () -> !said.isEmpty());
        VexelApplication.frame(shell);
        assertEquals(List.of(new Settled("cat", 1)), said, "one keystroke's worth of text, one search");
    }
}

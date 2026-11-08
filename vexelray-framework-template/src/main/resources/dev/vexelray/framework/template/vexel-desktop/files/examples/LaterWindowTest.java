package ${packageName};

import ${packageName}.core.examples.later.LaterMessages;
import ${packageName}.core.examples.later.LaterMessages.Settled;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Cuts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The later example in the whole application built headless: its part runs on the logic lane and its view in the
 * frames this thread runs. Its logic is tested on its own, with a clock of its test's ({@code LaterTest}, in
 * {@code -core}); this checks that it is wired, that its view tells it what the person did, and that nothing changes
 * in one frame. It sits here, beside the generated wiring, because the wiring is package-private.
 *
 * <p>Time here is real, since the framework's logic lane keeps its bounds with the real timer: so each test waits for
 * what it asked for, and nothing here waits on a reminder.
 */
class LaterWindowTest {

    private Shell shell;
    private ${className}AppWiring wiring;
    private final List<Cuts> cuts = new ArrayList<>();
    /** What the example's part told its view. */
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
    void typingInTheLaterExampleSearchesOnceItStops() throws Exception {
        wiring.later().searchField().insert("cat");
        eventually("the search", () -> !said.isEmpty());
        VexelApplication.frame(shell);
        assertEquals(List.of(new Settled("cat", 1)), said, "one keystroke's worth of text, one search");
    }
}

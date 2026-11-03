package ${packageName};

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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Examples window, in the whole application built headless, as {@code ViewTest} builds it: the window's tree is
 * built with the main one's, and nothing in either changes in one frame. Each example checks its own piece of the
 * window in a test of its own beside this one ({@code <Name>WindowTest}), so taking an example out takes its test with
 * it and leaves this one as it is.
 */
class ExamplesTest {

    private Shell shell;
    private ${className}AppWiring wiring;
    private final List<Cuts> cuts = new ArrayList<>();

    @BeforeEach
    void build() {
        wiring = new ${className}AppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
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

    @Test
    void f1OpensTheExamplesAndThePersonCanRebindIt() {
        Keymap keys = shell.keymap();
        assertEquals(List.of(Shortcut.of(Key.F1)), keys.chords(keys.action("help.examples").orElseThrow()));
    }

    @Test
    void theExamplesWindowBuildsWithTheMainOne() {
        VexelApplication.frame(shell);
        VexelApplication.frame(shell);
    }
}

package ${packageName}.examples;

import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.ListView;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;

/**
 * The Examples window's view: F1 opens the window, and choosing an example in its list shows it. A second
 * {@code @View} beside the editor's: each hears every widget message and keeps to its own widgets by their address.
 *
 * <p>The chord is a declaration, as the editor's are: F1 sends a {@link WindowCommand} that the framework carries out,
 * and the person can rebind it from the title bar's key button, where it is listed as <i>Examples</i>.
 */
@dev.vexelray.framework.api.View
public final class ExamplesView {

    private final ExamplesWindow window;

    public ExamplesView(ExamplesWindow window, Keymap keys, Sender<WindowCommand> windows) {
        this.window = window;
        keys.action("help.examples", "Examples", Shortcut.of(Key.F1))
                .sends(windows, WindowCommand.show(ExamplesWindow.KEY));
    }

    @Subscribe
    public void chosen(ListView.Selected m) {
        window.chosen(m);
    }
}

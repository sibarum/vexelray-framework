package ${packageName}.examples;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Windows;

/**
 * The worked examples' window, declared. Every {@code @Configuration} in this module's sources is read by the
 * processor, so this needs no line in {@code Recipes}, and each example's own configuration (in its folder under
 * {@code examples/}) adds its part to the window the same way. Deleting {@code examples/} in both modules, with
 * {@code EXAMPLES.md} and {@code checks/}, takes them all out, and nothing else names them.
 */
@Configuration
public final class Examples {

    /** A window by name: its own {@code Gui} and title bar, built by the framework, and claimed when the main one is. */
    @Provides
    public ExamplesWindow examples(Windows windows) {
        return new ExamplesPage(windows.named(ExamplesWindow.KEY, "Examples", ExamplesPage.W, ExamplesPage.H));
    }
}

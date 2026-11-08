package ${packageName}.examples;

import dev.vexelray.framework.api.All;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.framework.shell.Windows;

/**
 * The worked examples' window, declared. Every {@code @Configuration} in this module's sources is read by the
 * processor, so this needs no line in {@code Recipes}, and each example's own configuration (in its folder under
 * {@code examples/}) provides its {@link Example}, built into the window's {@code Gui}. The page takes every one there
 * is ({@code All<Example>}), so nothing here names an example: deleting {@code examples/} in both modules, with
 * {@code EXAMPLES.md} and {@code checks/}, takes them all out, and deleting one example's folders takes out that one.
 */
@Configuration
public final class Examples {

    /** A window by name: its own {@code Gui} and title bar, built by the framework, and claimed when the main one is. */
    @Provides
    public ExamplesWindow examples(Windows windows) {
        Window window = windows.named(ExamplesWindow.KEY, "Examples", ExamplesPage.W, ExamplesPage.H);
        return () -> window;
    }

    /** The page: built after every example, which the processor sees to, since it takes them all. */
    @Provides
    public ExamplesList page(ExamplesWindow window, All<Example> examples) {
        return new ExamplesPage(window.window(), examples);
    }
}

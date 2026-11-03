package ${packageName}.examples.later;

import ${packageName}.core.examples.later.Later;
import ${packageName}.examples.Example;
import ${packageName}.examples.ExamplesWindow;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;

/**
 * Everything the <i>later</i> example adds to the application: {@link Later}, which is in {@code -core} and so a class
 * file to the processor compiling this module (named here as {@code Core} names the editor), its panel, built into the
 * Examples window, and the {@link Example} the window lists, which is the panel as the window sees it.
 * {@link LaterView} is found by its annotation. Delete this folder and {@code -core}'s {@code examples/later/}, and the
 * example is gone.
 */
@Configuration(components = Later.class)
public final class LaterParts {

    /** The panel, as its view sees it. */
    @Provides
    public LaterPanel later(ExamplesWindow examples) {
        return new LaterNodes(examples.gui());
    }

    /** The panel, as the Examples window lists it: one of the {@code All<Example>} its page takes. */
    @Provides
    public Example laterExample(LaterPanel later) {
        return later.example();
    }
}

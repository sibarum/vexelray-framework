package ${packageName}.examples.later;

import ${packageName}.core.examples.later.Later;
import ${packageName}.examples.ExamplesWindow;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;

/**
 * Everything the <i>later</i> example adds to the application: {@link Later}, which is in {@code -core} and so a class
 * file to the processor compiling this module (named here as {@code Core} names the editor), and its part of the
 * Examples window. {@link LaterView} is found by its annotation. Delete this folder and {@code -core}'s
 * {@code examples/later/}, and the example is gone.
 */
@Configuration(components = Later.class)
public final class LaterParts {

    @Provides
    public LaterPanel later(ExamplesWindow examples) {
        LaterNodes panel = new LaterNodes(examples.gui());
        examples.add(panel.example());
        return panel;
    }
}

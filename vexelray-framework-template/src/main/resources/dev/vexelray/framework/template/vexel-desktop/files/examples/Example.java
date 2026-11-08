package ${packageName}.examples;

import dev.vexelray.gui.core.Node;

/**
 * One worked example, as the Examples window lists and shows it: the question it answers, a few lines on how, and its
 * live part, built into the window's {@code Gui}. Each example's own configuration provides one, and
 * {@link ExamplesPage} takes them all ({@code All<Example>}), so leaving an example out of the build leaves it out of
 * the list.
 *
 * <p>An interface, as anything a recipe returns is: {@link #of} makes one.
 */
public interface Example {

    /** The example's folder name ({@code examples/<name>/}, {@code checks/<name>.txt}), and its row in {@code EXAMPLES.md}. */
    String name();

    /** What someone building an application would ask, which is how the list names it. */
    String question();

    /** How it answers, and where to read its files and its test. */
    String about();

    /** The live example, shown under them. */
    Node node();

    static Example of(String name, String question, String about, Node node) {
        return new Example() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String question() {
                return question;
            }

            @Override
            public String about() {
                return about;
            }

            @Override
            public Node node() {
                return node;
            }
        };
    }
}

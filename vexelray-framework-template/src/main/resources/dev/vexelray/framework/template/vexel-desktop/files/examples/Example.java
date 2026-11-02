package ${packageName}.examples;

import dev.vexelray.gui.core.Node;

/**
 * One worked example, as the {@link ExamplesWindow} lists and shows it: the question it answers, a few lines on how,
 * and its live part, built into the window's {@code Gui}. Each example's own recipe makes one and
 * {@link ExamplesWindow#add adds} it.
 *
 * @param name     the example's folder name ({@code examples/<name>/}, {@code checks/<name>.txt}), which is also its
 *                 row in {@code EXAMPLES.md}
 * @param question what someone building an application would ask, which is how the list names it
 * @param about    how it answers, and where to read its files and its test
 * @param node     the live example, shown under them
 */
public record Example(String name, String question, String about, Node node) {
}

package dev.vexelray.framework.shell;

import java.util.Set;

/**
 * The facts from {@code @VexelApp}, carried as values rather than read from an annotation.
 *
 * <p>The generated wiring returns one of these from a constant, so nothing reads the annotation at runtime.
 * That is the point: the annotation is a compile-time instruction, and this record is what it compiles to.
 *
 * @param name        the settings directory, {@code $HOME/.{name}/} — stable across releases, or the user loses
 *                    their window placement
 * @param title       the main window's title
 * @param width       first-run width, in the engine's logical coordinates
 * @param height      first-run height
 * @param settingKeys every {@code @Setting} key the application declares, so an unknown {@code --flag} can be
 *                    refused by name with the alternatives listed. The processor fills this in, which is why it
 *                    cannot fall out of date with the code that reads the settings
 */
public record AppInfo(String name, String title, int width, int height, Set<String> settingKeys) {

    public AppInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("@VexelApp needs a name: it is the settings directory");
        }
        settingKeys = Set.copyOf(settingKeys == null ? Set.of() : settingKeys);
    }

    /** For an application that declares no settings of its own. */
    public AppInfo(String name, String title, int width, int height) {
        this(name, title, width, height, Set.of());
    }
}

package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>Hand {@code target} to the desktop</b>, as a double click in its own file manager would: a file to the program
 * its type is associated with, a folder to the file manager, a web address to the browser. The framework runs the
 * desktop's opener on the job lane and publishes one {@link Opened} with the same {@code target}, saying whether it
 * started, so whoever asked can say so.
 *
 * <p>Not waited for past the opener's start: what opens is another program, and its life is not this application's.
 * Which program does the opening differs by OS, and is the engine's ({@code NativePlatform.open}), so a part never
 * names an OS module to open anything (Vexplore FN-4).
 *
 * <p>A string, not a {@code Path}, as {@link PickFile}'s are: a message crosses lanes and shares nothing, and a web
 * address is no path. A file is best given absolute, since the opener's working directory is not this application's.
 *
 * {@snippet :
 * out.send(Open.TOPIC, new Open(file.toAbsolutePath().toString()));
 * // ... and in a step on Opened.TOPIC:
 * if (!opened.opened()) {
 *     out.send(STATUS, new Status("Can't open " + name + ": " + opened.problem()));
 * }
 * }
 *
 * @param target a file or folder, absolute, or a web address
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Open(String target) {

    public static final Topic<Open> TOPIC = Topic.edge(Open.class);

    public Open {
        java.util.Objects.requireNonNull(target, "target");
    }
}

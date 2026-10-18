package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What this application's binaries were built from: its coordinates and exact source, and the exact source of every
 * stamped part of the stack it was built with (the {@code vexelray-build-maven-plugin}'s bill, see its README).
 *
 * <p>A constant, baked into the generated wiring while the application compiles; nothing is read at run time. A
 * source is the git commit, plus {@code +dirty.<digest>} of the uncommitted changes when there were any, so two
 * builds with the same source were built from the same files.
 *
 * @param group    the application's Maven groupId, or {@code -}
 * @param artifact the application's Maven artifactId, or {@code -}
 * @param version  the application's Maven version, or {@code -}
 * @param source   the application's exact source, or {@link #UNKNOWN_SOURCE}
 * @param stack    each stamped part, as {@code group:artifact:version@source}, ordered by group and artifact
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record BuildInfo(String group, String artifact, String version, String source, List<String> stack) {

    /** The source when the application was not built with the plugin. */
    public static final String UNKNOWN_SOURCE = "unknown";

    /** Not built with {@code vexelray-build-maven-plugin}: nothing is known. */
    public static final BuildInfo UNKNOWN = new BuildInfo("-", "-", "-", UNKNOWN_SOURCE, List.of());

    public BuildInfo {
        stack = List.copyOf(stack);
    }

    /** Whether the source is known. */
    public boolean known() {
        return !UNKNOWN_SOURCE.equals(source);
    }

    /** {@code group:artifact:version@source}: this build, in one line. */
    public String line() {
        return group + ":" + artifact + ":" + version + "@" + source;
    }

    /**
     * As facts for the automation socket's discovery file and {@code hello}: {@code app.group}, {@code app.artifact},
     * {@code app.version}, {@code app.source}, {@code stack.count} and {@code stack.<n>}.
     */
    public Map<String, String> facts() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("app.group", group);
        f.put("app.artifact", artifact);
        f.put("app.version", version);
        f.put("app.source", source);
        f.put("stack.count", Integer.toString(stack.size()));
        for (int i = 0; i < stack.size(); i++) {
            f.put("stack." + (i + 1), stack.get(i));
        }
        return f;
    }
}

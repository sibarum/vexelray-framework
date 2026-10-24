package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What this application's binaries were built from: its coordinates and exact source, and the exact source of every
 * stamped part of the stack it was built with (the {@code vexelray-build-maven-plugin}'s bill, see its README); and
 * what the About window says of it besides: its proper name, when it was built, where its third-party notices are read
 * and where each part's problems are reported.
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
 * @param name     the application's proper name (its {@code installer.json}'s, else its pom's), or empty when the bill
 *                 is from before the plugin wrote one
 * @param built    when this identity was first built, as an ISO-8601 instant, or empty
 * @param notices  where this build's third-party notices are read, or empty when it has none
 * @param issues   where problems are reported, by {@code group:artifact}: the application's own and each stamped
 *                 part's that names a tracker
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record BuildInfo(String group, String artifact, String version, String source, List<String> stack,
                        String name, String built, String notices, Map<String, String> issues) {

    /** The source when the application was not built with the plugin. */
    public static final String UNKNOWN_SOURCE = "unknown";

    /** Not built with {@code vexelray-build-maven-plugin}: nothing is known. */
    public static final BuildInfo UNKNOWN = new BuildInfo("-", "-", "-", UNKNOWN_SOURCE, List.of());

    /** The framework's own coordinates: the part whose tracker is the framework's. */
    static final String FRAMEWORK = "dev.vexelray.framework:vexelray-framework-shell";

    public BuildInfo {
        stack = List.copyOf(stack);
        name = name == null ? "" : name;
        built = built == null ? "" : built;
        notices = notices == null ? "" : notices;
        issues = Map.copyOf(issues == null ? Map.of() : issues);
    }

    /** A bill with only the identity in it: a hand-written wiring's, or one from before the plugin wrote the rest. */
    public BuildInfo(String group, String artifact, String version, String source, List<String> stack) {
        this(group, artifact, version, source, stack, "", "", "", Map.of());
    }

    /** Whether the source is known. */
    public boolean known() {
        return !UNKNOWN_SOURCE.equals(source);
    }

    /** {@code group:artifact:version@source}: this build, in one line. */
    public String line() {
        return group + ":" + artifact + ":" + version + "@" + source;
    }

    /** {@code group.artifact}, as Maven coordinates are written in prose; {@code -} when they are unknown. */
    public String coordinates() {
        return "-".equals(group) && "-".equals(artifact) ? "-" : group + "." + artifact;
    }

    /** The commit, without the digest of any uncommitted changes. */
    public String commit() {
        int plus = source.indexOf('+');
        return plus < 0 ? source : source.substring(0, plus);
    }

    /** Whether the build had uncommitted changes, so its commit alone does not say what it was built from. */
    public boolean dirty() {
        return source.indexOf('+') >= 0;
    }

    /** {@link #built}, read; empty when it is not known or is not an instant. */
    public Optional<Instant> builtAt() {
        try {
            return built.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(built));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Where problems with the framework are reported, from its own stamp; empty when its stamp names none. */
    public Optional<String> frameworkIssues() {
        return Optional.ofNullable(issues.get(FRAMEWORK)).filter(s -> !s.isBlank());
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

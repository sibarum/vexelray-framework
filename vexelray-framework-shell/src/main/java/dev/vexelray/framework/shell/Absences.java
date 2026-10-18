package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import sibarum.probe.Log;

/**
 * What the generated wiring calls when a {@code @Provides} method returns {@code null}: the report {@code Provides}
 * promises, said once by the framework rather than by each provider.
 *
 * <p><b>Once per provider per run, by construction.</b> The wiring calls each provider once, and calls this at that
 * site when the answer is null, so there is no set of keys to keep here. The null is still passed on: absence is a
 * supported answer, and a part that tolerates it is built with it. The report names those parts, so a part that does
 * not tolerate it fails beside a line saying where its null came from.
 *
 * <p><b>{@code WARN}</b>, by {@code Level}'s convention: something was dropped and the application carried on,
 * possibly in a worse state — the class of thing {@code Diagnostics.dropped} reports. It is not {@code INFO}, which is
 * the run's lifecycle, and a provider answering null is the application deciding it is degraded — a window nobody can
 * click — which is worth seeing in a packaged build's console. Not {@code ERROR}: nothing failed that was asked for.
 *
 * <p><b>Not for application code</b>, on the terms {@link Placements} states: the wiring lives in the application's
 * package, so its door has to be public, and this class is that door and nothing else.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Absences {

    private static final Log LOG = Log.of("framework.wiring");

    private Absences() {
    }

    /**
     * {@code provider} returned no {@code type}.
     *
     * @param parts the parts built with the null — an element is {@code null} for a part this run's mode does not
     *              build, and is skipped
     */
    public static void provided(String provider, String type, String... parts) {
        LOG.warn("{} provided no {}: absent. {}", provider, type, builtWith(parts));
    }

    /**
     * As {@link #provided}, for a type the framework takes back: having been handed nothing, it keeps its own.
     */
    public static void handedBack(String provider, String type, String... parts) {
        LOG.warn("{} provided no {}: absent, so the framework keeps its own. {}", provider, type, builtWith(parts));
    }

    static String builtWith(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null) {
                out.append(out.isEmpty() ? "" : ", ").append(part);
            }
        }
        return out.isEmpty() ? "Nothing is built with it." : "Built with the null: " + out + ".";
    }
}

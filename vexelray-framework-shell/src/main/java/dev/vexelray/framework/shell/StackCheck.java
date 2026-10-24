package dev.vexelray.framework.shell;

import sibarum.probe.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Whether the stack on the class path is the stack this application, and each part of it, was built against: said
 * once at startup, as a warning that names each part and what to rebuild.
 *
 * <p><b>Why at startup.</b> The stack is a dozen repositories installed into one local Maven repository, often by more
 * than one hand at once. A part installed after the application compiled, or a part built against an older copy of
 * another, compiles and starts and then fails the first time the code that differs runs: a
 * {@code NoSuchMethodError} from a click, or a fix that silently is not there. On 2026-10-24 both happened in one
 * afternoon: a half-installed {@code vexelray-text} crashed four applications at launch, and an older
 * {@code vexelray-gui-core} installed over a newer one took a day's fixes out of every application without a word.
 * Each jar already says what it was built from ({@code vexelray-build}'s stamp) and what it was built against (its own
 * bill), so the mismatch is knowable before anything runs, and a startup warning beats a runtime error.
 *
 * <p><b>Why a warning and not a halt.</b> A part rebuilt from a later commit is usually compatible, so a mismatch is a
 * risk and not a fault. What it buys is that the crash, if it comes, has its cause written a few lines above it.
 *
 * <p>Two questions, both answered from resources by name, so nothing reflects:
 * <ul>
 *   <li><b>Was a part replaced after the application compiled?</b> The application's bill ({@link BuildInfo#stack()},
 *       baked into its wiring) names the source of every part it compiled against; each part's stamp names the source
 *       of the jar actually loaded.</li>
 *   <li><b>Was a part built against another version of a part?</b> Each jar carries its own bill, naming the source of
 *       every part it was compiled against; the stamps say what is loaded instead.</li>
 * </ul>
 * A build without the plugin, or a native image that did not keep these resources, has nothing to compare and says
 * nothing.
 */
final class StackCheck {

    private static final Log LOG = Log.of("framework.stack");

    /** Where each jar's own bill is, and each part's stamp, relative to the class path root. */
    static final String BILL = "META-INF/vexelray/build.properties";
    static final String STAMPS = "META-INF/vexelray/build/";

    /**
     * One part loaded from another source than something was built against.
     *
     * @param part   {@code group:artifact}
     * @param wanted the source it was built against
     * @param loaded the source of the jar on the class path
     * @param by     who was built against {@code wanted}: the application, or another part as {@code group:artifact}
     */
    record Mismatch(String part, String wanted, String loaded, String by) {
    }

    private StackCheck() {
    }

    /** Say once, as a warning, whatever {@link #find} finds. Never throws: a check that cannot read has nothing to say. */
    static void report(BuildInfo build, ClassLoader loader) {
        try {
            List<Mismatch> found = find(build, loader);
            if (!found.isEmpty()) {
                LOG.warn(describe(found));
            }
        } catch (RuntimeException e) {
            LOG.debug("the stack check could not read the class path", e);
        }
    }

    /** Every part loaded from another source than the application, or another part, was built against. */
    static List<Mismatch> find(BuildInfo build, ClassLoader loader) {
        Map<String, Mismatch> found = new LinkedHashMap<>();
        Map<String, String> loaded = new LinkedHashMap<>();
        String self = build.group() + ":" + build.artifact();
        for (String line : build.stack()) {
            compare(line, self, loader, loaded, found);
        }
        try {
            Enumeration<URL> bills = loader.getResources(BILL);
            while (bills.hasMoreElements()) {
                Properties bill = read(bills.nextElement());
                String owner = bill.getProperty("group", "-") + ":" + bill.getProperty("artifact", "-");
                if (owner.equals(self)) {
                    continue;   // the application's own, already compared through its wiring
                }
                int count = parseCount(bill.getProperty("stack.count", "0"));
                for (int i = 1; i <= count; i++) {
                    String line = bill.getProperty("stack." + i);
                    if (line != null) {
                        compare(line, owner, loader, loaded, found);
                    }
                }
            }
        } catch (IOException e) {
            // Whatever was found from the application's own bill still stands.
        }
        return new ArrayList<>(found.values());
    }

    /** {@code group:artifact:version@source}, as {@code by} was built against it, against the stamp loaded. */
    private static void compare(String line, String by, ClassLoader loader, Map<String, String> loaded,
                                Map<String, Mismatch> found) {
        int at = line.indexOf('@');
        String[] coords = (at < 0 ? line : line.substring(0, at)).split(":");
        if (at < 0 || coords.length < 2) {
            return;
        }
        String part = coords[0] + ":" + coords[1];
        String wanted = line.substring(at + 1).trim();
        String now = loaded.computeIfAbsent(part, p -> stampedSource(loader, coords[0], coords[1]));
        if (!now.isEmpty() && !now.equals(wanted)) {
            found.putIfAbsent(part + "|" + by, new Mismatch(part, wanted, now, by));
        }
    }

    /** The source the loaded jar of {@code group:artifact} was built from, or empty when it carries no stamp. */
    private static String stampedSource(ClassLoader loader, String group, String artifact) {
        URL stamp = loader.getResource(STAMPS + group + "/" + artifact + ".properties");
        if (stamp == null) {
            return "";
        }
        try {
            return read(stamp).getProperty("source", "").trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static Properties read(URL url) throws IOException {
        Properties p = new Properties();
        try (InputStream in = url.openStream();
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static int parseCount(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The warning: what differs, what it risks, and what to rebuild. Grouped by the two sources, because a mismatch
     * comes a repository at a time: one reinstall moves every module of it, and a dozen lines saying so once each
     * would hide the one thing to read, which is the pair of commits.
     */
    static String describe(List<Mismatch> found) {
        Map<String, List<Mismatch>> groups = new LinkedHashMap<>();
        for (Mismatch m : found) {
            groups.computeIfAbsent(m.loaded() + "|" + m.wanted(), k -> new ArrayList<>()).add(m);
        }
        StringBuilder s = new StringBuilder("the stack on the class path is not the one it was built against, so code"
                + " that differs between them can fail when it first runs (NoSuchMethodError, NoClassDefFoundError,"
                + " or a fix that is not there):");
        for (List<Mismatch> group : groups.values()) {
            List<String> parts = new ArrayList<>();
            List<String> by = new ArrayList<>();
            for (Mismatch m : group) {
                if (!parts.contains(artifact(m.part()))) {
                    parts.add(artifact(m.part()));
                }
                if (!by.contains(artifact(m.by()))) {
                    by.add(artifact(m.by()));
                }
            }
            Mismatch first = group.get(0);
            s.append(' ').append(names(parts)).append(parts.size() == 1 ? " is " : " are ")
                    .append(shortSource(first.loaded())).append(", but ").append(names(by))
                    .append(by.size() == 1 ? " was" : " were").append(" built against ")
                    .append(shortSource(first.wanted())).append(';');
        }
        s.setLength(s.length() - 1);
        return s.append(". Reinstall the stack from the first repository behind these (./bootstrap.sh --from <repo>, in"
                + " vexelray-framework), then rebuild the application").toString();
    }

    /** The artifact of {@code group:artifact}, which is the name a reader knows a part by. */
    private static String artifact(String part) {
        int colon = part.indexOf(':');
        return colon < 0 ? part : part.substring(colon + 1);
    }

    /** {@code a}, {@code a and b}, or {@code a, b and 4 more}. */
    private static String names(List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        if (names.size() == 2) {
            return names.get(0) + " and " + names.get(1);
        }
        return names.get(0) + ", " + names.get(1) + " and " + (names.size() - 2) + " more";
    }

    /** A commit to twelve characters, keeping a {@code +dirty.<digest>} suffix whole. */
    private static String shortSource(String source) {
        int plus = source.indexOf('+');
        String commit = plus < 0 ? source : source.substring(0, plus);
        return (commit.length() > 12 ? commit.substring(0, 12) : commit) + (plus < 0 ? "" : source.substring(plus));
    }
}

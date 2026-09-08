package dev.vexelray.framework.core;

import dev.vexelray.framework.api.RunMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the command line asked for, settled once, before anything is constructed.
 *
 * <p>Every application on this stack parses this itself today, and the four copies have drifted: the same flags
 * appear in different orders, {@code --capture}'s optional output path is read positionally in three slightly
 * different ways, and the scaffold documents having thrown {@code NumberFormatException} out of {@code main} on
 * a misspelled flag — <i>"a stack trace, before any window, for a typo"</i>.
 *
 * <p><b>The typo is the interesting one, because fixing it properly needs the compiler.</b> A hand-written
 * {@code main} cannot reject {@code --verbse} without keeping a list of every flag it accepts, in agreement
 * with the code that reads them; miss one and a real flag is rejected, forget to add one and a typo is
 * silently ignored. The processor already knows every {@code @Setting} key in the application, so it passes
 * them in as {@code knownKeys} and an unrecognised flag is refused by name, with the alternatives listed. That
 * list cannot fall out of date, because it is not written by anybody.
 *
 * <p>The grammar is small and uniform:
 *
 * <pre>
 *   app                          a session
 *   app 240                      render 240 frames and exit
 *   app --capture [out.png]      render one frame to a PNG and exit; no window, no input backend
 *   app --key=value              override a setting for this launch
 *   app --key                    the same, with the value "true"
 * </pre>
 *
 * <p>{@code mode}, {@code frames} and {@code captureOut} are the framework's own three questions; everything
 * else is an override, resolved later at the precedence {@code Setting} documents. That includes the two
 * switches the demos hand-roll — {@code --profile} and {@code --automation} — which are settings like any
 * other and are not given fields of their own here. There is no third category.
 */
public record Launch(RunMode mode, int frames, Path captureOut, Map<String, String> overrides,
                     List<String> rest) {

    /**
     * Keys the framework itself understands, and so accepts without the application declaring them.
     *
     * <p>{@code profile} turns on the frame probe; {@code automation} binds the driving socket. Both are off
     * unless asked for, and both are deliberately settings rather than modes — profiling a windowed session and
     * profiling a fixed-frame run are both meaningful, so they are not alternatives to anything.
     */
    public static final Set<String> FRAMEWORK_KEYS = Set.of("profile", "automation");

    public Launch {
        overrides = Map.copyOf(overrides);
        rest = List.copyOf(rest);
    }

    /**
     * Parse {@code argv}.
     *
     * @param argv      the arguments as {@code main} received them; blank entries are ignored, because a shell
     *                  or an IDE run configuration that expands an empty variable produces one and it has never
     *                  meant anything
     * @param appName   the application's name, used for {@code --capture}'s default output file
     * @param knownKeys every {@code @Setting} key the application declares, from the processor
     * @throws IllegalArgumentException on anything unrecognised, with a message meant to be printed as-is and
     *                                 without a stack trace — the caller is expected to print it and exit
     */
    public static Launch parse(String[] argv, String appName, Set<String> knownKeys) {
        RunMode mode = RunMode.WINDOWED;
        int frames = 0;
        Path captureOut = null;
        Map<String, String> overrides = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>();

        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i] == null ? "" : argv[i].trim();
            if (arg.isEmpty()) {
                continue;
            }
            if (arg.equals("--capture")) {
                mode = RunMode.CAPTURE;
                // The optional output path, taken only when the next token cannot be anything else. A flag
                // clearly is not it, and neither is a bare number: --capture takes a file, and a frame count
                // means nothing to a mode that renders exactly one frame, so reading 240 as "240" the filename
                // would be obeying a line that is already a mistake.
                if (i + 1 < argv.length && isPath(argv[i + 1])) {
                    captureOut = Path.of(argv[++i].trim());
                }
                continue;
            }
            if (arg.startsWith("--")) {
                String body = arg.substring(2);
                int eq = body.indexOf('=');
                String key = eq < 0 ? body : body.substring(0, eq);
                // A bare --key is the value "true", so a boolean setting reads the way a flag looks.
                String value = eq < 0 ? "true" : body.substring(eq + 1);
                if (key.isEmpty()) {
                    throw new IllegalArgumentException("empty option name: " + arg);
                }
                if (!knownKeys.contains(key) && !FRAMEWORK_KEYS.contains(key)) {
                    throw unknown(key, knownKeys);
                }
                overrides.put(key, value);
                continue;
            }
            if (arg.startsWith("-")) {
                throw unknown(arg.substring(1), knownKeys);
            }
            if (isInteger(arg)) {
                int n = Integer.parseInt(arg);
                // Zero is a session, not an error and not a zero-frame run. That is already this stack's
                // convention -- GuiApp.run(gui, 0) is the unbounded loop -- and a script that computes its
                // frame count and arrives at none should get the same thing it would have got by passing none.
                // Not overriding an explicit --capture either: one frame to a file and n frames to a window are
                // different requests, and the file is the more specific of the two.
                if (n > 0 && mode == RunMode.WINDOWED) {
                    mode = RunMode.FRAMES;
                    frames = n;
                }
                continue;
            }
            rest.add(arg);
        }

        if (mode == RunMode.CAPTURE && captureOut == null) {
            captureOut = Path.of(appName + ".png");
        }
        return new Launch(mode, frames, captureOut, overrides, rest);
    }

    /** The value for {@code key} as given on the command line, or {@code null} if this launch did not set it. */
    public String override(String key) {
        return overrides.get(key);
    }

    /** True when {@code key} was given and reads as true. Absent means false; the flag form gives "true". */
    public boolean flag(String key) {
        return Boolean.parseBoolean(overrides.getOrDefault(key, "false"));
    }

    /** Usage text, for a caller that has just caught {@link IllegalArgumentException} from {@link #parse}. */
    public static String usage(String appName, Set<String> knownKeys) {
        StringBuilder b = new StringBuilder();
        b.append("usage: ").append(appName).append(" [--capture [out.png]] [--key=value] [frames]");
        Set<String> all = new TreeSet<>(knownKeys);
        all.addAll(FRAMEWORK_KEYS);
        if (!all.isEmpty()) {
            b.append(System.lineSeparator()).append("settings: ").append(String.join(", ", all));
        }
        return b.toString();
    }

    private static IllegalArgumentException unknown(String key, Set<String> knownKeys) {
        Set<String> all = new TreeSet<>(knownKeys);
        all.addAll(FRAMEWORK_KEYS);
        // Naming the alternatives is the whole point: the flag was misspelled, and the list is generated, so
        // it is both complete and free.
        return new IllegalArgumentException(all.isEmpty()
                ? "unknown option: --" + key
                : "unknown option: --" + key + " (known: " + String.join(", ", all) + ")");
    }

    /** A token that can serve as {@code --capture}'s output path: not a flag, and not a bare number. */
    private static boolean isPath(String token) {
        String s = token == null ? "" : token.trim();
        return !s.isEmpty() && !s.startsWith("-") && !isInteger(s);
    }

    private static boolean isInteger(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 0 && (c == '-' || c == '+') && s.length() > 1) {
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}

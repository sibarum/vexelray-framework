package dev.vexelray.framework.core;

import dev.vexelray.framework.api.RunMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the command line asked for, settled once, before anything is constructed.
 *
 * <p>Every application on this stack parsed this itself, and the four copies had drifted: the same flags in
 * different orders, and the scaffold documents having thrown {@code NumberFormatException} out of {@code main}
 * on a misspelled flag — <i>"a stack trace, before any window, for a typo"</i>.
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
 *   app --key=value              override a setting for this launch
 *   app --key                    the same, with the value "true"
 * </pre>
 *
 * <p>{@code mode} and {@code frames} are the framework's own two questions; everything else is an override,
 * resolved later at the precedence {@code Setting} documents. That includes the two switches the demos
 * hand-rolled — {@code --profile} and {@code --automation} — which are settings like any other and are not
 * given fields of their own here. There is no third category.
 *
 * <p><b>There is no {@code --capture}.</b> The framework's one-frame capture mode was removed because it
 * photographed the chrome correctly and the content silently wrongly; see {@link RunMode}.
 * {@code WindowInstrument.screenshot()} and {@code Automation}'s {@code shot} both photograph a real window on
 * the application's own device instead. An application that has its own richer capture tooling — as
 * {@code calculator-vexel-demo} does, with a zoom ladder and a shot of every rail panel — intercepts its own
 * flag before handing the rest here, and gets a clear "unknown option" if it forgets to.
 */
public record Launch(RunMode mode, int frames, Map<String, String> overrides, List<String> rest) {

    /**
     * Keys the framework <em>reserves</em>, and so accepts without the application declaring them.
     *
     * <p>{@code automation} asks for the driving socket; {@code profile} asks for the frame probe. Both are off
     * unless asked for, and both are deliberately settings rather than modes — profiling a windowed session and
     * profiling a fixed-frame run are both meaningful, so they are not alternatives to anything.
     *
     * <p><b>Reserved is not the same as honoured, and this is the one place in the framework where that gap is
     * accepted rather than closed.</b> Neither key is consumed by {@code -core} or {@code -shell}. The socket
     * is bound by {@code vexelray-framework-automation}'s {@code Driver} — a module of its own, because a
     * listening socket linked into every native binary is the wrong trade — and the probe is still the
     * application's own until there is a {@code -diagnostics} to move it into
     * ({@code text-editor-vexel-demo}'s {@code FpsProbe} reads {@code flag("profile")} to turn it on). So an
     * application that depends on neither can be given {@code --automation=7654}, have it parse, and have
     * nothing happen.
     *
     * <p>That is the failure this class exists to prevent, kept here on purpose, because the two alternatives
     * are worse. Refusing the key unless something consumes it would mean asking at runtime whether
     * {@code Driver} is on the classpath, which is a {@code Class.forName} on the startup path and this
     * framework does not reflect. Making each application declare the key returns the stack to what it had
     * before — the scaffold read {@code System.getProperty("automation", "off")} in the middle of a factory
     * method — and gives up the one thing reserving a name buys, which is that the same instrument is asked
     * for the same way in every application on the desk.
     *
     * <p>So the gap is real, it is narrow, and its fix is a compile-time one that does not exist yet:
     * {@code @ConditionalOnType} makes the dependency decide, and then a key with no consumer is a build
     * question rather than a quiet launch. {@link #usage} lists these separately from the application's own
     * keys in the meantime, so that at least the two categories are not presented as one.
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
     * @param appName   the application's name, for the usage line
     * @param knownKeys every {@code @Setting} key the application declares, from the processor
     * @throws IllegalArgumentException on anything unrecognised, with a message meant to be printed as-is and
     *                                 without a stack trace — the caller is expected to print it and exit
     */
    public static Launch parse(String[] argv, String appName, Set<String> knownKeys) {
        RunMode mode = RunMode.WINDOWED;
        int frames = 0;
        Map<String, String> overrides = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>();

        for (String raw : argv) {
            String arg = raw == null ? "" : raw.trim();
            if (arg.isEmpty()) {
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
                if (n > 0) {
                    mode = RunMode.FRAMES;
                    frames = n;
                }
                continue;
            }
            rest.add(arg);
        }
        return new Launch(mode, frames, overrides, rest);
    }

    /** The value for {@code key} as given on the command line, or {@code null} if this launch did not set it. */
    public String override(String key) {
        return overrides.get(key);
    }

    /** True when {@code key} was given and reads as true. Absent means false; the flag form gives "true". */
    public boolean flag(String key) {
        return Boolean.parseBoolean(overrides.getOrDefault(key, "false"));
    }

    /**
     * Usage text, for a caller that has just caught {@link IllegalArgumentException} from {@link #parse}.
     *
     * <p>The application's own keys and the framework's reserved ones are on separate lines, because they are
     * not the same promise: a declared key is read by this application's own code, and a reserved one is read
     * by whatever module happens to be linked in. See {@link #FRAMEWORK_KEYS} for why that gap is open.
     */
    public static String usage(String appName, Set<String> knownKeys) {
        StringBuilder b = new StringBuilder();
        b.append("usage: ").append(appName).append(" [--key=value] [frames]");
        Set<String> own = new TreeSet<>(knownKeys);
        own.removeAll(FRAMEWORK_KEYS);
        if (!own.isEmpty()) {
            b.append(System.lineSeparator()).append("settings: ").append(String.join(", ", own));
        }
        b.append(System.lineSeparator()).append("framework: ")
                .append(String.join(", ", new TreeSet<>(FRAMEWORK_KEYS)));
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

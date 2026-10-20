package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.core.Launch;
import sibarum.probe.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * How this process was started, to start it again with some settings changed: into a debug run, or out of one
 * ({@code vexelray-framework-automation}'s {@code Driver}). A value; {@link Shell#restart} is what uses it.
 *
 * {@snippet :
 * shell.restart(shell.relaunch().drop("automation.control", "automation.diagnostics").set("automation", "0"));
 * }
 *
 * <p><b>Two halves, kept apart.</b> The <i>launcher</i> is everything before the application's own arguments: a native
 * image's path, or the java launcher with its options, class path and main class. The <i>arguments</i> are what
 * {@code main} was handed. A setting can be given in either place ({@code --automation=0} after the class,
 * {@code -Dautomation=0} before it), so {@link #drop} takes it out of both and {@link #set} puts it in the arguments,
 * where {@code Setting}'s order makes it win.
 *
 * <p><b>Where the launcher comes from.</b> {@code ProcessHandle.Info} names the program on every platform, and its
 * arguments on every platform but Windows, which is where this is mostly used; so under the JVM the launcher is
 * rebuilt from what the JVM says about itself: its input arguments, {@code java.class.path} and the main class. In a
 * native image it is the image alone. When neither can be said, {@link #unavailable} says why, and a restart is not
 * offered.
 *
 * <p><b>The new process waits for this one.</b> {@link #start} adds {@code --restart.after=<this pid>}
 * ({@link Launch#RESTART_AFTER}), and {@link #awaitPredecessor} holds the new process there until this one has exited,
 * so the settings it saves on the way out, its window's placement and every file it held are the new one's first.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Relaunch {

    private static final Log LOG = Log.of("framework.relaunch");

    /** How long a restarted process waits for the one it replaces before it goes on regardless. */
    static final long PREDECESSOR_WAIT_SECONDS = 30;

    private final List<String> launcher;
    private final List<String> arguments;
    private final Map<String, String> environment;
    private final String unavailable;

    private Relaunch(List<String> launcher, List<String> arguments, Map<String, String> environment,
                     String unavailable) {
        this.launcher = List.copyOf(launcher);
        this.arguments = List.copyOf(arguments);
        this.environment = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        this.unavailable = unavailable;
    }

    /** {@code launcher} then {@code arguments}, as given: for a test, and for a launch that knows its own. */
    public static Relaunch of(List<String> launcher, List<String> arguments) {
        return new Relaunch(launcher, arguments, Map.of(), launcher.isEmpty() ? "no launcher was given" : null);
    }

    /** This process, which {@code main} handed {@code arguments}. */
    static Relaunch current(List<String> arguments) {
        try {
            String program = ProcessHandle.current().info().command().orElse(null);
            if (program == null) {
                return new Relaunch(List.of(), arguments, Map.of(), "the operating system does not say which program "
                        + "this process is");
            }
            if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
                return new Relaunch(List.of(program), arguments, Map.of(), null);
            }
            List<String> launcher = new ArrayList<>();
            launcher.add(program);
            launcher.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
            String why = mainClass(launcher);
            return new Relaunch(launcher, arguments, Map.of(), why);
        } catch (RuntimeException | LinkageError e) {
            return new Relaunch(List.of(), arguments, Map.of(), "how this process was started could not be read: "
                    + e.getMessage());
        }
    }

    /** The class path or module and the main class, added to {@code launcher}; or why they could not be. */
    private static String mainClass(List<String> launcher) {
        String module = System.getProperty("jdk.module.main");
        if (module != null && !module.isBlank()) {
            String path = System.getProperty("jdk.module.path", "");
            if (!path.isBlank()) {
                launcher.add("-p");
                launcher.add(path);
            }
            String cls = System.getProperty("jdk.module.main.class", "");
            launcher.add("-m");
            launcher.add(cls.isBlank() ? module : module + "/" + cls);
            return null;
        }
        String command = System.getProperty("sun.java.command", "").trim();
        if (command.isEmpty()) {
            return "the JVM does not say which class it was started with";
        }
        // The class (or jar), then main's arguments, joined with spaces: the first word is the class, since a class
        // name has none. A jar's path might, which is why a jar is recognised by what the whole command starts with.
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank() && command.startsWith(classPath) && classPath.endsWith(".jar")) {
            launcher.add("-jar");
            launcher.add(classPath);
            return null;
        }
        int space = command.indexOf(' ');
        String main = space < 0 ? command : command.substring(0, space);
        if (!classPath.isBlank()) {
            launcher.add("-cp");
            launcher.add(classPath);
        }
        launcher.add(main);
        return null;
    }

    /** Why this process cannot be started again, or empty when it can. */
    public Optional<String> unavailable() {
        return Optional.ofNullable(unavailable);
    }

    /** {@code --key=value} in the arguments, in place of any {@code --key} they had. */
    public Relaunch set(String key, String value) {
        List<String> args = new ArrayList<>(without(arguments, key));
        args.add("--" + key + "=" + value);
        return new Relaunch(launcher, args, environment, unavailable);
    }

    /**
     * Without {@code keys}: {@code --key} and {@code --key=...} out of the arguments, and {@code -Dkey} and
     * {@code -Dkey=...} out of the launcher's options.
     */
    public Relaunch drop(String... keys) {
        List<String> args = arguments;
        List<String> options = launcher;
        for (String key : keys) {
            args = without(args, key);
            options = withoutProperty(options, key);
        }
        return new Relaunch(options, args, environment, unavailable);
    }

    /** With {@code name} set to {@code value} in the new process's environment, or taken out of it when null. */
    public Relaunch environment(String name, String value) {
        Map<String, String> env = new LinkedHashMap<>(environment);
        env.put(name, value);
        return new Relaunch(launcher, arguments, env, unavailable);
    }

    /** Everything that starts the process: the launcher, then the arguments. */
    public List<String> command() {
        List<String> all = new ArrayList<>(launcher);
        all.addAll(arguments);
        return List.copyOf(all);
    }

    /** The application's own arguments. */
    public List<String> arguments() {
        return arguments;
    }

    /** What the new process's environment changes: a value, or null for a variable taken out. */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Start the process, told to wait for this one ({@link Launch#RESTART_AFTER}). Its output goes nowhere: it outlives
     * whatever was reading this process's, and its log is where it says what it is doing.
     *
     * @throws IOException           when the program cannot be started
     * @throws IllegalStateException when {@link #unavailable} says it cannot be
     */
    public Process start() throws IOException {
        if (unavailable != null) {
            throw new IllegalStateException("cannot restart: " + unavailable);
        }
        List<String> command = drop(Launch.RESTART_AFTER)
                .set(Launch.RESTART_AFTER, Long.toString(ProcessHandle.current().pid())).command();
        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(nul()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> env = pb.environment();
        environment.forEach((k, v) -> {
            if (v == null) {
                env.remove(k);
            } else {
                env.put(k, v);
            }
        });
        Process p = pb.start();
        LOG.info("restarting: pid {} started, waiting for {} to exit", p.pid(), ProcessHandle.current().pid());
        LOG.debug("restart command: {}", command);
        return p;
    }

    /**
     * When {@code launch} is a restart, wait for the process it replaces to exit, for up to
     * {@link #PREDECESSOR_WAIT_SECONDS}; then go on regardless, since a window that never opens is worse than one that
     * opens beside a process that would not leave.
     */
    static void awaitPredecessor(Launch launch) {
        String pid = launch.override(Launch.RESTART_AFTER);
        if (pid == null) {
            return;
        }
        long before;
        try {
            before = Long.parseLong(pid.trim());
        } catch (NumberFormatException e) {
            LOG.warn("--{}={} is not a process id; not waiting", Launch.RESTART_AFTER, pid);
            return;
        }
        Optional<ProcessHandle> old = ProcessHandle.of(before);
        if (old.isEmpty()) {
            return;
        }
        LOG.info("restarted: waiting for pid {} to exit", before);
        try {
            old.get().onExit().get(PREDECESSOR_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.warn("pid {} was still running after {}s; starting beside it", before, PREDECESSOR_WAIT_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            LOG.debug("waiting for pid {}: {}", before, e.getMessage());
        }
    }

    private static java.io.File nul() {
        return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    private static List<String> without(List<String> args, String key) {
        List<String> out = new ArrayList<>();
        for (String a : args) {
            if (!a.equals("--" + key) && !a.startsWith("--" + key + "=")) {
                out.add(a);
            }
        }
        return out;
    }

    private static List<String> withoutProperty(List<String> options, String key) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            String a = options.get(i);
            // The program itself is never an option, even if it were spelled like one.
            if (i > 0 && (a.equals("-D" + key) || a.startsWith("-D" + key + "="))) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    @Override
    public String toString() {
        return unavailable != null ? "Relaunch[unavailable: " + unavailable + "]" : "Relaunch" + command();
    }
}

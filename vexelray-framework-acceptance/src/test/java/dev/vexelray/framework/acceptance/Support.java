package dev.vexelray.framework.acceptance;

import dev.vexelray.framework.template.Answers;
import dev.vexelray.framework.template.Blueprint;
import dev.vexelray.framework.template.Catalogue;
import dev.vexelray.framework.template.Checks;
import dev.vexelray.framework.template.Scaffold;
import dev.vexelray.framework.template.Template;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What every acceptance scenario needs and none of them is about: a Maven in a generated project, a process
 * that is stopped children first, a socket that is retried until the application answers, and the one-line
 * protocol spoken over it.
 *
 * <p>Each scenario keeps its output under a directory of its own inside {@code acceptance.dir}, so one clearing
 * its last run cannot delete another's.
 */
final class Support {

    /** Generous, because the first build of a fresh project resolves every plugin it names. */
    static final long BUILD_MINUTES = 10;
    static final long LAUNCH_SECONDS = 180;

    private Support() {
    }

    /** A fresh, empty directory called {@code name} inside {@code acceptance.dir}. */
    static Path fresh(String name) throws IOException {
        Path dir = Path.of(property("acceptance.dir")).resolve(name);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        return Files.createDirectories(dir);
    }

    /** The Maven running this build, in {@code project}, against this build's local repository. */
    static ProcessBuilder maven(Path project, Path log, String... goals) {
        String home = property("acceptance.maven");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = new ArrayList<>();
        command.add(Path.of(home, "bin", windows ? "mvn.cmd" : "mvn").toString());
        command.add("-B");
        command.add("-Dstyle.color=never");
        command.add("-Dmaven.repo.local=" + property("acceptance.repository"));
        command.addAll(List.of(goals));
        return new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
    }

    /** Keeps trying until the socket answers, and stops early with the log if the application has gone. */
    static Socket connect(Process app, int port, Path log) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LAUNCH_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!app.isAlive()) {
                fail("the application exited with " + app.exitValue() + " before its socket opened\n" + tail(log));
            }
            try {
                return new Socket("127.0.0.1", port);
            } catch (IOException notYet) {
                Thread.sleep(250);
            }
        }
        return fail("no automation socket on " + port + " after " + LAUNCH_SECONDS + "s\n" + tail(log));
    }

    /** Maven forks the application, so the tree goes down, children first. */
    static void stop(Process process) {
        process.descendants().sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** The last lines of a log, for a failure message somebody reads instead of opening the file. */
    static String tail(Path log) {
        // Latin-1 because it decodes any byte: a child's console output is in the platform's encoding, and a
        // failure message that fails to decode is the one moment the log cannot be lost.
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
            return "--- last lines of " + log + " ---\n"
                    + String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
        } catch (IOException e) {
            return "(" + log + " could not be read: " + e.getMessage() + ")";
        }
    }

    static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set; run this module through its pom, not an IDE");
        }
        return value;
    }

    /**
     * A generated project on disk, and the names the overlay and the assertions need: the entry class, and the
     * {@code @VexelApp} class the wiring is generated from, which lives in the edition's source root.
     */
    record Project(Path dir, String main, String packageName, String className, String appClass) {

        /** Where javac put the wiring the processor generated. */
        Path wiring() {
            return dir.resolve("target/generated-sources/annotations/" + packageName.replace('.', '/') + "/"
                    + appClass + "Wiring.java");
        }
    }

    /** Where the counter every witness is built on lives in the test resources. */
    static final String SHARED_DIR = "/witnesses/shared";

    /** The counter every witness is built on: its state, and the type scale its views use. */
    static final List<String> SHARED = List.of("Doc", "Model", "Type");

    /**
     * The builder's {@code vexel-desktop} tree, written under {@code root}, with its application swapped for the
     * witness's: the builder's sources are removed except the entry class and the look, the counter in
     * {@link #SHARED_DIR} goes in, and then each name in {@code overlay}, read from the test resources at
     * {@code overlayDir}. Each has {@code ${packageName}} and {@code ${className}} filled in. What is left of the
     * builder's is the part every application on it shares — the entry, the look, the pom and the build — which is
     * the point: a real generated project, running an application chosen to test something.
     */
    static Project generate(Path root, String artifact, String overlayDir, List<String> overlay) throws Exception {
        Template template = Catalogue.bundled().get("vexel-desktop");
        Map<String, String> given = new LinkedHashMap<>(Answers.presets(template).values());
        given.put("where", root.toString());
        given.put("artifactId", artifact);
        given.put("groupId", "dev.vexelray.acceptance");
        Answers answers = Answers.of(given).filled(template);
        assertEquals(List.of(), Checks.problems(template, answers));

        Blueprint blueprint = Scaffold.of(template, answers, Catalogue.bundled());
        Path dir = Scaffold.folder(template, answers, root);
        Blueprint.Writing writing = new Blueprint.Writing(dir);
        writing.begin();
        for (Blueprint.Entry entry : blueprint.entries()) {
            writing.write(entry);
        }

        String recipes = blueprint.paths().stream().filter(p -> p.endsWith("/Recipes.java")).findFirst()
                .orElseThrow(() -> new AssertionError("no Recipes.java in " + blueprint.paths()));
        String main = recipes.substring(0, recipes.lastIndexOf('/'));
        String packageName = main.substring("src/main/java/".length()).replace('/', '.');
        // The entry class is the one whose main runs the wiring; the @VexelApp class is not beside it but in each
        // edition's own source root, and its name is what the processor names the wiring after.
        String className = blueprint.paths().stream()
                .filter(p -> p.startsWith(main + "/") && blueprint.text(p).contains("VexelApplication.run("))
                .map(Support::simpleName)
                .findFirst().orElseThrow(() -> new AssertionError("no entry class in " + blueprint.paths()));
        String appClass = blueprint.paths().stream()
                .filter(p -> p.startsWith("src/edition-debug/java/") && blueprint.text(p).contains("@VexelApp("))
                .map(Support::simpleName)
                .findFirst().orElseThrow(() -> new AssertionError("no debug @VexelApp class in " + blueprint.paths()));
        // A witness is a counter on real lanes, not an editor, so the editor the builder wrote is taken out and the
        // witness's own application put in. What is kept of the builder's is what every application on it shares:
        // the entry class, the look, the pom and the build. The counter's state model lives in /witnesses/shared,
        // the one every witness drives, and each witness then overlays its own view and recipes.
        Set<String> kept = Set.of(className + ".java", "Look.java", "LookTest.java");
        for (String tree : List.of(main, main.replace("src/main/java", "src/test/java"))) {
            Path folder = dir.resolve(tree);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    if (Files.isRegularFile(p) && !kept.contains(p.getFileName().toString())) {
                        Files.delete(p);
                    } else if (Files.isDirectory(p) && !p.equals(folder)) {
                        try (Stream<Path> left = Files.list(p)) {
                            if (left.findAny().isEmpty()) {
                                Files.delete(p);
                            }
                        }
                    }
                }
            }
        }
        List<String> sources = new ArrayList<>();
        for (String name : SHARED) {
            sources.add(SHARED_DIR + "/" + name);
        }
        for (String name : overlay) {
            sources.add(overlayDir + "/" + name);
        }
        for (String from : sources) {
            String name = from.substring(from.lastIndexOf('/') + 1);
            String source = resource(from + ".java")
                    .replace("${packageName}", packageName)
                    .replace("${className}", className);
            Files.writeString(dir.resolve(main).resolve(name + ".java"), source, StandardCharsets.UTF_8);
        }
        return new Project(dir, main, packageName, className, appClass);
    }

    private static String simpleName(String path) {
        return path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length());
    }

    /** {@code mvn package} in the project, failing with the log if it does not pass. */
    static void build(Project project, Path log) throws Exception {
        Process build = maven(project.dir(), log, "package").start();
        if (!build.waitFor(BUILD_MINUTES, TimeUnit.MINUTES)) {
            stop(build);
            fail("the build took longer than " + BUILD_MINUTES + " minutes\n" + tail(log));
        }
        assertEquals(0, build.exitValue(), () -> "the project did not build\n" + tail(log));
    }

    /** One launch of a built project: the process, and the socket it is driven through. */
    record Session(Process app, Socket socket, PrintWriter out, Driver driver, Path log) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            socket.close();
            if (app.isAlive()) {
                stop(app);
            }
        }
    }

    /**
     * Run the project with its automation socket on and a settings home of its own under {@code root}. Extra
     * {@code -D} arguments go to the application's JVM.
     */
    static Session launch(Project project, Path root, String artifact, String name, String... jvmArgs)
            throws Exception {
        int port = freePort();
        Path log = root.resolve("run-" + name + ".log");
        Path home = root.resolve("home-" + name);
        List<String> args = new ArrayList<>(List.of("-D" + artifact + ".home=" + home));
        args.addAll(List.of(jvmArgs));
        Process app = maven(project.dir(), log, "compile", "exec:exec",
                "-Dautomation=" + port, "-Dapp.jvmArgs=" + String.join(" ", args)).start();
        Socket socket = connect(app, port, log);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        return new Session(app, socket, out, new Driver(out, in, log), log);
    }

    static String resource(String path) throws IOException {
        try (InputStream in = Support.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** One line protocol, one reply per command, terminated by a line holding only {@code .}. */
    record Driver(PrintWriter out, BufferedReader in, Path log) {

        String send(String command) throws IOException {
            out.println(command);
            StringBuilder reply = new StringBuilder();
            for (String line = in.readLine(); line != null && !line.equals("."); line = in.readLine()) {
                reply.append(line).append('\n');
            }
            return reply.toString().strip();
        }

        String ok(String command) throws IOException {
            String reply = send(command);
            if (!reply.startsWith("ok")) {
                fail("'" + command + "' answered: " + reply + "\n" + tail(log));
            }
            return reply;
        }

        /** The ref of the one node carrying this landmark, from {@code find}'s listing. */
        String ref(String landmark) throws IOException {
            for (String line : ok("find " + landmark).lines().skip(1).toList()) {
                if (line.contains(" @" + landmark)) {
                    return line.substring(0, line.indexOf(' '));
                }
            }
            return fail("no node carries the landmark " + landmark);
        }
    }
}

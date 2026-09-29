package dev.vexelray.framework.acceptance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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

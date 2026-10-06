package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finding and starting another application of the suite: one that was installed separately, by
 * {@code vexelray-installer}, into a folder of its own.
 *
 * <p><b>Where the answer comes from.</b> The installer writes one record per install,
 * {@code <registryDir>\<id>.json}, and each lists {@code commands.<name>.path}: the absolute path of every
 * executable that install placed. Only the install knows where an application went, and a native image cannot
 * carry it (a resource is fixed when the exe is built), so the records are the shared file. The directory is
 * {@code %LOCALAPPDATA%\vexelray-installer\installs}, or {@code INSTALL_REGISTRY_DIR} when that is set, which is
 * the installer's own override for tests.
 *
 * <p><b>What this does not do.</b> It does not say what arguments one application passes another: that is the
 * applications' own business, agreed between them, since the suite is built in lock-step and a framework convention
 * would only restate it and freeze it. Nor does it hand off to a running instance: each start is a new process and a
 * new window, which outlives the one that started it. Such a start is ephemeral, and should neither restore nor
 * write a saved session (see {@code docs/TODO.md}, <i>settings and the session</i>).
 *
 * <p>Windows only, as the installer is. Elsewhere there is no record to find, and {@link #find} is empty.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Apps {

    /** The installer's override for where records live; set by its tests, and honoured here for the same reason. */
    public static final String REGISTRY_ENV = "INSTALL_REGISTRY_DIR";

    private Apps() {
    }

    /** Where the installer keeps its records on this machine. */
    public static Path registryDir() {
        String override = System.getenv(REGISTRY_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank() ? Path.of(local)
                : Path.of(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve("vexelray-installer").resolve("installs");
    }

    /**
     * The executable of the application installed as {@code id}, or empty when it is not installed. The caller
     * decides what to say about that.
     */
    public static Optional<Path> find(String id) {
        return find(registryDir(), id);
    }

    /**
     * As {@link #find(String)}, against {@code registryDir}.
     *
     * <p>The command named {@code id} if the record lists one, else the record's only command. A record whose
     * executable is no longer on disk counts as not installed: somebody deleted the folder by hand, and offering to
     * start it would only fail later and less clearly.
     */
    public static Optional<Path> find(Path registryDir, String id) {
        Path record = registryDir.resolve(id + ".json");
        if (!Files.isRegularFile(record)) {
            return Optional.empty();
        }
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(record, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!(parsed instanceof Map<?, ?> top) || !(top.get("commands") instanceof Map<?, ?> commands)) {
            return Optional.empty();
        }
        Object command = commands.get(id);
        if (command == null && commands.size() == 1) {
            command = commands.values().iterator().next();
        }
        if (!(command instanceof Map<?, ?> c) || !(c.get("path") instanceof String path) || path.isBlank()) {
            return Optional.empty();
        }
        Path exe = Path.of(path);
        return Files.isRegularFile(exe) ? Optional.of(exe) : Optional.empty();
    }

    /**
     * Start the application installed as {@code id} with {@code args}, as a new process with its own window.
     * Empty when it is not installed.
     *
     * <p>Started directly, with no {@code cmd.exe} in between: a native exe needs no shell quoting, and each
     * argument arrives as one argument however many spaces it has. Its output goes nowhere, because nothing
     * would read it and a full pipe would stall it.
     *
     * @throws IOException if it is installed and could not be started
     */
    public static Optional<Process> spawn(String id, String... args) throws IOException {
        return spawn(registryDir(), id, args);
    }

    /** As {@link #spawn(String, String...)}, against {@code registryDir}. */
    public static Optional<Process> spawn(Path registryDir, String id, String... args) throws IOException {
        Optional<Path> exe = find(registryDir, id);
        if (exe.isEmpty()) {
            return Optional.empty();
        }
        List<String> command = new ArrayList<>(1 + args.length);
        command.add(exe.get().toString());
        command.addAll(List.of(args));
        Process started = new ProcessBuilder(command)
                .directory(exe.get().getParent().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        // Nothing will ever be written to it; closing it says so rather than leaving a handle open for the
        // child's lifetime.
        started.getOutputStream().close();
        return Optional.of(started);
    }

    /**
     * Just enough JSON for an install record: objects, arrays, strings with their escapes, and the scalars read as
     * text. Hand-written because nothing on this stack carries a JSON library, and a reflective one would need
     * native-image metadata to read four fields.
     */
    static final class Json {

        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            // A byte-order mark is legal at the start of a UTF-8 file and PowerShell has written one before.
            Json j = new Json(text.startsWith("﻿") ? text.substring(1) : text);
            Object value = j.value();
            j.space();
            if (j.i != j.s.length()) {
                throw j.error("trailing text");
            }
            return value;
        }

        private Object value() {
            space();
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                default -> scalar();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            i++;
            space();
            if (peek('}')) {
                i++;
                return out;
            }
            while (true) {
                space();
                if (!peek('"')) {
                    throw error("expected a key");
                }
                String key = string();
                space();
                expect(':');
                out.put(key, value());
                space();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            i++;
            space();
            if (peek(']')) {
                i++;
                return out;
            }
            while (true) {
                out.add(value());
                space();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect(']');
                return out;
            }
        }

        private String string() {
            i++;
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    // PowerShell 5.1 writes ' < > & as ' and the like, so this is not a rarity here.
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("short \\u escape");
                        }
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            }
            throw error("unterminated string");
        }

        /** A number, true, false or null, as its text: nothing here needs them as more. */
        private String scalar() {
            int start = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            if (start == i) {
                throw error("expected a value");
            }
            return s.substring(start, i);
        }

        private void space() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            i++;
        }

        private IllegalArgumentException error(String what) {
            return new IllegalArgumentException(what + " at " + i);
        }
    }
}

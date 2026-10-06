package dev.vexelray.framework.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding a sibling application through the installer's records, against records shaped the way
 * {@code install.ps1} writes them: {@code ConvertTo-Json} from Windows PowerShell 5.1, two-space indent, every
 * backslash doubled, and an apostrophe as {@code '}.
 */
final class AppsTest {

    private static void record(Path dir, String id, String json) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\u0027");
    }

    private static String commands(String name, Path exe) {
        return """
                {
                    "schemaVersion":  1,
                    "id":  "%s",
                    "requires":  [],
                    "commands":  {
                                     "%s":  {
                                                "path":  "%s",
                                                "kind":  "native",
                                                "version":  null
                                            }
                                 },
                    "files":  [ { "path": "x", "sha256": "ab" } ]
                }
                """.formatted(name, name, json(exe.toString()));
    }

    @Test
    void findsTheExecutableTheRecordNames(@TempDir Path tmp) throws Exception {
        Path exe = Files.createDirectories(tmp.resolve("Programs").resolve("O'Brien's Editor")).resolve("ed.exe");
        Files.writeString(exe, "MZ");
        record(tmp.resolve("installs"), "text-editor", commands("text-editor", exe));
        assertEquals(Optional.of(exe), Apps.find(tmp.resolve("installs"), "text-editor"));
    }

    @Test
    void notInstalledIsEmptyNotAnError(@TempDir Path tmp) throws Exception {
        assertEquals(Optional.empty(), Apps.find(tmp, "vexplore"), "no record");
        record(tmp, "vexplore", commands("vexplore", tmp.resolve("gone").resolve("vexplore.exe")));
        assertEquals(Optional.empty(), Apps.find(tmp, "vexplore"), "a record whose exe was deleted by hand");
        record(tmp, "broken", "{ \"commands\": ");
        assertEquals(Optional.empty(), Apps.find(tmp, "broken"), "a record cut short");
    }

    @Test
    void aSingleCommandIsTheAppWhateverItIsCalled(@TempDir Path tmp) throws Exception {
        Path exe = Files.writeString(tmp.resolve("mf.exe"), "MZ");
        record(tmp, "mainframe", commands("mf", exe));
        assertEquals(Optional.of(exe), Apps.find(tmp, "mainframe"));
    }

    @Test
    void readsWhatPowerShellWrites() {
        Object parsed = Apps.Json.parse("﻿{ \"a\": [1, true, null, \"x\\u0027y\\\\z\\n\"], \"b\": {} }");
        Map<?, ?> top = (Map<?, ?>) parsed;
        assertEquals(List.of("1", "true", "null", "x'y\\z\n"), top.get("a"));
        assertEquals(Map.of(), top.get("b"));
        assertThrows(IllegalArgumentException.class, () -> Apps.Json.parse("{} extra"));
        assertThrows(IllegalArgumentException.class, () -> Apps.Json.parse("{\"a\" 1}"));
    }

    /** A real start: this test's own java, asked for its version, with an argument that has a space in it. */
    @Test
    void spawnStartsItWithTheArgumentsAsGiven(@TempDir Path tmp) throws Exception {
        Path java = Path.of(ProcessHandle.current().info().command().orElseThrow());
        record(tmp, "java", commands("java", java));
        // A wrong split of "-Dmarker=a b" would hand java a stray "b" to run as a main class, and exit non-zero.
        Process p = Apps.spawn(tmp, "java", "-Dmarker=a b", "-version").orElseThrow();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "it ran and finished");
        assertEquals(0, p.exitValue());
        assertEquals(Optional.empty(), Apps.spawn(tmp, "nothing-here"));
    }
}

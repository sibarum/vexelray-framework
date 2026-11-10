package dev.vexelray.framework.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a bug report holds, and that what names the person is not in it. */
class BugReportTest {

    private final BugReport.Scrub scrub = BugReport.Scrub.of("C:\\Users\\Ann", "Ann", "DESK-7");

    @Test
    void theHomeFolderGoesInEitherSeparatorAndAnyCase() {
        assertEquals("~\\x.txt and ~/y and ~\\\\z.json",
                scrub.apply("C:\\Users\\Ann\\x.txt and c:/users/ANN/y and C:\\\\Users\\\\Ann\\\\z.json"));
    }

    @Test
    void aLongerNameUnderTheSameFolderIsNotHome() {
        assertEquals("C:\\Users\\Anna\\x", scrub.apply("C:\\Users\\Anna\\x"));
    }

    @Test
    void theUserNameGoesAsAWordAndOnlyAsAWord() {
        assertEquals("signed in as <user>; Annex and ann stay",
                scrub.apply("signed in as Ann; Annex and ann stay"));
    }

    @Test
    void theComputerNameGoesInAnyCase() {
        assertEquals("host <computer>, again <computer>", scrub.apply("host DESK-7, again desk-7"));
    }

    @Test
    void aNameTooShortToBeSafeIsLeft() {
        assertEquals("Al wrote al", BugReport.Scrub.of(null, "Al", null).apply("Al wrote al"));
    }

    @Test
    void theSystemSaysTheBuildTheMachineAndTheRunsThatDidNotClose() {
        Map<String, String> machine = new LinkedHashMap<>();
        machine.put("Operating system", "Windows 11 10.0");
        String s = BugReport.system("Demo (g:a:1)\n", machine,
                List.of(new UncleanExits.Ended(42, Instant.parse("2026-11-10T09:00:00Z"))));
        assertTrue(s.startsWith("Demo (g:a:1)\n"), s);
        assertTrue(s.contains("Operating system: Windows 11 10.0\n"), s);
        assertTrue(s.contains("process 42, started 2026-11-10"), s);
    }

    @Test
    void theMachineNamesNothingThatIdentifiesIt() {
        Map<String, String> m = BugReport.machine("PACKAGED");
        assertEquals(List.of("Operating system", "Architecture", "Processors", "Memory available to the application",
                "Runtime", "Log mode"), List.copyOf(m.keySet()));
    }

    @Test
    void theZipHoldsTheReadmeTheSystemAndEachLogScrubbed(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("demo.log");
        Files.writeString(log, "opened C:\\Users\\Ann\\notes.txt on DESK-7\n");
        Files.writeString(dir.resolve("demo.log.1"), "earlier, as Ann\n");
        List<Path> logs = BugReport.logs(log);
        assertEquals(List.of(dir.resolve("demo.log.1"), log), logs, "older first, as they were written");

        Path out = dir.resolve("report.zip");
        BugReport.write(out, BugReport.readme("Demo", "https://example.invalid/issues", List.of("demo.log.1",
                "demo.log")), "system for Ann\n", logs, scrub);

        Map<String, String> entries = read(out);
        assertEquals(List.of("README.txt", "system.txt", "logs/demo.log.1", "logs/demo.log"),
                List.copyOf(entries.keySet()));
        assertEquals("opened ~\\notes.txt on <computer>\n", entries.get("logs/demo.log"));
        assertEquals("earlier, as <user>\n", entries.get("logs/demo.log.1"));
        assertEquals("system for <user>\n", entries.get("system.txt"));
        assertTrue(entries.get("README.txt").contains("Nothing was sent anywhere"));
        assertTrue(entries.get("README.txt").contains("https://example.invalid/issues"));
        assertFalse(Files.exists(dir.resolve("report.zip.partial")));
    }

    @Test
    void aReportThatCannotBeWrittenLeavesNoHalfFile(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("gone.log");   // listed, then deleted before the write
        Path out = dir.resolve("report.zip");
        try {
            BugReport.write(out, "r", "s", List.of(missing), scrub);
        } catch (java.io.IOException expected) {
            // the point
        }
        assertFalse(Files.exists(out));
        assertFalse(Files.exists(dir.resolve("report.zip.partial")));
    }

    @Test
    void theFileNameSaysWhoseAndWhen() {
        String name = BugReport.fileName("demo", Instant.parse("2026-11-10T09:00:00Z"));
        assertTrue(name.matches("demo-bug-report-\\d{8}-\\d{4}\\.zip"), name);
    }

    private static Map<String, String> read(Path zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}

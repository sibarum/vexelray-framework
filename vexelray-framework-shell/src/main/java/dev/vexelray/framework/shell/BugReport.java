package dev.vexelray.framework.shell;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * <b>What a bug report holds</b>, and the file it is written to: one zip, made to be attached to a report by the person
 * who saved it. The framework sends nothing anywhere.
 *
 * <ul>
 *   <li>{@code README.txt}: what this is, what is in it, and what was taken out.</li>
 *   <li>{@code system.txt}: About's <i>Copy info</i> (the build and the stack it was built against), the operating
 *       system, processor count, memory and runtime, and the runs that did not close.</li>
 *   <li>{@code logs/}: the application's log and the file it last rolled over, which between them hold the run that
 *       crashed and, read in at this start, what it left on its native standard error. The GPU it chose is logged at
 *       startup, so it is in there too.</li>
 * </ul>
 *
 * <p><b>Anonymous, as far as text can be made so.</b> Every piece of text is {@linkplain Scrub scrubbed} of the home
 * directory, the user's name and the computer's name before it is written. That covers what names the person; it
 * cannot cover what the application itself chose to log (the name of a file it opened, under a folder outside home),
 * which is why the README says so, and the person can open the zip before sending it.
 */
final class BugReport {

    static final String README = "README.txt";
    static final String SYSTEM = "system.txt";
    static final String LOGS = "logs/";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private BugReport() {
    }

    /** The name offered in the save dialog: the application's, and when, so two reports never collide. */
    static String fileName(String app, Instant now) {
        return app + "-bug-report-" + STAMP.format(now.atZone(ZoneId.systemDefault())) + ".zip";
    }

    /** The log files worth sending, as {@code Logging} names them: the live file and the one before, when they exist. */
    static List<Path> logs(Path logFile) {
        List<Path> out = new ArrayList<>();
        if (logFile == null) {
            return out;
        }
        Path rolled = logFile.resolveSibling(logFile.getFileName() + ".1");
        for (Path p : new Path[] {rolled, logFile}) {
            if (Files.isRegularFile(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** What {@code system.txt} says: the build, then this machine, then the runs that did not close. */
    static String system(String about, Map<String, String> machine, List<UncleanExits.Ended> ended) {
        StringBuilder s = new StringBuilder(about);
        if (!about.endsWith("\n")) {
            s.append('\n');
        }
        s.append('\n');
        machine.forEach((k, v) -> s.append(k).append(": ").append(v).append('\n'));
        s.append("\nRuns that did not close:\n");
        if (ended.isEmpty()) {
            s.append("  none (this report was asked for without one)\n");
        }
        for (UncleanExits.Ended e : ended) {
            s.append("  process ").append(e.pid()).append(", started ")
                    .append(WHEN.format(e.started().atZone(ZoneId.systemDefault()))).append('\n');
        }
        return s.toString();
    }

    /** This machine, as a report wants it and with nothing that names it: insertion-ordered. */
    static Map<String, String> machine(String mode) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("Operating system", System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?"));
        m.put("Architecture", System.getProperty("os.arch", "?"));
        m.put("Processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        long max = Runtime.getRuntime().maxMemory();
        m.put("Memory available to the application", max == Long.MAX_VALUE ? "unbounded" : (max >> 20) + " MB");
        boolean nativeImage = "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
        m.put("Runtime", (nativeImage ? "native image, " : "JVM, ") + System.getProperty("java.vm.name", "?") + " "
                + System.getProperty("java.version", "?"));
        m.put("Log mode", mode);
        return m;
    }

    /** What {@code README.txt} says. {@code issues} is where the application takes reports, or empty. */
    static String readme(String name, String issues, List<String> logNames) {
        StringBuilder s = new StringBuilder();
        s.append("Bug report for ").append(name).append("\n\n");
        s.append("This file was saved by ").append(name).append(" after a run that stopped without closing normally.\n");
        s.append("Nothing was sent anywhere: attach this file to a report yourself if you want to.\n");
        if (!issues.isEmpty()) {
            s.append("Reports go to: ").append(issues).append('\n');
        }
        s.append("\nWhat is in it:\n");
        s.append("  ").append(SYSTEM).append("  the version and build of ").append(name)
                .append(", the operating system, processor count, memory and runtime\n");
        for (String log : logNames) {
            s.append("  ").append(LOGS).append(log).append("  what the application logged\n");
        }
        s.append("\nWhat was taken out: your home folder is written as ").append(Scrub.HOME)
                .append(", your user name as ").append(Scrub.USER).append(" and this computer's name as ")
                .append(Scrub.COMPUTER).append(".\n");
        s.append("The logs can still name files and folders the application worked with. Open them before you send\n");
        s.append("this if that matters to you.\n");
        return s.toString();
    }

    /** Write the zip to {@code out}: the README, the system, and each log scrubbed, as UTF-8. */
    static void write(Path out, String readme, String system, List<Path> logs, Scrub scrub) throws IOException {
        Path partial = out.resolveSibling(out.getFileName() + ".partial");
        try (OutputStream file = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(file)) {
            entry(zip, README, scrub.apply(readme));
            entry(zip, SYSTEM, scrub.apply(system));
            for (Path log : logs) {
                String text;
                try {
                    text = Files.readString(log, StandardCharsets.UTF_8);
                } catch (java.nio.charset.MalformedInputException e) {
                    text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                }
                entry(zip, LOGS + log.getFileName(), scrub.apply(text));
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        // Whole or not at all: a report cut short by a full disk is worse than none, since it reads as complete.
        Files.move(partial, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * <b>What names the person, taken out of text</b>: the home directory, the user's name and the computer's name, each
     * replaced by a placeholder.
     *
     * <p>Home first, since it contains the user's name, in either separator and in any case (Windows paths are not
     * case-sensitive, and logs write both separators). Then the user's name and the computer's name as whole words: a
     * name inside a longer word is not the name. Case-sensitive for the user's name, so a name that is also a word
     * ({@code User}) does not eat every sentence that says {@code user}; case-insensitive for the computer's, which is
     * written in either case and is rarely a word. A name shorter than three characters is left, since replacing it
     * would mangle the text and would hide little.
     */
    record Scrub(List<Pattern> patterns, List<String> replacements) {

        static final String HOME = "~";
        static final String USER = "<user>";
        static final String COMPUTER = "<computer>";

        /** For this process: {@code user.home}, {@code user.name}, and the computer's name from the environment. */
        static Scrub current() {
            String host = System.getenv("COMPUTERNAME");
            if (host == null || host.isBlank()) {
                host = System.getenv("HOSTNAME");
            }
            return of(System.getProperty("user.home"), System.getProperty("user.name"), host);
        }

        /** For these; any may be null, and is then not looked for. */
        static Scrub of(String home, String user, String computer) {
            List<Pattern> p = new ArrayList<>();
            List<String> r = new ArrayList<>();
            if (home != null && home.length() > 3) {
                String trimmed = home.replaceAll("[\\\\/]+$", "");
                // Not a longer name that starts with it: C:\Users\Ann is not the start of C:\Users\Anna.
                p.add(Pattern.compile(anySeparator(trimmed) + "(?![\\p{L}\\p{N}_])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                r.add(HOME);
            }
            if (user != null && user.length() >= 3) {
                p.add(Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(user) + "(?![\\p{L}\\p{N}_])"));
                r.add(USER);
            }
            if (computer != null && computer.length() >= 3) {
                p.add(Pattern.compile("(?<![\\p{L}\\p{N}_-])" + Pattern.quote(computer) + "(?![\\p{L}\\p{N}_-])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                r.add(COMPUTER);
            }
            return new Scrub(List.copyOf(p), List.copyOf(r));
        }

        /** {@code path} matched with either separator between its parts. */
        private static String anySeparator(String path) {
            StringBuilder regex = new StringBuilder();
            for (String part : path.split("[\\\\/]+", -1)) {
                if (!regex.isEmpty()) {
                    regex.append("[\\\\/]+");
                }
                regex.append(Pattern.quote(part));
            }
            // A run of separators, so a JSON log's escaped backslash (two of them) matches as well.
            return regex.toString();
        }

        String apply(String text) {
            String out = text;
            for (int i = 0; i < patterns.size(); i++) {
                out = patterns.get(i).matcher(out).replaceAll(Matcher.quoteReplacement(replacements.get(i)));
            }
            return out;
        }
    }
}

package dev.vexelray.framework.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StepCheck}: what is certainly wrong in a step's body is a compile error, and nothing else is.
 *
 * <p>Half of these are the refusals, one each, so each says what it is and how to fix it. The other half matter as
 * much: rule 4 of {@code docs/plans/chains.md} is that the check never refuses safe code, so a body that only looks
 * suspicious (a {@code java.io} type that touches no disk, a thread asked its name, a class of its own inside the
 * step, a call into an interface) must compile.
 */
class StepCheckTest {

    @TempDir
    Path temp;

    private static final String HEAD = """
            package app;
            import sibarum.atchung.*;
            import java.util.*;
            public final class Steps {
                public record Notes(List<String> lines) {
                    Notes with(String line) {
                        List<String> next = new ArrayList<>(lines);
                        next.add(line);
                        return new Notes(List.copyOf(next));
                    }
                    Notes add(String line, Out out) { return with(line); }
                }
                static final String PREFIX = "> ";
                static int counter;
                static volatile boolean flag;
                String field = "f";
                String own() { return "o"; }
            """;

    private List<String> errors(String body) throws IOException {
        String source = HEAD + body + "\n}\n";
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path out = Files.createDirectories(temp.resolve("out"));
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///app/Steps.java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path")), null, List.of(unit));
            task.setProcessors(List.of(new VexelProcessor()));
            task.call();
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d.getMessage(Locale.ROOT));
            }
        }
        return errors;
    }

    private void refused(String body, String fragment) throws IOException {
        List<String> errors = errors(body);
        assertEquals(1, errors.size(), () -> "expected one error containing \"" + fragment + "\", got:\n"
                + String.join("\n", errors));
        assertTrue(errors.get(0).contains(fragment), () -> "expected \"" + fragment + "\" in:\n" + errors.get(0));
    }

    private void accepted(String body) throws IOException {
        assertEquals(List.of(), errors(body));
    }

    // --- what is certainly wrong ---------------------------------------------------------------------------------

    @Test
    void aCapturedVariableIsRefusedAndToldToCarryIt() throws IOException {
        refused("""
                void build(Ledger.Builder<Notes> b) {
                    String suffix = "!";
                    b.step("add", (n, line, out) -> n.with(line + suffix));
                }
                """, "step 'add' captures 'suffix' from outside it");
    }

    @Test
    void theEnclosingObjectsFieldIsACaptureOfThis() throws IOException {
        refused("""
                void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> n.with(field));
                }
                """, "uses the field field of the enclosing object, so it captures it");
    }

    @Test
    void theEnclosingObjectsMethodIsACaptureOfThis() throws IOException {
        refused("""
                void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> n.with(own()));
                }
                """, "calls own() on the enclosing object");
    }

    @Test
    void aMethodReferenceOnAnObjectCapturesIt() throws IOException {
        refused("""
                Notes mine(Notes n, String line, Out out) { return n; }
                void build(Ledger.Builder<Notes> b) {
                    b.step("add", this::mine);
                }
                """, "is a method reference on an object, this, so it captures it");
    }

    @Test
    void aStaticFieldWrittenIsRefused() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> { counter++; return n; });
                }
                """, "writes the static field app.Steps.counter");
    }

    @Test
    void aStaticFieldThatIsNotFinalIsRefusedWhenRead() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> n.with(line + counter));
                }
                """, "reads the static field app.Steps.counter, which is not final");
    }

    @Test
    void aVolatileFieldIsRefused() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> flag ? n.with(line) : n);
                }
                """, "uses the volatile field app.Steps.flag");
    }

    @Test
    void aLockIsRefused() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> { synchronized (n) { return n.with(line); } });
                }
                """, "takes a lock (synchronized)");
    }

    @Test
    void theDiskIsRefusedAndToldToUseAJob() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("load", (n, file, out) -> {
                        try {
                            return n.with(java.nio.file.Files.readString(java.nio.file.Path.of(file)));
                        } catch (java.io.IOException e) {
                            return n;
                        }
                    });
                }
                """, "calls Files.readString, which does I/O. A step sees only its state, its arguments and its Out "
                + "(docs/plans/chains.md, design F): a step makes no effects and never waits: send a message to a @Job");
    }

    @Test
    void theProcessStreamsAreRefused() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> { System.out.println(line); return n; });
                }
                """, "uses java.lang.System.out, the process's own stream");
    }

    @Test
    void sleepingIsRefused() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> {
                        try { Thread.sleep(5); } catch (InterruptedException e) { }
                        return n;
                    });
                }
                """, "calls Thread.sleep, which blocks its thread");
    }

    @Test
    void startingAThreadIsRefused() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> { new Thread(); return n; });
                }
                """, "calls new Thread, which starts work on another thread");
    }

    @Test
    void waitingOnAFutureIsRefusedThroughItsSupertype() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<java.util.concurrent.CompletableFuture<String>>step("add", (n, f, out) -> {
                        try { return n.with(f.get()); } catch (Exception e) { return n; }
                    });
                }
                """, "calls CompletableFuture.get, which blocks its thread");
    }

    @Test
    void endingTheProcessIsRefused() throws IOException {
        refused("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("quit", (n, line, out) -> { System.exit(0); return n; });
                }
                """, "calls System.exit, which ends the process");
    }

    @Test
    void aHelperIsReadThroughAndReportedOnceForTwoSteps() throws IOException {
        refused("""
                static String load(String file) {
                    try {
                        return java.nio.file.Files.readString(java.nio.file.Path.of(file));
                    } catch (java.io.IOException e) {
                        return "";
                    }
                }
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("one", (n, file, out) -> n.with(load(file)));
                    b.<String>step("two", (n, file, out) -> n.with(load(file)));
                }
                """, "step 'one' calls Files.readString");
    }

    @Test
    void aStepWrittenAsAStaticMethodIsReadThrough() throws IOException {
        refused("""
                static Notes add(Notes n, String line, Out out) { counter = 1; return n; }
                static void build(Ledger.Builder<Notes> b) {
                    b.step("add", Steps::add);
                }
                """, "step 'add' writes the static field app.Steps.counter");
    }

    // --- what is safe, which must compile ------------------------------------------------------------------------

    @Test
    void stateArgumentsOutConstantsAndItsOwnLocalsAreFine() throws IOException {
        accepted("""
                static final sibarum.atchung.Topic<String> SAID = sibarum.atchung.Topic.of("said", String.class);
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> {
                        String shown = PREFIX + line.trim();
                        List<String> parts = new ArrayList<>();
                        for (String p : shown.split(" ")) {
                            parts.add(p.toUpperCase(Locale.ROOT));
                        }
                        parts.removeIf(p -> p.isEmpty());
                        out.send(SAID, shown);
                        out.result(parts.size());
                        return n.with(String.join(" ", parts));
                    });
                }
                """);
    }

    @Test
    void aStaticOrUnboundMethodReferenceIsFine() throws IOException {
        accepted("""
                static Notes add(Notes n, String line, Out out) { return n.with(line); }
                static void build(Ledger.Builder<Notes> b) {
                    b.step("add", Steps::add);
                    b.step("own", Notes::add);
                }
                """);
    }

    @Test
    void javaIoThatTouchesNoDiskIsFine() throws IOException {
        accepted("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> {
                        java.io.StringWriter w = new java.io.StringWriter();
                        w.write(line);
                        java.io.File f = new java.io.File(line);
                        return n.with(w + f.getName());
                    });
                }
                """);
    }

    @Test
    void askingTheThreadItsNameIsFine() throws IOException {
        accepted("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> n.with(Thread.currentThread().getName()));
                }
                """);
    }

    @Test
    void aClassOfItsOwnInsideTheStepHasItsOwnThis() throws IOException {
        accepted("""
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> {
                        Comparator<String> byLength = new Comparator<>() {
                            final int bias = 1;
                            public int compare(String x, String y) { return x.length() - y.length() + bias - bias; }
                        };
                        List<String> sorted = new ArrayList<>(n.lines());
                        sorted.sort(byLength);
                        return new Notes(List.copyOf(sorted));
                    });
                }
                """);
    }

    @Test
    void aRecordWithACompactConstructorStillCompiles() throws IOException {
        // The check once read bodies while annotations were processed, which made javac attribute early and left a
        // compact constructor with a super() call javac then refused as the source's. It reads after analysis now.
        accepted("""
                public record Lines(List<String> all) {
                    public Lines {
                        all = List.copyOf(all);
                    }
                }
                static void build(Ledger.Builder<Notes> b) {
                    b.<String>step("add", (n, line, out) -> n.with(new Lines(List.of(line)).all().get(0)));
                }
                """);
    }

    @Test
    void whatTheCheckCannotReadIsLetThrough() throws IOException {
        accepted("""
                static void build(Ledger.Builder<Notes> b, Step.Body<Notes, String> made) {
                    b.<java.util.function.Supplier<String>>step("add", (n, s, out) -> n.with(s.get()));
                    b.step("made", made);
                }
                """);
    }
}

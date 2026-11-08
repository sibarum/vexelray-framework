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
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Withers}: the processor writes the interface of withers a ledger's state record implements (VexelRay IDE v0.2
 * FN-10), and refuses the records it cannot write one for.
 */
class WithersTest {

    @TempDir
    Path temp;

    private Path out;

    private List<String> errors(String source) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        out = Files.createDirectories(temp.resolve("out"));
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///app/Editor.java"),
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

    private void refused(String source, String fragment) throws IOException {
        List<String> errors = errors(source);
        assertEquals(1, errors.size(), () -> "expected one error containing \"" + fragment + "\", got:\n"
                + String.join("\n", errors));
        assertTrue(errors.get(0).contains(fragment), () -> "expected \"" + fragment + "\" in:\n" + errors.get(0));
    }

    private static final String EDITOR = """
            package app;
            import dev.vexelray.framework.api.Withers;
            import java.util.*;
            public final class Editor {
                @Withers
                record State(String doc, long ids, Set<String> reading, Map<Long, List<String>> closings,
                             Optional<String> front, boolean armed) implements EditorStateWithers {
                    State {
                        reading = Set.copyOf(reading);
                    }
                    State withNextId() {
                        return withIds(ids + 1);
                    }
                    // Hand-written, with a wither's name and parameter: it wins over the generated one.
                    public State withArmed(boolean value) {
                        return new State(doc, ids, reading, closings, front, !value);
                    }
                }
                public static Object initial() {
                    return new State("", 0, new HashSet<>(), Map.of(), Optional.empty(), false);
                }
            }
            """;

    @Test
    void aNestedRecordGetsOneWitherPerComponentThroughItsConstructor() throws Exception {
        assertEquals(List.of(), errors(EDITOR));
        String written = Files.readString(generated("app/EditorStateWithers.java"));
        assertTrue(written.contains("default app.Editor.State withDoc(java.lang.String value)"), written);
        assertTrue(written.contains("return new app.Editor.State(doc(), value, reading(), closings(), front(), armed());"),
                written);

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{out.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> editor = loader.loadClass("app.Editor");
            Object state = editor.getMethod("initial").invoke(null);
            Class<?> type = state.getClass();
            Object named = call(state, "withDoc", String.class, "notes.md");
            assertNotSame(state, named);
            assertEquals("notes.md", call(named, "doc", null, null));
            assertEquals(0L, call(named, "ids", null, null), "every other component carried over");

            Object next = call(named, "withNextId", null, null);
            assertEquals(1L, call(next, "ids", null, null), "a hand-written step on a generated wither");

            Object reading = call(next, "withReading", Set.class, new java.util.HashSet<>(Set.of("a")));
            Set<?> set = (Set<?>) call(reading, "reading", null, null);
            assertThrows(UnsupportedOperationException.class, () -> set.clear(),
                    "the compact constructor ran: the set was copied");

            Object armed = call(reading, "withArmed", boolean.class, true);
            assertEquals(false, call(armed, "armed", null, null), "the record's own withArmed overrides");
        }
    }

    /** A method of the package-private record, or the default it inherits from its interface. */
    private static Object call(Object target, String name, Class<?> param, Object arg) throws Exception {
        Class<?>[] params = param == null ? new Class<?>[0] : new Class<?>[]{param};
        Method m;
        try {
            m = target.getClass().getDeclaredMethod(name, params);
        } catch (NoSuchMethodException inherited) {
            m = target.getClass().getMethod(name, params);
        }
        m.setAccessible(true);
        return param == null ? m.invoke(target) : m.invoke(target, arg);
    }

    private Path generated(String relative) throws IOException {
        // javac writes generated sources beside the classes when no -s is given.
        try (var walk = Files.walk(out)) {
            return walk.filter(p -> p.toString().replace('\\', '/').endsWith(relative)).findFirst()
                    .orElseThrow(() -> new AssertionError(relative + " was not written"));
        }
    }

    @Test
    void aTopLevelPublicRecordGetsAPublicInterfaceNamedForItAlone() throws Exception {
        assertEquals(List.of(), errors("""
                package app;
                @dev.vexelray.framework.api.Withers
                public record Editor(int a, int b) implements EditorWithers {
                    public Editor bumped() { return withB(b + 1); }
                }
                """));
        String written = Files.readString(generated("app/EditorWithers.java"));
        assertTrue(written.contains("public interface EditorWithers"), written);
    }

    @Test
    void aStepCallingAWitherPassesTheStepCheck() throws IOException {
        assertEquals(List.of(), errors("""
                package app;
                import dev.vexelray.framework.api.Withers;
                import sibarum.atchung.*;
                public final class Editor {
                    @Withers
                    record State(long ids, String doc) implements EditorStateWithers {}
                    static void build(Ledger.Builder<State> b) {
                        b.<String>step("open", (s, doc, out) -> s.withDoc(doc).withIds(s.ids() + 1));
                    }
                }
                """));
    }

    @Test
    void aRecordThatDoesNotImplementItsInterfaceIsToldWhatToWrite() throws IOException {
        refused("""
                package app;
                public final class Editor {
                    @dev.vexelray.framework.api.Withers
                    record State(long ids) {}
                }
                """, "Add `implements EditorStateWithers` to the record");
    }

    @Test
    void aClassIsRefused() throws IOException {
        refused("""
                package app;
                @dev.vexelray.framework.api.Withers
                public final class Editor {}
                """, "which is not a record");
    }

    @Test
    void aPrivateRecordIsRefused() throws IOException {
        refused("""
                package app;
                public final class Editor {
                    @dev.vexelray.framework.api.Withers
                    private record State(long ids) implements EditorStateWithers {}
                }
                """, "it is private");
    }

    @Test
    void aRecordInsideAPrivateTypeIsRefused() throws IOException {
        refused("""
                package app;
                public final class Editor {
                    private static final class Inner {
                        @dev.vexelray.framework.api.Withers
                        record State(long ids) implements EditorInnerStateWithers {}
                    }
                }
                """, "Inner, which encloses it, is private");
    }

    @Test
    void aGenericRecordIsRefused() throws IOException {
        refused("""
                package app;
                @dev.vexelray.framework.api.Withers
                public record Editor<T>(T value) implements EditorWithers {}
                """, "generic record");
    }
}

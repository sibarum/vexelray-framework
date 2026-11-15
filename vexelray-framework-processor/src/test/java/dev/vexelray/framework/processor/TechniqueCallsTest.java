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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A technique is declared, never called (T7.6, {@link TechniqueCalls}): {@code realize}, {@code record} or
 * {@code close} called on one from application code is a compile error, however the technique is held, and a technique
 * calling its parts, or anyone reading its declarations, compiles. vexelray's engine API is not on this module's path,
 * so {@code RenderTechnique} is a stand-in of the same name, compiled beside the code.
 */
class TechniqueCallsTest {

    @TempDir
    Path temp;

    private static final Map<String, String> ENGINE = Map.of(
            "dev/vexelray/engine/RenderTechnique", """
                    package dev.vexelray.engine;
                    public interface RenderTechnique extends AutoCloseable {
                        void realize(Object ctx);
                        void record(Object frame);
                        void close();
                        default java.util.List<String> bindings() { return java.util.List.of(); }
                    }
                    """,
            "app/March", """
                    package app;
                    public final class March implements dev.vexelray.engine.RenderTechnique {
                        public void realize(Object ctx) {}
                        public void record(Object frame) {}
                        public void close() {}
                        public March camera(float x) { return this; }
                    }
                    """);

    @Test
    void recordingATechniqueFromApplicationCodeIsRefused() throws IOException {
        refused("""
                package app;
                public final class Viewport {
                    final March march = new March();
                    void frame() { march.record(null); }
                }
                """, "Viewport calls March.record()");
    }

    @Test
    void closingOneHeldAsTheInterfaceIsRefusedToo() throws IOException {
        refused("""
                package app;
                import dev.vexelray.engine.RenderTechnique;
                public final class Viewport {
                    final RenderTechnique t = new March();
                    void done() { t.close(); }
                }
                """, "Viewport calls RenderTechnique.close()");
    }

    @Test
    void aNestedHelperIsCheckedAsItsOuterClassIs() throws IOException {
        refused("""
                package app;
                public final class Viewport {
                    static final class Helper {
                        void go(March m) { m.realize(null); }
                    }
                }
                """, "calls March.realize()");
    }

    @Test
    void aTechniqueCallingItsPartsAndCodeReadingDeclarationsCompile() throws IOException {
        assertEquals(List.of(), compile("""
                package app;
                public final class Both implements dev.vexelray.engine.RenderTechnique {
                    final March sky = new March();
                    final March ground = new March();
                    public void realize(Object ctx) { sky.realize(ctx); ground.realize(ctx); }
                    public void record(Object frame) { sky.record(frame); ground.record(frame); }
                    public void close() { sky.close(); ground.close(); }
                    static int declared(March m) { m.camera(1f); return m.bindings().size(); }
                }
                """));
    }

    /**
     * The check reads a class after javac has analyzed it. Read while annotations were processed, it made javac
     * attribute the record early, and javac's own pass then refused its compact constructor (Rasterware's FN-40).
     */
    @Test
    void aRecordWithACompactConstructorCompiles() throws IOException {
        assertEquals(List.of(), compile("""
                package app;
                public final class Messages {
                    public record Filmstrip(java.util.List<String> paths, int at) {
                        public Filmstrip {
                            paths = java.util.List.copyOf(paths);
                        }
                    }
                    static int shown(March m) { return new Filmstrip(java.util.List.of("a"), 0).paths().size(); }
                }
                """));
    }

    private void refused(String source, String fragment) throws IOException {
        List<String> errors = compile(source);
        assertEquals(1, errors.size(), () -> "expected one error containing \"" + fragment + "\", got:\n"
                + String.join("\n", errors));
        assertTrue(errors.get(0).contains(fragment), () -> "expected \"" + fragment + "\" in:\n" + errors.get(0));
        assertTrue(errors.get(0).contains("TechniqueView"), "it says what to do instead");
    }

    private List<String> compile(String source) throws IOException {
        String path = "app/" + source.split("public final class ")[1].split("[ {]")[0];
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path out = Files.createDirectories(temp.resolve("out"));
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            List<JavaFileObject> units = new ArrayList<>();
            units.add(unit(path, source));
            ENGINE.forEach((name, code) -> units.add(unit(name, code)));
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:full"), null, units);
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

    private static JavaFileObject unit(String path, String code) {
        return new SimpleJavaFileObject(URI.create("string:///" + path + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }
}

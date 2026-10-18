package dev.vexelray.framework.shell;

import dev.vexelray.framework.processor.VexelProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.probe.Level;
import sibarum.probe.Logging;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Provides}' promise, end to end: an application compiled by the real processor against the real
 * {@code -shell}, built headless by {@link VexelApplication#tree}. A provider answering null is reported once, at
 * {@code WARN}, naming what was built with the null — and the null is still what those parts were given.
 *
 * <p>The processor's own tests check the generated call against stand-ins; this is where the call meets the logger.
 * Reflection is a test's privilege: the wiring is loaded from a class directory the test compiled.
 */
final class ProvidedAbsenceTest {

    private static final String APP = """
            package app;
            import dev.vexelray.framework.api.*;
            @VexelApp(name = "absent", title = "Absent")
            public final class DemoApp {}
            """;

    private static final String RECIPES = """
            package app;
            import dev.vexelray.framework.api.*;
            final class Backend {}
            final class User {
                static Backend given = new Backend();
                User(Backend backend, dev.vexelray.gui.core.Gui gui) { given = backend; }
            }
            @Configuration
            final class Recipes {
                @Provides Backend backend() { return null; }
                @Provides User user(Backend backend, dev.vexelray.gui.core.Gui gui) { return new User(backend, gui); }
                @Provides dev.vexelray.framework.shell.InputBackend input() { return null; }
            }
            """;

    @Test
    void aProviderAnsweringNullIsReportedOnceAndItsNullPassedOn(@TempDir Path out) throws Exception {
        Path classes = compile(out, Map.of("app.DemoApp", APP, "app.Recipes", RECIPES));
        System.setProperty("absent.home", out.resolve("home").toString());
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                ProvidedAbsenceTest.class.getClassLoader());
             Logging.Capture log = Logging.capture()) {
            var ctor = loader.loadClass("app.DemoAppWiring").getDeclaredConstructor();
            ctor.setAccessible(true);
            Shell shell = VexelApplication.tree((Wiring) ctor.newInstance(), new String[0]);
            try {
                var given = loader.loadClass("app.User").getDeclaredField("given");
                given.setAccessible(true);
                assertEquals(null, given.get(null),
                        "the User, built at TREE, was handed the null: absence is passed on, not refused");
                List<String> reports = new ArrayList<>();
                for (Logging.Entry e : log.records()) {
                    if (e.logger().equals("framework.wiring")) {
                        assertEquals(Level.WARN, e.level(), e.message());
                        reports.add(e.message());
                    }
                }
                assertEquals(2, reports.size(), "once per provider: " + reports);
                assertTrue(reports.contains("Recipes.backend provided no Backend: absent. Built with the null:"
                        + " Recipes.user."), reports.toString());
                assertTrue(reports.contains("Recipes.input provided no InputBackend: absent, so the framework keeps"
                        + " its own. Nothing is built with it."), reports.toString());
            } finally {
                shell.disposer().close();
            }
        } finally {
            System.clearProperty("absent.home");
        }
    }

    private static Path compile(Path out, Map<String, String> sources) throws Exception {
        Path classes = Files.createDirectories(out.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                URI uri = URI.create("string:///" + e.getKey().replace('.', '/') + JavaFileObject.Kind.SOURCE.extension);
                String code = e.getValue();
                units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return code;
                    }
                });
            }
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path")), null, units);
            task.setProcessors(List.of(new VexelProcessor()));
            task.call();
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d.getMessage(Locale.ROOT));
            }
        }
        assertEquals(List.of(), errors);
        return classes;
    }
}

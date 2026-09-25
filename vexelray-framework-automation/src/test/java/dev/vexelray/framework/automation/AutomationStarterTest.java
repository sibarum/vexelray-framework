package dev.vexelray.framework.automation;

import dev.vexelray.framework.processor.VexelProcessor;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The starter as an application meets it: compiled against the real {@code -shell} with the real processor, off
 * the starter's own class file.
 *
 * <p>The processor's own tests use stand-ins for {@code Shell} and its neighbours, because the processor cannot
 * depend on {@code -shell}. That leaves exactly this unexercised — a starter in another jar, read through its
 * {@code CLASS}-retention annotations, generating a wiring that has to compile against the {@code Shell} that
 * actually ships. So it is asserted here, where all three are on one classpath.
 */
final class AutomationStarterTest {

    private static final String APP = """
            package app;
            import dev.vexelray.framework.api.*;
            import dev.vexelray.framework.automation.AutomationStarter;
            @VexelApp(name = "demo", title = "Demo", starters = AutomationStarter.class)
            public final class DemoApp {}
            """;

    @Test
    void namingTheStarterBuildsTheDriverLastAndClosesItAtShutdown(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP));
        assertEquals(List.of(), compiled.errors());

        String wiring = compiled.wiring();
        // ATTACH, inferred from the Shell parameter: the driver needs the window's real controls.
        String attach = wiring.substring(wiring.indexOf("public void attach("));
        assertTrue(attach.contains("driver = automationStarter.driver(shell);"), wiring);
        assertTrue(attach.contains("shell.disposer().register(driver);"), wiring);
    }

    @Test
    void anApplicationsOwnDriverBacksTheStartersOff(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP, "app.Recipes", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.framework.automation.Driver;
                @Configuration
                final class Recipes {
                    @Provides Driver driver(dev.vexelray.framework.shell.Shell shell) { return Driver.open(shell); }
                }
                """));
        assertEquals(List.of(), compiled.errors(), "a @Default is backed off, not in conflict");

        String wiring = compiled.wiring();
        assertTrue(wiring.contains("recipes.driver(shell)"), wiring);
        assertFalse(wiring.contains("automationStarter"), wiring);
    }

    @Test
    void anApplicationThatDoesNotNameTheStarterIsNotDriven(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", """
                package app;
                @dev.vexelray.framework.api.VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """));
        assertEquals(List.of(), compiled.errors());
        assertFalse(compiled.wiring().contains("Driver"), compiled.wiring());
    }

    // --- harness --------------------------------------------------------------------------------------------

    private record Compiled(List<String> errors, Path sources) {

        String wiring() throws IOException {
            return Files.readString(sources.resolve("app/DemoAppWiring.java"));
        }
    }

    private static Compiled compile(Path out, Map<String, String> sources) throws IOException {
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            files.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                units.add(source(e.getKey(), e.getValue()));
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
        return new Compiled(errors, generated);
    }

    private static JavaFileObject source(String fqcn, String code) {
        URI uri = URI.create("string:///" + fqcn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension);
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }
}

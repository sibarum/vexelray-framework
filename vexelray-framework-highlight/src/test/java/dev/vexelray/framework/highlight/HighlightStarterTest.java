package dev.vexelray.framework.highlight;

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
 * The starter as an application meets it: off its own class file, read by the real processor, generating a wiring
 * that has to compile against the real shell. The same arrangement as {@code AutomationStarterTest} and the codec's
 * {@code ImageStarterTest}, and the three ways an application has its say: its colours, its languages, or all of it.
 */
final class HighlightStarterTest {

    private static final String APP = """
            package app;
            import dev.vexelray.framework.api.*;
            import dev.vexelray.framework.highlight.HighlightStarter;
            @VexelApp(name = "demo", title = "Demo", starters = HighlightStarter.class)
            public final class DemoApp {}
            """;

    @Test
    void namingTheStarterBuildsHighlightingFirstInTheStandardColoursWithNoLanguagesOfItsOwn(@TempDir Path out)
            throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP));
        assertEquals(List.of(), compiled.errors());

        String wiring = compiled.wiring();
        assertTrue(wiring.contains("highlightStarter.colours()"), wiring);
        assertTrue(wiring.contains("highlightStarter.highlighting(colours, dev.vexelray.framework.api.All.<dev.vexelray.framework.highlight.Language>of())"),
                wiring);
        // Built from nothing the framework makes, so in the first phase, before the Gui.
        String config = wiring.substring(wiring.indexOf("public void config("));
        int end = config.indexOf("public void ", 1);
        config = end < 0 ? config : config.substring(0, end);
        assertTrue(config.contains("highlightStarter.highlighting("), "in the first phase, config:\n" + wiring);
    }

    @Test
    void anApplicationsColoursBackTheStandardTableOff(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP, "app.Recipes", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.framework.highlight.*;
                @Configuration
                final class Recipes {
                    @Provides Colours colours() {
                        return Colours.of(Colour.of("keyword", dev.vexelray.canvas.Color.rgb(0x8aa9f0)));
                    }
                }
                """));
        assertEquals(List.of(), compiled.errors(), "a @Default is backed off, not in conflict");

        String wiring = compiled.wiring();
        assertTrue(wiring.contains("recipes.colours()"), wiring);
        assertFalse(wiring.contains("highlightStarter.colours()"), wiring);
        assertTrue(wiring.contains("highlightStarter.highlighting("), "the starter still builds the part:\n" + wiring);
    }

    /** Each language is provided where it is written, the -core module's configuration included, and none names another. */
    @Test
    void everyLanguageTheApplicationProvidesIsOneMore(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP, "app.Recipes", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.framework.highlight.*;
                @Configuration
                final class Recipes {
                    @Provides Language toy() {
                        return Language.lexed("source.toy", "Toy", (text, tokens) -> tokens.token(0, 1, "keyword.toy"))
                                .extensions("toy");
                    }
                    @Provides Language ledger() {
                        return Language.lexed("source.ledger", "Ledger", (text, tokens) -> { }).extensions("ledger");
                    }
                }
                """));
        assertEquals(List.of(), compiled.errors(), "two providers of a type taken as All<T> are a set, not a conflict");

        String wiring = compiled.wiring();
        assertTrue(wiring.contains("dev.vexelray.framework.api.All.<dev.vexelray.framework.highlight.Language>of(ledger, toy)"), wiring);
    }

    @Test
    void anApplicationsOwnHighlightingBacksTheStarterOff(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP, "app.Recipes", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.framework.highlight.*;
                @Configuration
                final class Recipes {
                    @Provides Highlighting highlighting() {
                        return Highlighting.of(Colours.standard(), java.util.List.of());
                    }
                }
                """));
        assertEquals(List.of(), compiled.errors());

        String wiring = compiled.wiring();
        assertTrue(wiring.contains("recipes.highlighting()"), wiring);
        assertFalse(wiring.contains("highlightStarter.highlighting("), wiring);
    }

    /** The case the starter exists for: a job that colours a document, two requests at once, takes the part. */
    @Test
    void aJobTakesHighlighting(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.framework.highlight.HighlightStarter;
                @VexelApp(name = "demo", title = "Demo", starters = HighlightStarter.class)
                @Configuration(jobs = Mark.class)
                public final class DemoApp {}
                """, "app.Ask", """
                package app;
                public record Ask(String scope, String text) {
                    public static final sibarum.atchung.Topic<Ask> TOPIC = sibarum.atchung.Topic.edge(Ask.class);
                }
                """, "app.Marked", """
                package app;
                public record Marked(java.util.List<dev.vexelray.gui.core.text.Span> spans) {
                    public static final sibarum.atchung.Topic<Marked> TOPIC = sibarum.atchung.Topic.edge(Marked.class);
                }
                """, "app.Mark", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.framework.highlight.Highlighting;
                @Job
                public final class Mark {
                    private final Highlighting highlighting;
                    public Mark(Highlighting highlighting) { this.highlighting = highlighting; }
                    public Marked run(Ask ask) {
                        return new Marked(highlighting.spans(ask.scope(), ask.text()));
                    }
                }
                """, "app.Asker", """
                package app;
                import dev.vexelray.framework.api.*;
                @Component @Publishes(Ask.class)
                public final class Asker {
                    @Subscribe public void marked(Marked m) {}
                }
                """));
        assertEquals(List.of(), compiled.errors());
        assertTrue(compiled.wiring().contains("new app.Mark(highlighting)"), compiled.wiring());
    }

    /**
     * A component does not take it: the compiled grammars are state under a lock, not a value, and a provided value
     * reaches every part that names it. The processor says so (T2.6), which is what Highlighting's class comment
     * promises, so it is held here.
     */
    @Test
    void aComponentIsToldToReachItByAMessage(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP, "app.Holder", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.framework.highlight.Highlighting;
                @Component
                public final class Holder {
                    public Holder(Highlighting highlighting) { }
                }
                """, "app.Parts", """
                package app;
                @dev.vexelray.framework.api.Configuration(components = Holder.class)
                final class Parts {}
                """));
        assertEquals(1, compiled.errors().size(), String.join("\n", compiled.errors()));
        assertTrue(compiled.errors().get(0).contains("T2.6"), compiled.errors().get(0));
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

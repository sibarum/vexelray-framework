package dev.vexelray.framework.processor;

import org.junit.jupiter.api.BeforeAll;
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
import java.io.File;
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
 * Compiles fixtures with the real {@code javac} and reads what the processor said.
 *
 * <p><b>Two compilations, not one.</b> A library is compiled first, with no processor, into a directory the
 * application then compiles against — so the checks that read an annotation off a <em>class file</em> are
 * exercised on one. That is the case {@code RetentionPolicy.CLASS} was chosen for, and the case the
 * interface rule turns on: {@code lib.Device} is a {@code final} class from another jar and may be provided as
 * one; the application's own concrete class may not.
 *
 * <p>Each failing fixture asserts on its <em>only</em> error, so a check that fires twice, or a second check
 * that fires beside the intended one, fails the test rather than hiding behind a {@code contains}.
 */
class VexelProcessorTest {

    /** Another jar: a main-thread type, a final class, and a starter with a default the app overrides. */
    private static final Map<String, String> LIB = Map.of(
            "lib.Window", """
                package lib;
                @dev.vexelray.framework.api.MainThread
                public final class Window {}
                """,
            "lib.Device", """
                package lib;
                public final class Device {}
                """,
            "lib.Clock", """
                package lib;
                public interface Clock {}
                """,
            "lib.Starter", """
                package lib;
                import dev.vexelray.framework.api.*;
                @Configuration
                public final class Starter {
                    @Default @Provides public Clock clock() { return null; }
                    @Default @Provides public Device device() { return null; }
                }
                """);

    private static final String IMPORTS = """
            package app;
            import dev.vexelray.framework.api.*;
            import lib.*;
            """;

    @TempDir
    static Path temp;

    private static Path lib;

    @BeforeAll
    static void compileTheLibrary() throws IOException {
        lib = Files.createDirectories(temp.resolve("lib"));
        List<String> errors = compile(LIB, lib, null, false);
        assertEquals(List.of(), errors, "the library itself must compile");
    }

    // --- a correct application -------------------------------------------------------------------------------

    @Test
    void aCorrectApplicationCompilesWithNoErrors() {
        assertEquals(List.of(), app(
                """
                @VexelApp(name = "demo", title = "Demo", starters = Starter.class)
                public final class DemoApp {}
                """,
                """
                public interface Store {}
                """,
                """
                public interface Surface {}
                """,
                """
                public interface Pen {}
                """,
                """
                @Configuration
                public final class AppConfig {
                    @Provides public Store store(@Setting(value = "store.size", def = "4") int size) { return null; }
                    // Backs lib.Starter's @Default off, in every mode.
                    @Provides public Clock clock() { return null; }
                    // A final class from another jar, which the application cannot give an interface to.
                    @Provides public Device device(@Setting("device.name") String name) { return null; }
                    // A main-thread value into a main-thread value.
                    @MainThread @Provides public Surface surface(Window window) { return null; }
                    // Two providers for one type, in disjoint modes: they never meet.
                    @OnMode(RunMode.WINDOWED) @Provides public Pen windowedPen() { return null; }
                    @OnMode(RunMode.FRAMES) @Provides public Pen scriptedPen() { return null; }
                    // A guard that fails is code that does not exist, so it cannot conflict.
                    @ConditionalOnType("does.not.Exist") @Provides public Store ghost() { return null; }
                }
                """,
                """
                @Component(lane = "compose")
                public final class Composer {
                    public Composer(Store store, Helper helper,
                                    @Setting(value = "compose.fast", def = "true") boolean fast,
                                    @Setting(value = "compose.recent") java.util.List<String> recent) {}
                }
                """,
                """
                @Component(lane = "compose")
                public final class Helper {}
                """,
                """
                public final class Hooks {
                    @BeforeFrame void drain() {}
                }
                """));
    }

    // --- the colour rule ------------------------------------------------------------------------------------

    @Test
    void aComponentTakingAMainThreadTypeIsAnErrorNamingBoth() {
        onlyError(app("""
                @MainThread public final class Target {}
                """, """
                @Component(lane = "compose")
                public final class Composer { public Composer(Target target) {} }
                """), "T2.2: Composer takes target, a main-thread value (Target is @MainThread)");
    }

    @Test
    void mainThreadIsReadOffACompiledType() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer { public Composer(Window window) {} }
                """), "T2.2: Composer takes window, a main-thread value (Window is @MainThread)");
    }

    @Test
    void aMainThreadProviderColoursAForeignTypeItReturns() {
        onlyError(app("""
                @Configuration
                public final class AppConfig {
                    @MainThread @Provides public Device device() { return null; }
                }
                """, """
                @Component(lane = "compose")
                public final class Composer { public Composer(Device device) {} }
                """), "T2.2: Composer takes device, a main-thread value (AppConfig.device provides it @MainThread)");
    }

    @Test
    void aProviderMayPassAMainThreadValueOnlyIntoAMainThreadOne() {
        onlyError(app("""
                public interface Surface {}
                """, """
                @Configuration
                public final class AppConfig {
                    @Provides public Surface surface(Window window) { return null; }
                }
                """), "T2.2: AppConfig.surface takes window");
    }

    @Test
    void aComponentIsNotAlsoMainThread() {
        onlyError(app("""
                @MainThread @Component(lane = "compose")
                public final class Composer {}
                """), "T2.1: Composer is both a @Component and @MainThread");
    }

    @Test
    void componentsOnDifferentLanesMayNotHoldEachOther() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer { public Composer(Indexer indexer) {} }
                """, """
                @Component(lane = "index")
                public final class Indexer {}
                """), "T2.3: Composer (lane \"compose\") holds Indexer (lane \"index\")");
    }

    @Test
    void aMisspelledLaneDeniesAReferenceAndNamesBothSpellings() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer { public Composer(Helper helper) {} }
                """, """
                @Component(lane = "compse")
                public final class Helper {}
                """), "(lane \"compose\") holds Helper (lane \"compse\")");
    }

    @Test
    void aProviderMayNotHoldAComponent() {
        onlyError(app("""
                public interface Store {}
                """, """
                @Component(lane = "compose")
                public final class Composer {}
                """, """
                @Configuration
                public final class AppConfig {
                    @Provides public Store store(Composer composer) { return null; }
                }
                """), "T3.7: AppConfig.store takes the component Composer");
    }

    // --- one provider per type ------------------------------------------------------------------------------

    @Test
    void twoProvidersForOneTypeAreAnErrorNamingBoth() {
        onlyError(app("""
                @Configuration
                public final class AppConfig {
                    @Provides public Clock one() { return null; }
                    @Provides public Clock two() { return null; }
                }
                """), "Two providers, and neither is a @Default: AppConfig.one and AppConfig.two both provide"
                + " lib.Clock in WINDOWED");
    }

    @Test
    void overlappingModesStillConflictInTheModeTheyShare() {
        onlyError(app("""
                @Configuration
                public final class AppConfig {
                    @OnMode({RunMode.WINDOWED, RunMode.FRAMES}) @Provides public Clock one() { return null; }
                    @OnMode(RunMode.FRAMES) @Provides public Clock two() { return null; }
                }
                """), "both provide lib.Clock in FRAMES");
    }

    @Test
    void twoDefaultsWithNothingToBackThemOffConflict() {
        onlyError(app("""
                @Configuration
                public final class AppConfig {
                    @Default @Provides public Clock clock() { return null; }
                }
                """, """
                @VexelApp(name = "demo", title = "Demo", starters = Starter.class)
                public final class DemoApp {}
                """), "Two @Default providers");
    }

    @Test
    void aDefaultBackedOffInOneModeStillWinsInTheOther() {
        assertEquals(List.of(), app("""
                @Configuration
                public final class AppConfig {
                    @OnMode(RunMode.WINDOWED) @Provides public Clock clock() { return null; }
                }
                """, """
                @VexelApp(name = "demo", title = "Demo", starters = Starter.class)
                public final class DemoApp {}
                """));
    }

    // --- shapes ---------------------------------------------------------------------------------------------

    @Test
    void providingAConcreteClassOfTheApplicationsOwnIsAnError() {
        onlyError(app("""
                public final class Store {}
                """, """
                @Configuration
                public final class AppConfig {
                    @Provides public Store store() { return null; }
                }
                """), "returns Store, a concrete class of this application's own");
    }

    @Test
    void providingARecordIsTheSmellRatherThanTheException() {
        onlyError(app("""
                public record Size(int w, int h) {}
                """, """
                @Configuration
                public final class AppConfig {
                    @Provides public Size size() { return null; }
                }
                """), "returns Size, a record");
    }

    @Test
    void providingAPrimitiveIsAValue() {
        onlyError(app("""
                @Configuration
                public final class AppConfig {
                    @Provides public int size() { return 0; }
                }
                """), "returns int, which is a value");
    }

    @Test
    void aProviderOutsideAConfigurationIsAnError() {
        onlyError(app("""
                public final class Loose {
                    @Provides public Clock clock() { return null; }
                }
                """), "is not inside a @Configuration class");
    }

    @Test
    void aDefaultThatIsNotAProviderIsAnError() {
        onlyError(app("""
                public final class Loose {
                    @Default public void nothing() {}
                }
                """), "@Default on Loose.nothing, which is not a @Provides method");
    }

    @Test
    void aComponentWithTwoConstructorsIsAnError() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer {
                    public Composer() {}
                    Composer(int x) {}
                }
                """), "has 2 non-private constructors");
    }

    @Test
    void aRecordIsNotAComponent() {
        onlyError(app("""
                @Component(lane = "compose")
                public record Composer(int x) {}
                """), "@Component Composer is a record");
    }

    @Test
    void aBlankLaneIsAnError() {
        onlyError(app("""
                @Component(lane = " ")
                public final class Composer {}
                """), "has a blank lane");
    }

    @Test
    void aSettingWithNoAccessorIsAnError() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer { public Composer(@Setting(value = "ratio", def = "1") double r) {} }
                """), "Settings has no accessor for one");
    }

    @Test
    void anIntSettingWithNoDefaultDoesNotParse() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer { public Composer(@Setting("size") int size) {} }
                """), "def = \"\", which is not an int");
    }

    @Test
    void aBooleanDefaultIsTrueOrFalseAndNothingElse() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer { public Composer(@Setting(value = "fast", def = "yes") boolean f) {} }
                """), "which is neither true nor false");
    }

    @Test
    void aListSettingHasNoDefault() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer {
                    public Composer(@Setting(value = "recent", def = "a") java.util.List<String> recent) {}
                }
                """), "a list has no default");
    }

    @Test
    void aSettingOnAParameterNothingSuppliesIsAnError() {
        onlyError(app("""
                public final class Loose {
                    void set(@Setting(value = "name") String name) {}
                }
                """), "is not on a parameter the container supplies");
    }

    @Test
    void aFrameHookTakesNothingAndThrowsNothing() {
        List<String> errors = app("""
                public final class Hooks {
                    @BeforeFrame void drain(int n) throws java.io.IOException {}
                }
                """);
        assertEquals(2, errors.size(), () -> String.join("\n", errors));
        assertTrue(errors.get(0).contains("takes parameters") || errors.get(1).contains("takes parameters"));
        assertTrue(errors.get(0).contains("declares") || errors.get(1).contains("declares"));
    }

    @Test
    void aFrameHookIsNoComponentsBusiness() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer {
                    @BeforeFrame void drain() {}
                }
                """), "is on a @Component. The frame is the main thread's");
    }

    @Test
    void aStarterMustBeAConfiguration() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo", starters = Device.class)
                public final class DemoApp {}
                """), "names lib.Device as a starter, and it is not a @Configuration class");
    }

    @Test
    void thereIsOneApplication() {
        onlyError(app("""
                @VexelApp(name = "one", title = "One")
                public final class One {}
                """, """
                @VexelApp(name = "two", title = "Two")
                public final class Two {}
                """), "A second @VexelApp");
    }

    // --- registration ---------------------------------------------------------------------------------------

    /**
     * Found through its service file when named on the processor path, which is how an application's build
     * reaches it — rather than handed to the compiler as an instance, the way the other tests do.
     */
    @Test
    void theServiceFileNamesTheProcessor() throws IOException {
        Path out = Files.createTempDirectory(temp, "discovered");
        List<String> errors = compile(Map.of("app.Composer", IMPORTS + """
                @MainThread @Component(lane = "compose")
                public final class Composer {}
                """), out, lib, true);
        onlyError(errors, "T2.1");
    }

    // --- harness --------------------------------------------------------------------------------------------

    private static List<String> app(String... bodies) {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        for (String body : bodies) {
            sources.put("app." + typeName(body), IMPORTS + body);
        }
        try {
            return compile(sources, Files.createTempDirectory(temp, "app"), lib, false);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The declared type's name: the word after the first {@code class}, {@code interface} or {@code record}. */
    private static String typeName(String body) {
        String[] words = body.split("[\\s{(<]+");
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].equals("class") || words[i].equals("interface") || words[i].equals("record")) {
                return words[i + 1];
            }
        }
        throw new IllegalArgumentException("no type in " + body);
    }

    /**
     * Compiles {@code sources} into {@code out} and returns the error messages.
     *
     * @param against  a class directory to compile against, or {@code null}
     * @param discover find the processor through the processor path, rather than being handed one
     */
    private static List<String> compile(Map<String, String> sources, Path out, Path against, boolean discover)
            throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            String classpath = System.getProperty("java.class.path")
                    + (against == null ? "" : File.pathSeparator + against);
            List<String> options = new ArrayList<>(List.of("-classpath", classpath));
            boolean processed = against != null;
            if (processed && discover) {
                options.addAll(List.of("-processorpath", classpath));
            } else if (!processed) {
                options.add("-proc:none");
            }
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                units.add(source(e.getKey(), e.getValue()));
            }
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics, options, null, units);
            if (processed && !discover) {
                task.setProcessors(List.of(new VexelProcessor()));
            }
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

    private static JavaFileObject source(String fqcn, String code) {
        URI uri = URI.create("string:///" + fqcn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension);
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    private static void onlyError(List<String> errors, String fragment) {
        assertEquals(1, errors.size(), () -> "expected exactly one error containing \"" + fragment + "\", got:\n"
                + String.join("\n", errors));
        assertTrue(errors.get(0).contains(fragment), () -> "expected \"" + fragment + "\" in:\n" + errors.get(0));
    }
}

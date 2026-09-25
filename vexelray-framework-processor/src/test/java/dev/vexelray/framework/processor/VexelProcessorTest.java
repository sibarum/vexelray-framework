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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Another jar: a main-thread type, a final class, and a starter with a default the app overrides — and
     * stand-ins for the {@code -shell} and GUI types generated wiring names, since the processor cannot depend on
     * {@code -shell}. {@code -core} is real: it is on this module's classpath.
     *
     * <p>The stand-ins record what the wiring does to them in {@code Shell.LOG}, so a test can run the generated
     * class one phase at a time and read back what was built when.
     */
    private static final Map<String, String> LIB = Map.ofEntries(
            Map.entry("lib.Window", """
                package lib;
                @dev.vexelray.framework.api.MainThread
                public final class Window {}
                """),
            Map.entry("lib.Device", """
                package lib;
                public final class Device {}
                """),
            Map.entry("lib.Clock", """
                package lib;
                public interface Clock {}
                """),
            Map.entry("lib.Starter", """
                package lib;
                import dev.vexelray.framework.api.*;
                @Configuration
                public final class Starter {
                    @Default @Provides public Clock clock() { return null; }
                    @Default @Provides public Device device() { return null; }
                }
                """),
            Map.entry("dev.vexelray.framework.shell.Shell", """
                package dev.vexelray.framework.shell;
                import dev.vexelray.framework.core.*;
                import java.util.*;
                public final class Shell {
                    public static final List<String> LOG = new ArrayList<>();
                    private final Launch launch;
                    private final Map<String, String> file;
                    private final FrameHooks hooks = new FrameHooks();
                    private final Disposer disposer = new Disposer();
                    public Shell(Launch launch, Map<String, String> file) { this.launch = launch; this.file = file; }
                    public Launch launch() { return launch; }
                    public AppInfo info() { return null; }
                    public FrameHooks hooks() { return hooks; }
                    public Disposer disposer() { return disposer; }
                    public Shell appearance(Appearance a) { LOG.add("appearance applied"); return this; }
                    public Shell input(InputBackend i) { LOG.add("input handed back"); return this; }
                    public Shell clipboard(ClipboardBackend c) { LOG.add("clipboard handed back"); return this; }
                    public Placement place(String name) { LOG.add("placed " + name); return new Placement(name); }
                    public dev.vexelray.gui.core.Gui gui() { return new dev.vexelray.gui.core.Gui(); }
                    public dev.vexelray.gui.core.app.GuiApp app() { return new dev.vexelray.gui.core.app.GuiApp(); }
                    public dev.vexelray.gui.widget.TitleBar titleBar() { return new dev.vexelray.gui.widget.TitleBar(); }
                    public String setting(String k, String d) { return file.getOrDefault(k, d); }
                    public int setting(String k, int d) { return file.containsKey(k) ? Integer.parseInt(file.get(k)) : d; }
                    public long setting(String k, long d) { return d; }
                    public float setting(String k, float d) { return d; }
                    public boolean setting(String k, boolean d) { return d; }
                    public List<String> settingList(String k) { return List.of(); }
                }
                """),
            Map.entry("dev.vexelray.framework.shell.Wiring", """
                package dev.vexelray.framework.shell;
                public abstract class Wiring {
                    public abstract AppInfo info();
                    public void config(Shell shell) {}
                    public void model(Shell shell) {}
                    public void gui(Shell shell) {}
                    public void tree(Shell shell) {}
                    public void window(Shell shell) {}
                    public void attach(Shell shell) {}
                }
                """),
            Map.entry("dev.vexelray.framework.shell.AppInfo", """
                package dev.vexelray.framework.shell;
                public record AppInfo(String name, String title, int width, int height,
                                      java.util.Set<String> settingKeys) {}
                """),
            Map.entry("dev.vexelray.framework.shell.Placement", """
                package dev.vexelray.framework.shell;
                public final class Placement {
                    public final String name;
                    public Placement(String name) { this.name = name; }
                }
                """),
            Map.entry("dev.vexelray.framework.shell.Appearance", """
                package dev.vexelray.framework.shell;
                public final class Appearance {}
                """),
            Map.entry("dev.vexelray.framework.shell.InputBackend", """
                package dev.vexelray.framework.shell;
                public interface InputBackend extends AutoCloseable { void close(); }
                """),
            Map.entry("dev.vexelray.framework.shell.ClipboardBackend", """
                package dev.vexelray.framework.shell;
                public interface ClipboardBackend extends AutoCloseable { void close(); }
                """),
            Map.entry("dev.vexelray.gui.core.Gui", """
                package dev.vexelray.gui.core;
                public final class Gui {}
                """),
            Map.entry("dev.vexelray.gui.core.app.GuiApp", """
                package dev.vexelray.gui.core.app;
                public final class GuiApp {}
                """),
            Map.entry("dev.vexelray.gui.widget.TitleBar", """
                package dev.vexelray.gui.widget;
                public final class TitleBar {}
                """));

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
                    // A main-thread value into a main-thread value: the framework's GuiApp is the main thread's.
                    @MainThread @Provides public Surface surface(dev.vexelray.gui.core.app.GuiApp app) {
                        return null;
                    }
                    // Package-private and the application's own, so exempt from the interface rule.
                    @Provides Pump pump() { return new Pump(); }
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
                final class Pump {
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

    /**
     * T3.1: the window is the main thread's, and the framework's {@code GuiApp} carries no annotation — it lives in
     * a repo that must not learn this one exists — so the processor's table of framework values marks it instead.
     */
    @Test
    void aComponentAskingForTheWindowIsAnError() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer { public Composer(dev.vexelray.gui.core.app.GuiApp app) {} }
                """), "T2.2: Composer takes app, a main-thread value (GuiApp is the main thread's");
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

    // --- generation -----------------------------------------------------------------------------------------

    /** An application with one of everything the wiring handles, each part logging when it is built. */
    private static final String[] RECORDING_APP = {
            """
            @VexelApp(name = "demo", title = "Demo \\"quoted\\"", width = 640, height = 480)
            public final class DemoApp {}
            """,
            """
            final class Model {
                Model(int size) { dev.vexelray.framework.shell.Shell.LOG.add("model " + size); }
            }
            """,
            """
            final class Ui {
                Ui(dev.vexelray.gui.core.Gui gui, Model model) { dev.vexelray.framework.shell.Shell.LOG.add("ui"); }
            }
            """,
            """
            final class Window {
                Window(dev.vexelray.gui.core.app.GuiApp app) { dev.vexelray.framework.shell.Shell.LOG.add("window"); }
                @BeforeFrame(FrameStage.SETTLE) void settle() {}
            }
            """,
            """
            final class Socket implements AutoCloseable {
                Socket(dev.vexelray.framework.shell.Shell shell) { dev.vexelray.framework.shell.Shell.LOG.add("socket"); }
                public void close() { dev.vexelray.framework.shell.Shell.LOG.add("socket closed"); }
            }
            """,
            """
            final class Preview {
                Preview(Model model) { dev.vexelray.framework.shell.Shell.LOG.add("preview"); }
            }
            """,
            """
            @Configuration
            final class Recipes {
                @Provides dev.vexelray.framework.shell.Appearance look() {
                    dev.vexelray.framework.shell.Shell.LOG.add("look");
                    return new dev.vexelray.framework.shell.Appearance();
                }
                @Provides Model model(@Setting(value = "store.size", def = "4") int size) { return new Model(size); }
                @Provides Ui ui(dev.vexelray.gui.core.Gui gui, Model model) { return new Ui(gui, model); }
                @MainThread @Provides Window window(dev.vexelray.gui.core.app.GuiApp app) { return new Window(app); }
                @Provides Socket socket(dev.vexelray.framework.shell.Shell shell) { return new Socket(shell); }
                @OnMode(RunMode.WINDOWED) @Provides Preview preview(Model model) { return new Preview(model); }
            }
            """,
            """
            @Component(lane = "work")
            public final class Worker {
                public Worker(dev.vexelray.framework.shell.Placement placement, Model model) {
                    dev.vexelray.framework.shell.Shell.LOG.add("worker on " + placement.name);
                }
            }
            """};

    @Test
    void eachPartIsBuiltInThePhaseItsParametersPutItIn() throws Exception {
        Compiled compiled = build(RECORDING_APP);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());

        // CONFIG: the look is applied the moment it exists; the model takes only a setting; the worker takes the
        // model and its lane's placement, both CONFIG values.
        assertEquals(List.of("look", "appearance applied", "model 4", "preview", "placed work", "worker on work"),
                run.phase("config"));
        assertEquals(List.of(), run.phase("model"));
        assertEquals(List.of("ui"), run.phase("gui"), "the Gui exists from GUI");
        assertEquals(List.of(), run.phase("tree"));
        assertEquals(List.of("window"), run.phase("window"), "the GuiApp exists from WINDOW");
        assertEquals(List.of("socket"), run.phase("attach"), "a part taking the whole Shell is built last");

        assertEquals(1, run.hooks(), "Window's @BeforeFrame is in the frame array");
        run.shutdown();
        assertEquals(List.of("socket closed"), run.phase("shutdown"), "an AutoCloseable part is closed at shutdown");
    }

    @Test
    void theApplicationsFactsAndItsSettingKeysAreItsInfo() throws Exception {
        Run run = build(RECORDING_APP).run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());
        assertEquals("AppInfo[name=demo, title=Demo \"quoted\", width=640, height=480, settingKeys=[store.size]]",
                run.info());
    }

    @Test
    void aSettingIsReadThroughTheShell() throws Exception {
        Run run = build(RECORDING_APP).run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of("store.size", "9"));
        assertTrue(run.phase("config").contains("model 9"), () -> run.log.toString());
    }

    @Test
    void aPartGuardedToOneModeIsNotBuiltInAnother() throws Exception {
        Run run = build(RECORDING_APP).run(dev.vexelray.framework.api.RunMode.FRAMES, Map.of());
        assertFalse(run.phase("config").contains("preview"), () -> run.log.toString());
        assertTrue(run.phase("config").contains("model 4"));
    }

    @Test
    void aParameterNothingProvidesIsAnErrorNamingIt() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides Clock clock(Device device) { return null; }
                }
                """), "Recipes.clock takes device, a lib.Device, and nothing provides one");
    }

    @Test
    void aCycleIsAnErrorNamingThePath() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                final class A {}
                """, """
                final class B {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides A a(B b) { return null; }
                    @Provides B b(A a) { return null; }
                }
                """), "A cycle: Recipes.a -> Recipes.b -> Recipes.a");
    }

    @Test
    void theAppearanceCanTakeOnlyConfigValues() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides dev.vexelray.framework.shell.Appearance look(dev.vexelray.gui.core.Gui gui) {
                        return null;
                    }
                }
                """), "provides the Appearance, and takes something that exists only from GUI");
    }

    /**
     * The README's promise, as generated code: a provider returning one of the framework's defaults hands it back,
     * and the framework uses it instead of opening its own. Handed back rather than registered for shutdown as well,
     * because the shell closes what it is handed and a second registration would close it twice.
     */
    @Test
    void aProvidedInputBackendAndClipboardAreHandedBackAndClosedOnlyByTheShell() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                final class Recorded implements dev.vexelray.framework.shell.InputBackend {
                    public void close() { dev.vexelray.framework.shell.Shell.LOG.add("recorded closed"); }
                }
                """, """
                final class Confined implements dev.vexelray.framework.shell.ClipboardBackend {
                    public void close() { dev.vexelray.framework.shell.Shell.LOG.add("confined closed"); }
                }
                """, """
                @Configuration
                final class Recipes {
                    @Provides dev.vexelray.framework.shell.InputBackend input() { return new Recorded(); }
                    @Provides dev.vexelray.framework.shell.ClipboardBackend clipboard(dev.vexelray.gui.core.Gui gui) {
                        return new Confined();
                    }
                }
                """);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());

        assertEquals(List.of("input handed back"), run.phase("config"), "a provider taking nothing is CONFIG's");
        assertEquals(List.of("clipboard handed back"), run.phase("gui"), "a provider taking the Gui is built in GUI");
        run.shutdown();
        assertEquals(List.of(), run.phase("shutdown"), "the wiring registers neither: the shell owns both");
    }

    @Test
    void anInputBackendBuiltAfterTheFrameworkOpensItsOwnIsAnError() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides dev.vexelray.framework.shell.InputBackend input(
                            dev.vexelray.framework.shell.Shell shell) {
                        return null;
                    }
                }
                """), "provides the InputBackend, and takes something that exists only from ATTACH. The input"
                + " backend is opened at the start of WINDOW");
    }

    @Test
    void aClipboardBuiltAfterTheFrameworkInstallsItsOwnIsAnError() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides dev.vexelray.framework.shell.ClipboardBackend clipboard(
                            dev.vexelray.framework.shell.Shell shell) {
                        return null;
                    }
                }
                """), "provides the ClipboardBackend, and takes something that exists only from ATTACH");
    }

    @Test
    void aFrameHookNothingBuildsIsAnError() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                final class Loose {
                    @BeforeFrame void drain() {}
                }
                """), "@BeforeFrame Loose.drain would never run");
    }

    @Test
    void aPartWhoseDependencyNeverExistsIsNeverBuiltAndSaysSo() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                final class Model {}
                """, """
                final class View {}
                """, """
                @Configuration
                final class Recipes {
                    @OnMode(RunMode.WINDOWED) @Provides Model model() { return null; }
                    @OnMode(RunMode.FRAMES) @Provides View view(Model model) { return null; }
                }
                """), "Recipes.view is never built");
    }

    @Test
    void aProviderIsNotHandedAPlacement() {
        onlyError(app("""
                public interface Store {}
                """, """
                @Configuration
                public final class Recipes {
                    @Provides public Store store(dev.vexelray.framework.shell.Placement p) { return null; }
                }
                """), "takes a Placement, which is a component's thread and mailboxes");
    }

    @Test
    void aConfigurationTheWiringCannotConstructIsAnError() {
        onlyError(app("""
                public interface Store {}
                """, """
                @Configuration
                public final class Recipes {
                    public Recipes(int x) {}
                    @Provides public Store store() { return null; }
                }
                """), "has no non-private constructor taking nothing");
    }

    @Test
    void aPackagePrivateClassOfTheApplicationsOwnMayBeProvided() {
        assertEquals(List.of(), app("""
                final class Model {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides Model model() { return new Model(); }
                }
                """));
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
        return build(bodies).errors();
    }

    private static Compiled build(String... bodies) {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        for (String body : bodies) {
            sources.put("app." + typeName(body), IMPORTS + body);
        }
        try {
            Path out = Files.createTempDirectory(temp, "app");
            return new Compiled(compile(sources, out, lib, false), out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** What a compilation said, and where it put the classes — the generated wiring among them. */
    private record Compiled(List<String> errors, Path out) {

        /**
         * Load {@code app.DemoAppWiring} beside the stand-ins and a shell for this mode and settings file.
         * Reflection is a test's privilege here: the framework's own rule is about the startup path.
         */
        Run run(dev.vexelray.framework.api.RunMode mode, Map<String, String> file) throws Exception {
            assertEquals(List.of(), errors);
            java.net.URLClassLoader loader = new java.net.URLClassLoader(
                    new java.net.URL[]{out.toUri().toURL(), lib.toUri().toURL()},
                    VexelProcessorTest.class.getClassLoader());
            Class<?> shellType = loader.loadClass("dev.vexelray.framework.shell.Shell");
            Object shell = shellType.getConstructor(dev.vexelray.framework.core.Launch.class, Map.class)
                    .newInstance(new dev.vexelray.framework.core.Launch(mode, 0, Map.of(), List.of()), file);
            @SuppressWarnings("unchecked")
            List<String> log = (List<String>) shellType.getField("LOG").get(null);
            log.clear();
            var ctor = loader.loadClass("app.DemoAppWiring").getDeclaredConstructor();
            ctor.setAccessible(true);
            return new Run(ctor.newInstance(), shell, shellType, log);
        }
    }

    /** A generated wiring, driven one phase at a time the way {@code VexelApplication} drives it. */
    private record Run(Object wiring, Object shell, Class<?> shellType, List<String> log) {

        /** Call one phase method and return what was built during it. */
        List<String> phase(String name) throws Exception {
            if (name.equals("shutdown")) {
                return List.copyOf(log);
            }
            log.clear();
            var method = wiring.getClass().getSuperclass().getMethod(name, shellType);
            method.invoke(wiring, shell);
            return List.copyOf(log);
        }

        String info() throws Exception {
            return String.valueOf(wiring.getClass().getSuperclass().getMethod("info").invoke(wiring));
        }

        int hooks() throws Exception {
            var hooks = (dev.vexelray.framework.core.FrameHooks) shellType.getMethod("hooks").invoke(shell);
            return hooks.size();
        }

        /** Close what the wiring registered, leaving what that logged for {@code phase("shutdown")}. */
        void shutdown() throws Exception {
            log.clear();
            ((dev.vexelray.framework.core.Disposer) shellType.getMethod("disposer").invoke(shell)).close();
            List<String> closed = List.copyOf(log);
            log.clear();
            log.addAll(closed);
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

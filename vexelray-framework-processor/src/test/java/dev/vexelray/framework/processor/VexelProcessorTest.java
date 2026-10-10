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
                    public Shell liveness(LivenessPolicy p) { LOG.add("liveness handed back"); return this; }
                    Placement place(String name) { LOG.add("placed " + name); return new Placement(name); }
                    public dev.vexelray.gui.core.Gui gui() { return new dev.vexelray.gui.core.Gui(); }
                    public dev.vexelray.gui.core.app.GuiApp app() { return new dev.vexelray.gui.core.app.GuiApp(); }
                    public dev.vexelray.gui.core.app.ComputeQueue computeQueue() { LOG.add("compute queue lent"); return new dev.vexelray.gui.core.app.ComputeQueue(); }
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
                    public boolean computeQueue() { return false; }
                    public String icon() { return null; }
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
                    public <T> Placement subscribe(sibarum.atchung.Topic<T> topic, java.util.function.Consumer<T> on,
                                                   int capacity, sibarum.atchung.Backpressure policy) {
                        Shell.LOG.add("subscribed " + topic.name() + " " + topic.payloadType().getSimpleName() + " "
                                + capacity + " " + policy);
                        return this;
                    }
                }
                """),
            Map.entry("dev.vexelray.framework.shell.Placements", """
                package dev.vexelray.framework.shell;
                public final class Placements {
                    public static Placement of(Shell shell, String lane) { return shell.place(lane); }
                    public static <T> void mailbox(Placement placement, sibarum.atchung.Topic<T> topic,
                                                   java.util.function.Consumer<T> on, int capacity,
                                                   sibarum.atchung.Backpressure policy) {
                        placement.subscribe(topic, on, capacity, policy);
                    }
                }
                """),
            Map.entry("sibarum.atchung.Topic", """
                package sibarum.atchung;
                public record Topic<T>(String name, Class<T> payloadType) {
                    public static <T> Topic<T> of(String name, Class<T> payloadType) { return new Topic<>(name, payloadType); }
                }
                """),
            Map.entry("sibarum.atchung.Backpressure", """
                package sibarum.atchung;
                public enum Backpressure { FAIL, DROP_OLDEST, DROP_NEWEST, COALESCE_LATEST, BLOCK }
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
            Map.entry("dev.vexelray.framework.shell.LivenessPolicy", """
                package dev.vexelray.framework.shell;
                public interface LivenessPolicy {}
                """),
            Map.entry("dev.vexelray.gui.core.Gui", """
                package dev.vexelray.gui.core;
                public final class Gui {}
                """),
            Map.entry("dev.vexelray.gui.core.app.GuiApp", """
                package dev.vexelray.gui.core.app;
                public final class GuiApp {}
                """),
            Map.entry("dev.vexelray.gui.core.app.ComputeQueue", """
                package dev.vexelray.gui.core.app;
                public final class ComputeQueue {}
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
    void aComponentIsLentTheComputeQueueAndTheDeviceIsMadeWithOne() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @Component(lane = "physics")
                public final class Physics { public Physics(dev.vexelray.gui.core.app.ComputeQueue queue) {} }
                """);
        assertEquals(List.of(), compiled.errors(), "T3.1 keeps the queue that draws on the main thread, not this one");
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());
        assertEquals(true, run.computeQueue(), "the device is asked for a queue to lend");
        assertEquals(List.of(), run.phase("config"));
        assertEquals(List.of("compute queue lent"), run.phase("window"), "built when the device exists");
    }

    // --- @VexelApp(icon) -------------------------------------------------------------------------------------

    private static final String GENERATED_METADATA =
            "META-INF/native-image/dev.vexelray.framework.generated/app.DemoAppWiring/reachability-metadata.json";

    @Test
    void aNamedIconIsHandedToTheShellAndRegisteredForNativeImage() throws Exception {
        Compiled compiled = buildWith(Map.of("app/demo.ico", "an icon"), """
                @VexelApp(name = "demo", title = "Demo", icon = "demo.ico")
                public final class DemoApp {}
                """);
        assertEquals(List.of(), compiled.errors());
        assertEquals("demo.ico", compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).icon(),
                "by name, resolved beside the wiring as it was beside the application");
        String metadata = Files.readString(compiled.out().resolve(GENERATED_METADATA));
        assertTrue(metadata.contains("\"glob\": \"app/demo.ico\""), metadata);
        assertTrue(metadata.contains("\"glob\": \"app/demo-window.ico\""), "and the other windows' variant: " + metadata);
    }

    @Test
    void anAbsoluteIconNameIsResolvedFromTheRoot() throws Exception {
        Compiled compiled = buildWith(Map.of("marks/demo.ico", "an icon"), """
                @VexelApp(name = "demo", title = "Demo", icon = "/marks/demo.ico")
                public final class DemoApp {}
                """);
        assertEquals(List.of(), compiled.errors());
        assertTrue(Files.readString(compiled.out().resolve(GENERATED_METADATA)).contains("\"marks/demo.ico\""));
    }

    @Test
    void anIconThatIsNotThereIsACompileError() {
        onlyError(buildWith(Map.of(), """
                @VexelApp(name = "demo", title = "Demo", icon = "demo.ico")
                public final class DemoApp {}
                """).errors(), "there is no resource app/demo.ico on the class output or the class path");
    }

    @Test
    void anIconIsAnIcoOrAPng() {
        onlyError(buildWith(Map.of("app/demo.bmp", "a bitmap"), """
                @VexelApp(name = "demo", title = "Demo", icon = "demo.bmp")
                public final class DemoApp {}
                """).errors(), "an icon is an .ico or a .png");
    }

    @Test
    void anApplicationNamingNoIconLeavesItToTheFramework() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """);
        assertEquals(null, compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).icon());
        assertFalse(Files.exists(compiled.out().resolve(GENERATED_METADATA)), "nothing to register");
    }

    @Test
    void anApplicationThatLendsNoQueueAsksForNone() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """);
        assertEquals(List.of(), compiled.errors());
        assertEquals(false, compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).computeQueue());
    }

    @Test
    void theComputeQueueIsLentToOneComponent() {
        onlyError(app("""
                @Component(lane = "physics")
                public final class Physics { public Physics(dev.vexelray.gui.core.app.ComputeQueue queue) {} }
                """, """
                @Component(lane = "fluid")
                public final class Fluid { public Fluid(dev.vexelray.gui.core.app.ComputeQueue queue) {} }
                """), "T3.1: Fluid takes the ComputeQueue, and so does Physics");
    }

    @Test
    void aProviderIsNotLentTheComputeQueue() {
        onlyError(app("""
                public interface Solver {}
                """, """
                @Configuration
                public final class AppConfig {
                    @Provides public Solver solver(dev.vexelray.gui.core.app.ComputeQueue queue) { return null; }
                }
                """), "T3.1: AppConfig.solver takes the ComputeQueue");
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
    void aLaneThatIsNotANameIsAnError() {
        onlyError(app("""
                @Component(lane = "<default>x")
                public final class Composer {}
                """), "has the lane \"<default>x\", which is not a name");
    }

    @Test
    void componentsThatNameNoLaneShareTheDefaultLaneAndMayHoldEachOther() {
        assertEquals(List.of(), app("""
                @Component
                public final class Composer { public Composer(Helper helper) {} }
                """, """
                @Component
                public final class Helper {}
                """));
    }

    @Test
    void aComponentOnTheDefaultLaneMayNotHoldOneOnANamedLane() {
        onlyError(app("""
                @Component
                public final class Composer { public Composer(Indexer indexer) {} }
                """, """
                @Component(lane = "index")
                public final class Indexer {}
                """), "T2.3: Composer (lane \"<default>\") holds Indexer (lane \"index\")");
    }

    // --- channels: @Subscribe and @Publishes ---------------------------------------------------------------------

    @Test
    void aSubscribeBecomesAMailboxOnTheComponentsLaneWithNoPlacementParameter() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                public record Edit(int at) {}
                """, """
                @Component(lane = "compose")
                public final class Composer {
                    @Subscribe(topic = "edits", capacity = 8) public void edited(Edit e) {}
                    @Subscribe(topic = "cursor", overflow = Overflow.COALESCE_LATEST) public void moved(Edit e) {}
                }
                """);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());
        assertEquals(List.of("placed compose", "subscribed edits Edit 8 FAIL",
                "subscribed cursor Edit 64 COALESCE_LATEST"), run.phase("config"));
    }

    @Test
    void componentsThatNameNoLaneSubscribeOnTheOneDefaultPlacement() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                public record Edit(int at) {}
                """, """
                @Component
                public final class One { @Subscribe(topic = "a") public void on(Edit e) {} }
                """, """
                @Component
                public final class Two { @Subscribe(topic = "b") public void on(Edit e) {} }
                """);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());
        long placed = run.phase("config").stream().filter(s -> s.startsWith("placed ")).count();
        assertEquals(1, placed, "both components share the default lane's one placement: " + run.phase("config"));
    }

    @Test
    void aTopicThatIsBothAnEdgeAndASampleIsAnError() {
        onlyError(app("""
                public record Edit(int at) {}
                """, """
                @Component(lane = "a")
                public final class Sink { @Subscribe(topic = "edits") public void on(Edit e) {} }
                """, """
                @Component(lane = "b")
                public final class View {
                    @Subscribe(topic = "edits", overflow = Overflow.COALESCE_LATEST) public void on(Edit e) {}
                }
                """), "T4.1: the topic \"edits\" is an edge to Sink.on (FAIL) and a sample to View.on");
    }

    @Test
    void aTopicNameCarryingTwoPayloadTypesIsAnError() {
        onlyError(app("""
                public record Edit(int at) {}
                """, """
                public record Move(int by) {}
                """, """
                @Component(lane = "a")
                public final class Sink { @Subscribe(topic = "edits") public void on(Edit e) {} }
                """, """
                @Component(lane = "b")
                public final class Other { @Subscribe(topic = "edits") public void on(Move m) {} }
                """), "T4.1: the topic \"edits\" carries");
    }

    @Test
    void mainThreadCodeMayNotDeclareASendOnABlockingChannel() {
        onlyError(app("""
                public record Edit(int at) {}
                """, """
                @Component(lane = "a")
                public final class Sink {
                    @Subscribe(topic = "edits", overflow = Overflow.BLOCK) public void on(Edit e) {}
                }
                """, """
                @MainThread @Publishes("edits")
                public final class Toolbar {}
                """), "T4.7: Toolbar runs on the main thread and declares a send on \"edits\", where Sink.on has a"
                + " BLOCK mailbox");
    }

    // --- T2.4: a payload must be able to cross a lane as itself --------------------------------------------------

    private static String sink(String payload) {
        return """
                @Component(lane = "a")
                public final class Sink {
                    @Subscribe(topic = "t") public void on(%s p) {}
                }
                """.formatted(payload);
    }

    @Test
    void aRecordOfValuesCanCrossALane() {
        assertEquals(List.of(), app("""
                public record Range(int from, int to) {}
                """, """
                public record Edit(Range range, String text, java.time.Instant at, java.util.Optional<String> note,
                                   java.util.UUID id) {}
                """, sink("Edit")));
    }

    @Test
    void aFinalClassOfFinalValuesCanCrossALane() {
        assertEquals(List.of(), app("""
                public final class Point {
                    private final int x;
                    private final int y;
                    public Point(int x, int y) { this.x = x; this.y = y; }
                }
                """, sink("Point")));
    }

    @Test
    void aSealedInterfaceOfRecordsCanCrossALane() {
        assertEquals(List.of(), app("""
                public sealed interface Shape permits Circle, Square {}
                """, """
                public record Circle(int r) implements Shape {}
                """, """
                public record Square(int side) implements Shape {}
                """, sink("Shape")));
    }

    @Test
    void aCollectionInAPayloadIsRefusedAndTheMessageNamesThePath() {
        onlyError(app("""
                public record Edit(java.util.List<String> lines) {}
                """, sink("Edit")), "T2.4: Sink.on receives app.Edit on \"t\", and it cannot cross a lane as itself:"
                + " Edit.lines: java.util.List<java.lang.String> has type arguments");
    }

    @Test
    void anArrayInANestedRecordIsRefused() {
        onlyError(app("""
                public record Cells(int[] values) {}
                """, """
                public record Edit(Cells cells) {}
                """, sink("Edit")), "Edit.cells -> Cells.values: int[] is an array");
    }

    @Test
    void aClassWithAMutableFieldIsRefused() {
        onlyError(app("""
                public final class Cursor {
                    private int at;
                    public void move() { at++; }
                }
                """, sink("Cursor")), "Cursor.at: is not final");
    }

    @Test
    void aClassThatIsNotFinalIsRefused() {
        onlyError(app("""
                public class Open {}
                """, sink("Open")), "Open is neither final nor sealed, so a subclass could add mutable state");
    }

    @Test
    void aFinalClassOverAnAbstractClassOfFinalValuesCanCrossALane() {
        assertEquals(List.of(), app("""
                public abstract class Base { private final int x = 0; }
                """, """
                public final class Derived extends Base { private final String y = ""; }
                """, sink("Derived")));
    }

    @Test
    void aMutableFieldInASuperclassIsRefused() {
        onlyError(app("""
                public abstract class Base { protected int x; }
                """, """
                public final class Derived extends Base {}
                """, sink("Derived")), "Base.x: is not final");
    }

    @Test
    void anInnerClassIsRefusedBecauseItHoldsItsEnclosingInstance() {
        onlyError(app("""
                @Component(lane = "a")
                public final class Sink {
                    int mutable;
                    public final class Ping {}
                    @Subscribe(topic = "t") public void on(Ping p) {}
                }
                """), "Ping: is an inner class, so it holds a reference to the instance that made it");
    }

    @Test
    void aStaticNestedClassCanCrossALane() {
        assertEquals(List.of(), app("""
                @Component(lane = "a")
                public final class Sink {
                    int mutable;
                    public static final class Ping {}
                    @Subscribe(topic = "t") public void on(Ping p) {}
                }
                """));
    }

    @Test
    void aSealedClassOfFinalSubclassesCanCrossALane() {
        assertEquals(List.of(), app("""
                public sealed abstract class Shape permits Circle {
                    private final int id = 0;
                }
                """, """
                public final class Circle extends Shape { private final int r = 0; }
                """, sink("Shape")));
    }

    @Test
    void aSealedClassWithAMutablePermittedSubclassIsRefused() {
        onlyError(app("""
                public sealed abstract class Shape permits Blob {}
                """, """
                public final class Blob extends Shape { int[] cells; }
                """, sink("Shape")), "Shape -> Blob.cells: is not final");
    }

    @Test
    void anInterfaceThatIsNotSealedIsRefused() {
        onlyError(app("""
                public interface Message {}
                """, sink("Message")), "Message is an interface that is not sealed");
    }

    // --- T4.4: no blocking edge on a cycle ---------------------------------------------------------------------

    @Test
    void twoComponentsThatBlockOnEachOtherAreAnError() {
        onlyError(app("""
                public record Order(int id) {}
                """, """
                @Component(lane = "a") @Publishes("orders")
                public final class Orders {
                    @Subscribe(topic = "acks", overflow = Overflow.BLOCK) public void acked(Order o) {}
                }
                """, """
                @Component(lane = "b") @Publishes("acks")
                public final class Billing {
                    @Subscribe(topic = "orders", overflow = Overflow.BLOCK) public void billed(Order o) {}
                }
                """), "T4.4: a blocking cycle in the message graph: Orders -[orders, BLOCK]-> Billing -[acks, BLOCK]->"
                + " Orders");
    }

    @Test
    void aComponentThatBlocksOnItsOwnMailboxIsAnError() {
        onlyError(app("""
                public record Tick(int n) {}
                """, """
                @Component(lane = "a") @Publishes("ticks")
                public final class Counter {
                    @Subscribe(topic = "ticks", overflow = Overflow.BLOCK) public void tick(Tick t) {}
                }
                """), "T4.4: a blocking cycle in the message graph: Counter -[ticks, BLOCK]-> Counter");
    }

    @Test
    void aSendFromAMethodOfTheComponentIsOnTheCycleToo() {
        onlyError(app("""
                public record Order(int id) {}
                """, """
                @Component(lane = "a")
                public final class Orders {
                    @Publishes("orders") @Subscribe(topic = "acks") public void acked(Order o) {}
                }
                """, """
                @Component(lane = "b")
                public final class Billing {
                    @Publishes("acks") @Subscribe(topic = "orders", overflow = Overflow.BLOCK) public void billed(Order o) {}
                }
                """), "T4.4: a blocking cycle in the message graph");
    }

    @Test
    void requestAndResponseOnFailMailboxesIsACycleAndIsFine() {
        assertEquals(List.of(), app("""
                public record Order(int id) {}
                """, """
                @Component(lane = "a") @Publishes("orders")
                public final class Orders {
                    @Subscribe(topic = "acks") public void acked(Order o) {}
                }
                """, """
                @Component(lane = "b") @Publishes("acks")
                public final class Billing {
                    @Subscribe(topic = "orders") public void billed(Order o) {}
                }
                """));
    }

    @Test
    void aBlockingSendThatNothingAnswersIsNotACycle() {
        assertEquals(List.of(), app("""
                public record Order(int id) {}
                """, """
                @Component(lane = "a") @Publishes("orders")
                public final class Orders {}
                """, """
                @Component(lane = "b")
                public final class Billing {
                    @Subscribe(topic = "orders", overflow = Overflow.BLOCK) public void billed(Order o) {}
                }
                """));
    }

    @Test
    void aFrameHookIsMainThreadCodeForTheBlockingSendRule() {
        onlyError(app("""
                public record Edit(int at) {}
                """, """
                @Component(lane = "a")
                public final class Sink {
                    @Subscribe(topic = "edits", overflow = Overflow.BLOCK) public void on(Edit e) {}
                }
                """, """
                @Configuration
                public final class Recipes {
                    @Provides Pump pump() { return new Pump(); }
                }
                """, """
                final class Pump {
                    @BeforeFrame @Publishes("edits") void drain() {}
                }
                """), "T4.7: Pump.drain runs on the main thread");
    }

    @Test
    void aComponentMayBlockAndTheMainThreadMayFailOrCoalesce() {
        assertEquals(List.of(), app("""
                public record Edit(int at) {}
                """, """
                @Component(lane = "a")
                public final class Sink {
                    @Subscribe(topic = "edits", overflow = Overflow.BLOCK) public void on(Edit e) {}
                    @Subscribe(topic = "samples", overflow = Overflow.COALESCE_LATEST) public void on2(Edit e) {}
                    @Subscribe(topic = "commands") public void on3(Edit e) {}
                }
                """, """
                @Component(lane = "b") @Publishes("edits")
                public final class Producer {}
                """, """
                @MainThread @Publishes({"samples", "commands"})
                public final class Toolbar {}
                """));
    }

    @Test
    void aSubscribeOutsideAComponentIsAnError() {
        onlyError(app("""
                public record Edit(int at) {}
                """, """
                public final class Loose { @Subscribe(topic = "edits") public void on(Edit e) {} }
                """), "@Subscribe Loose.on is not on a @Component");
    }

    @Test
    void aSubscribeMustTakeOnePayloadThatCanNameATopic() {
        onlyError(app("""
                @Component(lane = "a")
                public final class Sink { @Subscribe(topic = "n") public void on(int n) {} }
                """), "takes int, which cannot name a topic's type");
        onlyError(app("""
                @Component(lane = "a")
                public final class Sink { @Subscribe(topic = "n") public void on(java.util.List<String> n) {} }
                """), "cannot name a topic's type");
        onlyError(app("""
                @Component(lane = "a")
                public final class Sink { @Subscribe(topic = "n") public void on(String a, String b) {} }
                """), "takes 2 parameters, and must take exactly one");
        onlyError(app("""
                @Component(lane = "a")
                public final class Sink { @Subscribe(topic = "n") public String on(String a) { return a; } }
                """), "returns a value");
        onlyError(app("""
                @Component(lane = "a")
                public final class Sink { @Subscribe(topic = " ") public void on(String a) {} }
                """), "has a blank topic");
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
                public Worker(Model model) { dev.vexelray.framework.shell.Shell.LOG.add("worker"); }
                @Subscribe(topic = "jobs") public void job(String job) {}
            }
            """};


    /**
     * What the wiring built is readable through an accessor named for the part, so a test or a capture that holds
     * the wiring drives the objects a user does. Null until the phase that builds it, and for a part a mode does
     * not build.
     */
    @Test
    void whatWasBuiltCanBeReadBackFromTheWiring() throws Exception {
        Compiled compiled = build(RECORDING_APP);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());

        assertEquals(null, run.part("model"), "nothing is built before its phase");
        run.phase("config");
        assertEquals("app.Model", run.part("model").getClass().getName());
        assertEquals(null, run.part("ui"), "the Ui is built in GUI, which has not run");
        run.phase("model");
        run.phase("gui");
        assertEquals("app.Ui", run.part("ui").getClass().getName());
    }
    @Test
    void eachPartIsBuiltInThePhaseItsParametersPutItIn() throws Exception {
        Compiled compiled = build(RECORDING_APP);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());

        // CONFIG: the look is applied the moment it exists; the model takes only a setting; the worker takes the
        // model, a CONFIG value, and its lane is placed and its mailbox registered as it is built.
        assertEquals(List.of("look", "appearance applied", "model 4", "preview", "worker", "placed work",
                "subscribed jobs String 64 FAIL"),
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

    /** The liveness policy is handed back the same way, and is not closeable, so the shell has nothing to close. */
    @Test
    void aProvidedLivenessPolicyIsHandedBack() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides dev.vexelray.framework.shell.LivenessPolicy liveness() {
                        return new dev.vexelray.framework.shell.LivenessPolicy() {};
                    }
                }
                """);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());
        assertEquals(List.of("liveness handed back"), run.phase("config"));
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
    void aComponentIsNotHandedAPlacementEither() {
        onlyError(app("""
                @Component(lane = "work")
                public final class Worker {
                    public Worker(dev.vexelray.framework.shell.Placement placement) {}
                }
                """), "Worker takes a Placement, which is the container's");
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
        return buildWith(Map.of(), bodies);
    }

    /**
     * {@link #build}, with files already in the class output, as Maven's resources phase leaves
     * {@code src/main/resources} there before the compiler runs.
     */
    private static Compiled buildWith(Map<String, String> resources, String... bodies) {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        for (String body : bodies) {
            sources.put("app." + typeName(body), IMPORTS + body);
        }
        try {
            Path out = Files.createTempDirectory(temp, "app");
            for (Map.Entry<String, String> r : resources.entrySet()) {
                Path file = out.resolve(r.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, r.getValue());
            }
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

        /** The part the wiring's accessor of that name returns. */
        Object part(String name) throws Exception {
            var accessor = wiring.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(wiring);
        }

        /** What the wiring answers {@code Wiring.icon}: the resource holding the mark, or null. */
        String icon() throws Exception {
            return (String) wiring.getClass().getSuperclass().getMethod("icon").invoke(wiring);
        }

        /** What the wiring answers {@code Wiring.computeQueue}: whether the device is made with a queue to lend. */
        boolean computeQueue() throws Exception {
            return (boolean) wiring.getClass().getSuperclass().getMethod("computeQueue").invoke(wiring);
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

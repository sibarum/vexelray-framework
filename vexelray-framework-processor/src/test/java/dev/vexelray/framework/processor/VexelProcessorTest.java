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
            // Another module's component and job, which no round of the application's compilation offers: they reach
            // the wiring only because Parts lists them and the application names Parts as a starter.
            Map.entry("lib.Asked", """
                package lib;
                public record Asked(String what) {}
                """),
            Map.entry("lib.Measured", """
                package lib;
                public record Measured(String what, int size) {
                    public static final sibarum.atchung.Topic<Measured> TOPIC =
                            sibarum.atchung.Topic.edge("lib.measured", Measured.class);
                }
                """),
            Map.entry("lib.Remote", """
                package lib;
                import dev.vexelray.framework.api.*;
                @Component(lane = "remote") @Publishes("lib.ask")
                public final class Remote {
                    @Subscribe(topic = "lib.measured") public void measured(Measured m) {}
                }
                """),
            Map.entry("lib.Measure", """
                package lib;
                import dev.vexelray.framework.api.*;
                @Job(topic = "lib.ask", reply = "lib.measured")
                public final class Measure {
                    public Measured run(Asked a) { return new Measured(a.what(), a.what().length()); }
                }
                """),
            Map.entry("lib.Ledgered", """
                package lib;
                import dev.vexelray.framework.api.*;
                @Component
                public final class Ledgered {
                    public Ledgered(sibarum.atchung.Inbox inbox) {}
                }
                """),
            Map.entry("lib.Parts", """
                package lib;
                import dev.vexelray.framework.api.*;
                @Configuration(components = {Remote.class, Ledgered.class}, jobs = Measure.class)
                public final class Parts {}
                """),
            Map.entry("dev.vexelray.framework.shell.Jobs", """
                package dev.vexelray.framework.shell;
                public final class Jobs {
                    public static <Q, R> void reply(Shell shell, String job, sibarum.atchung.Topic<Q> topic,
                                                    java.util.function.Function<Q, R> run,
                                                    sibarum.atchung.Topic<R> reply) {
                        Shell.LOG.add("job " + job + " " + topic.name() + " " + topic.payloadType().getSimpleName()
                                + " -> " + reply.name() + " " + reply.payloadType().getSimpleName());
                    }
                    public static <Q> void effect(Shell shell, String job, sibarum.atchung.Topic<Q> topic,
                                                  java.util.function.Consumer<Q> run) {
                        Shell.LOG.add("job " + job + " " + topic.name() + " " + topic.payloadType().getSimpleName());
                    }
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
                    sibarum.atchung.Inbox inbox(String name) { LOG.add("inbox " + name); return sibarum.atchung.Atchung.create().tick(null).inbox(name); }
                    public Senders senders() { return new Senders(); }
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
                    public BuildInfo build() { return null; }
                    public String appId() { return null; }
                    public java.util.List<String> formerNames() { return java.util.List.of(); }
                    public String busGraph() { return null; }
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
            Map.entry("dev.vexelray.framework.shell.BuildInfo", """
                package dev.vexelray.framework.shell;
                public record BuildInfo(String group, String artifact, String version, String source,
                                        java.util.List<String> stack, String name, String built, String notices,
                                        java.util.Map<String, String> issues) {}
                """),
            Map.entry("dev.vexelray.framework.shell.Senders", """
                package dev.vexelray.framework.shell;
                public final class Senders {
                    public <T> sibarum.atchung.Sender<T> to(sibarum.atchung.Topic<T> topic) {
                        Shell.LOG.add("senders " + topic.name());
                        return sibarum.atchung.Atchung.create().tick(null).inbox("senders").sender(topic);
                    }
                }
                """),
            Map.entry("dev.vexelray.framework.shell.Absences", """
                package dev.vexelray.framework.shell;
                import java.util.*;
                public final class Absences {
                    public static final List<String> REPORTED = new ArrayList<>();
                    public static void provided(String provider, String type, String... parts) {
                        REPORTED.add(provider + " " + type + " " + Arrays.toString(parts));
                    }
                    public static void handedBack(String provider, String type, String... parts) {
                        REPORTED.add(provider + " " + type + " kept " + Arrays.toString(parts));
                    }
                }
                """),
            Map.entry("dev.vexelray.framework.shell.Views", """
                package dev.vexelray.framework.shell;
                public final class Views {
                    public static <T> void mailbox(dev.vexelray.gui.core.Gui gui, String declared,
                                                   sibarum.atchung.Topic<T> topic, java.util.function.Consumer<T> on,
                                                   int capacity, String... keys) {
                        Shell.LOG.add("view mailbox " + declared + " " + topic.name() + " " + java.util.List.of(keys));
                    }
                }
                """),
            Map.entry("dev.vexelray.gui.widget.Tree", """
                package dev.vexelray.gui.widget;
                public final class Tree {
                    public static final sibarum.atchung.Topic<Chosen> CHOSEN =
                            sibarum.atchung.Topic.edge("tree.chosen", Chosen.class);
                    public record Chosen(String tree, Object item) {}
                }
                """),
            Map.entry("dev.vexelray.framework.shell.Inboxes", """
                package dev.vexelray.framework.shell;
                public final class Inboxes {
                    public static sibarum.atchung.Inbox of(Shell shell, String name, String lane, String... hears) {
                        LOG_HEARS.add(String.join(",", hears));
                        return shell.inbox(name);
                    }
                    public static <T> void mailbox(Shell shell, String component, String lane, String declared,
                                                   sibarum.atchung.Topic<T> topic, java.util.function.Consumer<T> on,
                                                   int capacity, String... keys) {
                        Shell.LOG.add("mailbox " + component + " " + lane + " " + declared + " " + topic.name() + " "
                                + capacity + " " + java.util.List.of(keys));
                    }
                    public static <T> sibarum.atchung.Sender<T> sender(Shell shell, String component, String lane,
                                                                       sibarum.atchung.Topic<T> topic) {
                        Shell.LOG.add("sender " + component + " " + lane + " " + topic.name());
                        return sibarum.atchung.Atchung.create().tick(null).inbox(component).sender(topic);
                    }
                    public static final java.util.List<String> LOG_HEARS = new java.util.ArrayList<>();
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
                public interface Pen {}
                """,
                """
                public record Edit(int at) {
                    public static final sibarum.atchung.Topic<Edit> TOPIC = sibarum.atchung.Topic.edge("edits", Edit.class);
                }
                """,
                """
                @Configuration
                public final class AppConfig {
                    @Provides public Store store(@Setting(value = "store.size", def = "4") int size) { return null; }
                    // Backs lib.Starter's @Default off, in every mode.
                    @Provides public Clock clock() { return null; }
                    // A final class from another jar, which the application cannot give an interface to.
                    @Provides public Device device(@Setting("device.name") String name) { return null; }
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
                    // A shareable provided value, a sender on a declared topic, and settings.
                    public Composer(Device device, sibarum.atchung.Sender<Edit> edits,
                                    @Setting(value = "compose.fast", def = "true") boolean fast,
                                    @Setting(value = "compose.recent") java.util.List<String> recent) {}
                }
                """,
                """
                @Component
                public final class Helper {
                    private int heard;
                    @Subscribe(topic = "edits") public void on(Edit e) { heard++; }
                }
                """,
                """
                final class Pump {}
                """));
    }

    // --- the colour rule ------------------------------------------------------------------------------------

    /**
     * T7.1: no application code runs on the main thread, so {@code @MainThread} is refused on any part of an
     * application, a type or a provider. A main-thread type compiled elsewhere ({@code lib.Window}) is still read as
     * one, below.
     */
    @Test
    void mainThreadIsRefusedOnAnApplicationsType() {
        onlyError(app("""
                @MainThread public final class Target {}
                """), "@MainThread on Target: no application code runs on the main thread (T7.1)");
    }

    @Test
    void mainThreadIsRefusedOnAProvider() {
        onlyError(app("""
                @Configuration
                public final class AppConfig {
                    @MainThread @Provides public Device device() { return null; }
                }
                """), "@MainThread on AppConfig.device: no application code runs on the main thread (T7.1)");
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

    // --- build identity -------------------------------------------------------------------------------------

    @Test
    void theBuildBillIsBakedIntoTheWiringAsAConstant() throws Exception {
        String bill = String.join("\n",
                "group=dev.example", "artifact=demo", "version=0.1.0-SNAPSHOT",
                "commit=ef816d9a", "dirty=true", "digest=0a1b2c3d4e5f", "source=ef816d9a+dirty.0a1b2c3d4e5f",
                "issues=https://example.dev/demo/issues", "name=Démo", "built=2026-10-24T10:00:00Z",
                "notices=https://example.dev/demo/NOTICES.txt",
                "stack.count=2",
                "stack.1=dev.vexelray.gui:vexelray-gui-core:0.1.1-SNAPSHOT@1e057d6",
                "stack.2=sibarum.atchung:atchung-probe:1.1-SNAPSHOT@4612f44",
                "stack.2.issues=https://example.dev/atchung/issues") + "\n";
        Compiled compiled = buildWith(Map.of(BuildBill.RESOURCE, bill), """
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """);
        assertEquals(List.of(), compiled.errors());
        String wiring = Files.readString(compiled.out().resolve("app/DemoAppWiring.java"));
        assertTrue(wiring.contains("public dev.vexelray.framework.shell.BuildInfo build() {\n"
                + "        return new dev.vexelray.framework.shell.BuildInfo(\n"
                + "                \"dev.example\", \"demo\", \"0.1.0-SNAPSHOT\",\n"
                + "                \"ef816d9a+dirty.0a1b2c3d4e5f\",\n"
                + "                java.util.List.of(\n"
                + "                        \"dev.vexelray.gui:vexelray-gui-core:0.1.1-SNAPSHOT@1e057d6\",\n"
                + "                        \"sibarum.atchung:atchung-probe:1.1-SNAPSHOT@4612f44\"),\n"
                + "                \"D\\u00e9mo\", \"2026-10-24T10:00:00Z\",\n"
                + "                \"https://example.dev/demo/NOTICES.txt\",\n"
                + "                java.util.Map.ofEntries(\n"
                + "                        java.util.Map.entry(\"dev.example:demo\", \"https://example.dev/demo/issues\"),\n"
                + "                        java.util.Map.entry(\"sibarum.atchung:atchung-probe\", \"https://example.dev/atchung/issues\")));"),
                wiring);
    }

    @Test
    void theBillsAppIdIsBakedInWhereThePomOverrodeIt() throws Exception {
        String bill = String.join("\n",
                "group=dev.example", "artifact=demo-vexel-demo", "version=0.1.0-SNAPSHOT",
                "commit=ef816d9a", "dirty=false", "digest=", "source=ef816d9a", "app.id=dev.example.demo",
                "stack.count=0") + "\n";
        Compiled compiled = buildWith(Map.of(BuildBill.RESOURCE, bill), """
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """);
        assertEquals(List.of(), compiled.errors());
        String wiring = Files.readString(compiled.out().resolve("app/DemoAppWiring.java"));
        assertTrue(wiring.contains("public String appId() {\n        return \"dev.example.demo\";"), wiring);
    }

    @Test
    void formerNamesAreBakedInAndMustMatchWhatTheInstallerReplaces() throws Exception {
        String bill = String.join("\n", "group=dev.example", "artifact=demo", "version=0.1", "commit=c", "dirty=false",
                "digest=", "source=c", "app.id=dev.example.demo", "installer.id=demo", "installer.replaces=old,older",
                "stack.count=0") + "\n";
        Compiled agreed = buildWith(Map.of(BuildBill.RESOURCE, bill), """
                @VexelApp(name = "demo", title = "Demo", formerNames = {"older", "old"})
                public final class DemoApp {}
                """);
        assertEquals(List.of(), agreed.errors());
        assertTrue(Files.readString(agreed.out().resolve("app/DemoAppWiring.java"))
                .contains("public java.util.List<String> formerNames() {\n        return java.util.List.of(\"older\", \"old\");"));

        Compiled missing = buildWith(Map.of(BuildBill.RESOURCE, bill), """
                @VexelApp(name = "demo", title = "Demo", formerNames = "old")
                public final class DemoApp {}
                """);
        assertTrue(missing.errors().stream().anyMatch(e -> e.contains("does not list [older]")), missing.errors().toString());

        Compiled settingsOnly = buildWith(Map.of(BuildBill.RESOURCE, bill), """
                @VexelApp(name = "demo", title = "Demo", formerNames = {"old", "older", "demo-vexel-demo"})
                public final class DemoApp {}
                """);
        assertEquals(List.of(), settingsOnly.errors(), "a former name that was never an install id is fine");

        Compiled renamedOnlyHere = buildWith(Map.of(BuildBill.RESOURCE, bill), """
                @VexelApp(name = "fresh", title = "Demo", formerNames = {"old", "older"})
                public final class DemoApp {}
                """);
        assertTrue(renamedOnlyHere.errors().stream().anyMatch(e -> e.contains("has the id \"demo\"")),
                renamedOnlyHere.errors().toString());

        Compiled self = buildWith(Map.of(), """
                @VexelApp(name = "demo", title = "Demo", formerNames = "demo")
                public final class DemoApp {}
                """);
        assertTrue(self.errors().stream().anyMatch(e -> e.contains("formerNames")), self.errors().toString());
    }

    @Test
    void withoutABillTheWiringLeavesTheBuildUnknown() throws Exception {
        Compiled compiled = buildWith(Map.of(), """
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """);
        assertEquals(List.of(), compiled.errors(), "a note, not an error: an application built without the plugin");
        assertFalse(Files.readString(compiled.out().resolve("app/DemoAppWiring.java")).contains(" build() {"));
        assertFalse(Files.readString(compiled.out().resolve("app/DemoAppWiring.java")).contains(" appId() {"));
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
        assertTrue(metadata.contains("\"glob\": \"app/demo-background.ico\""), "and the hidden one's: " + metadata);
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

    /** T2.2: a provider takes no main-thread value either, since none of an application's parts is the main thread's. */
    @Test
    void aProviderMayNotTakeAMainThreadValue() {
        onlyError(app("""
                public interface Surface {}
                """, """
                @Configuration
                public final class AppConfig {
                    @Provides public Surface surface(Window window) { return null; }
                }
                """), "T2.2: AppConfig.surface takes window, a main-thread value (Window is @MainThread). A main-thread"
                + " value is the framework's");
    }

    /** T2.1's case, a component that is also main-thread, is now the one refusal of {@code @MainThread}. */
    @Test
    void aComponentIsNotAlsoMainThread() {
        onlyError(app("""
                @MainThread @Component(lane = "compose")
                public final class Composer {}
                """), "@MainThread on Composer");
    }

    /**
     * T2.3: a component never holds another, on one lane or two. 0.1 allowed it where they shared a thread, which made
     * moving one to a lane of its own a compile error rather than a change of capacity.
     */
    @Test
    void componentsOnOneLaneMayNotHoldEachOther() {
        onlyError(app("""
                @Component
                public final class Composer { public Composer(Helper helper) {} }
                """, """
                @Component
                public final class Helper {}
                """), "T2.3: Composer holds Helper. A component never holds another, on one lane or two");
    }

    @Test
    void componentsOnDifferentLanesMayNotHoldEachOther() {
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer { public Composer(Indexer indexer) {} }
                """, """
                @Component(lane = "index")
                public final class Indexer {}
                """), "T2.3: Composer holds Indexer");
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
    void anEmptyRecordIsAnEffectAndIsToldTheMarkerShapeRatherThanAnInterface() {
        List<String> errors = app("""
                public record Restored() {}
                """, """
                @Configuration
                public final class AppConfig {
                    @Provides public Restored restore() { return null; }
                }
                """);
        onlyError(errors, "returns Restored, an empty record, which reads as a recipe run for its effect");
        onlyError(errors, "return void, and the wiring calls it in the phase its parameters put it in");
        assertFalse(errors.get(0).contains("Return an interface"), errors.get(0));
    }

    /**
     * A recipe run for its effect returns void: called once, in the phase its parameters put it in, and holding
     * nothing. Two of them are not a conflict, since neither provides anything (VexelRay IDE's {@code Restored}, Pix's window
     * binding and the template's close gate each returned a marker to get past the error this used to be).
     */
    // --- a component's inbox on the logic lane ---------------------------------------------------------------

    /**
     * A component that takes an {@code Inbox} is handed its own, named after it, in the first phase: the logic lane's,
     * where everything it hears runs. Nothing is placed for it, since it has no lane of its own.
     */
    @Test
    void aComponentIsHandedItsOwnInboxInTheFirstPhase() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @Component
                public final class Editor {
                    public Editor(sibarum.atchung.Inbox inbox) {
                        dev.vexelray.framework.shell.Shell.LOG.add("built with " + inbox.name());
                    }
                }
                """);
        assertEquals(List.of(), compiled.errors());
        String wiring = Files.readString(compiled.out().resolve("app/DemoAppWiring.java"));
        assertTrue(wiring.contains("dev.vexelray.framework.shell.Inboxes.of(shell, \"app.Editor\", \"<default>\")"),
                wiring);

        Run run = compiled.run(dev.vexelray.framework.api.RunMode.FRAMES, Map.of());
        assertEquals(List.of("inbox app.Editor", "built with app.Editor"), run.phase("config"));
    }

    @Test
    void aComponentThatTakesItsInboxDeclaresNoMailbox() {
        onlyError(app(EDIT, """
                @Component
                public final class Editor {
                    public Editor(sibarum.atchung.Inbox inbox) {}
                    @Subscribe(topic = "edits") public void on(Edit e) {}
                }
                """), "Editor takes its Inbox and also declares a @Subscribe mailbox");
    }

    /** A payload with its topic declared beside it: what most fixtures below hear. */
    private static final String EDIT = """
            public record Edit(int at) {
                public static final sibarum.atchung.Topic<Edit> TOPIC = sibarum.atchung.Topic.edge("edits", Edit.class);
            }
            """;

    /** A second payload and topic, for a component hearing two channels. */
    private static final String MOVE = """
            public record Move(int by) {
                public static final sibarum.atchung.Topic<Move> TOPIC = sibarum.atchung.Topic.edge("moves", Move.class);
            }
            """;

    @Test
    void anInboxIsAComponentsAndNoProvidersOrJobs() {
        onlyError(app("""
                @Configuration
                final class Recipes {
                    @Provides Clock clock(sibarum.atchung.Inbox inbox) { return null; }
                }
                """), "takes an Inbox, which is a component's own");
    }

    /**
     * T4: a component that hears through its inbox says so with {@code @Hears}, and the channel checks read it: a job's
     * reply it hears is heard, and the wiring hands the names to the inbox for the startup check.
     */
    @Test
    void aReplyHeardThroughAnInboxIsHeardWhenItIsDeclared() throws Exception {
        String job = """
                @Job(topic = "ask", reply = "told")
                public final class Answerer {
                    public Told run(Ask a) { return new Told(a.q()); }
                }
                """;
        String app = """
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """;
        String ask = "public record Ask(String q) {}";
        String told = "public record Told(String a) {}";
        assertTrue(build(app, ask, told, job, """
                @Component
                public final class Editor { public Editor(sibarum.atchung.Inbox inbox) {} }
                """).errors().stream().anyMatch(e -> e.contains("replies on \"told\", and no component subscribes")),
                "without the declaration the reply is unheard");

        Compiled compiled = build(app, ask, told, job, """
                @Component @Hears({"told", "other"})
                public final class Editor { public Editor(sibarum.atchung.Inbox inbox) {} }
                """);
        assertEquals(List.of(), compiled.errors());
        assertTrue(Files.readString(compiled.out().resolve("app/DemoAppWiring.java"))
                .contains("Inboxes.of(shell, \"app.Editor\", \"<default>\", \"told\", \"other\")"));
    }

    /** A component that declares a lane of its own is handed its inbox on that lane of the logic's tick. */
    @Test
    void aComponentOnADeclaredLaneIsHandedItsInboxOnThatLane() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @Component(lane = "physics")
                public final class Physics { public Physics(sibarum.atchung.Inbox inbox) {} }
                """);
        assertEquals(List.of(), compiled.errors());
        assertTrue(Files.readString(compiled.out().resolve("app/DemoAppWiring.java"))
                .contains("Inboxes.of(shell, \"app.Physics\", \"physics\")"));
    }

    @Test
    void hearsIsForAComponentThatTakesItsInbox() {
        onlyError(app("""
                @Component @Hears("told")
                public final class Editor { public Editor() {} }
                """), "Editor declares @Hears and takes no Inbox");
    }

    // --- a view on the GUI thread ----------------------------------------------------------------------------

    /**
     * T7.4: a view's mailboxes are the frame's, not a lane's. Nothing is placed for it, it is built once the Gui exists
     * even if it takes nothing, a widget's topic is the widget's own constant (so its kind is the publisher's), and a
     * widget's message is not a crossing, so its {@code Object} is not refused.
     */
    @Test
    void aViewHearsAWidgetOnTheGuiThreadWithTheWidgetsOwnTopic() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                @View
                public final class Navigator {
                    public Navigator() {}
                    @Subscribe(topic = "tree.chosen") public void chosen(dev.vexelray.gui.widget.Tree.Chosen c) {}
                }
                """);
        assertEquals(List.of(), compiled.errors());
        String wiring = Files.readString(compiled.out().resolve("app/DemoAppWiring.java"));
        assertTrue(wiring.contains("dev.vexelray.framework.shell.Views.mailbox(shell.gui(), \"tree.chosen\","
                + " dev.vexelray.gui.widget.Tree.CHOSEN, navigator::chosen, 64);"), wiring);

        Run run = compiled.run(dev.vexelray.framework.api.RunMode.FRAMES, Map.of());
        assertEquals(List.of(), run.phase("config"));
        assertEquals(List.of("view mailbox tree.chosen tree.chosen []"), run.phase("tree"));
    }

    @Test
    void aComponentOnALaneHearingAWidgetsObjectIsStillACrossing() {
        assertTrue(app("""
                @Component
                public final class Logic {
                    @Subscribe(topic = "tree.chosen") public void chosen(dev.vexelray.gui.widget.Tree.Chosen c) {}
                }
                """).stream().anyMatch(e -> e.startsWith("T2.4: Logic.chosen receives")));
    }

    @Test
    void onlyAViewMayHoldAView() {
        onlyError(app("""
                @View
                public final class Navigator { public Navigator() {} }
                """, """
                @Component
                public final class Editor { public Editor(Navigator nav) {} }
                """), "T7.4: Editor takes the view Navigator");
    }

    @Test
    void aClassIsAComponentOrAViewAndNotBoth() {
        onlyError(app("""
                @View @Component
                public final class Both { public Both() {} }
                """), "Both is both a @Component and a @View");
    }

    @Test
    void aVoidRecipeRunsForItsEffectInItsPhase() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                final class Session {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides Session session() { return new Session(); }
                    @Provides void restore(Session session, dev.vexelray.gui.core.Gui gui) {
                        dev.vexelray.framework.shell.Shell.LOG.add("restored " + (session != null));
                    }
                    @Provides void arm() { dev.vexelray.framework.shell.Shell.LOG.add("armed"); }
                }
                """);
        assertEquals(List.of(), compiled.errors());
        String wiring = Files.readString(compiled.out().resolve("app/DemoAppWiring.java"));
        assertFalse(wiring.contains(" restore;") || wiring.contains(" restore()  {"), wiring);
        assertTrue(wiring.contains("recipes.restore(session, shell.gui());"), wiring);

        Run run = compiled.run(dev.vexelray.framework.api.RunMode.FRAMES, Map.of());
        assertEquals(List.of("armed"), run.phase("config"), "no parameters: the first phase");
        assertEquals(List.of(), run.phase("model"));
        assertEquals(List.of(), run.phase("gui"));
        assertEquals(List.of("restored true"), run.phase("tree"), "it takes the Gui, so it runs where the Gui is handed out");
    }

    /** T2.2: a recipe run for its effect that takes the window would be application code on the main thread. */
    @Test
    void aVoidRecipeTakingTheMainThreadIsRefused() {
        onlyError(app("""
                @Configuration
                public final class AppConfig {
                    @Provides public void bind(dev.vexelray.gui.core.app.GuiApp app) {}
                }
                """), "T2.2: AppConfig.bind takes app, a main-thread value (GuiApp is the main thread's");
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

    // --- channels: @Subscribe, Sender and @Publishes ---------------------------------------------------------

    /**
     * Every component is on the logic lane's tick: its {@code @Subscribe} methods are subscriptions on its inbox, on
     * the lane it declares, each with the payload's declared topic, so the channel's kind is its declaration's. No
     * placement and no loss policy: the topic's kind is the policy.
     */
    @Test
    void aSubscribeBecomesAMailboxOnTheComponentsInboxOnItsLane() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, EDIT, MOVE, """
                @Component(lane = "compose")
                public final class Composer {
                    @Subscribe(topic = "edits", capacity = 8) public void edited(Edit e) {}
                    @Subscribe(topic = "moves") public void moved(Move m) {}
                }
                """);
        assertEquals(List.of(), compiled.errors());
        assertTrue(Files.readString(compiled.out().resolve("app/DemoAppWiring.java")).contains(
                "dev.vexelray.framework.shell.Inboxes.mailbox(shell, \"app.Composer\", \"compose\", \"edits\","
                        + " app.Edit.TOPIC, composer::edited, 8);"));
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());
        assertEquals(List.of("mailbox app.Composer compose edits edits 8 []",
                "mailbox app.Composer compose moves moves 64 []"), run.phase("config"));
    }

    @Test
    void componentsThatNameNoLaneSubscribeOnTheLogicLane() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, EDIT, MOVE, """
                @Component
                public final class One { @Subscribe(topic = "edits") public void on(Edit e) {} }
                """, """
                @Component
                public final class Two { @Subscribe(topic = "moves") public void on(Move m) {} }
                """);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());
        assertEquals(List.of("mailbox app.One <default> edits edits 64 []", "mailbox app.Two <default> moves moves 64 []"),
                run.phase("config"));
    }

    /** T4.8: a key filter is a declaration the wiring hands to the mailbox, on a state topic. */
    @Test
    void aKeyFilterIsHandedToTheMailbox() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                public record Cell(String id, int value) {
                    public static final sibarum.atchung.Topic<Cell> TOPIC =
                            sibarum.atchung.Topic.state("cells", Cell.class, Cell::id);
                }
                """, """
                @Component
                public final class Sum {
                    @Subscribe(topic = "cells", key = {"main.a", "main.b"}) public void on(Cell c) {}
                }
                """);
        assertEquals(List.of(), compiled.errors());
        assertTrue(Files.readString(compiled.out().resolve("app/DemoAppWiring.java")).contains(
                "app.Cell.TOPIC, sum::on, 64, \"main.a\", \"main.b\");"));
        assertEquals(List.of("mailbox app.Sum <default> cells cells 64 [main.a, main.b]"),
                compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).phase("config"));
    }

    @Test
    void aKeyFilterOnAnEdgeIsAnError() {
        onlyError(app(EDIT, """
                @Component
                public final class Sink { @Subscribe(topic = "edits", key = "main.a") public void on(Edit e) {} }
                """), "T4.8: @Subscribe Sink.on filters by key [main.a], and TOPIC is an edge");
    }

    @Test
    void aKeyFilterNamesEachKeyOnce() {
        onlyError(app("""
                public record Cell(String id) {
                    public static final sibarum.atchung.Topic<Cell> TOPIC =
                            sibarum.atchung.Topic.state("cells", Cell.class, Cell::id);
                }
                """, """
                @Component
                public final class Sink { @Subscribe(topic = "cells", key = {"a", "a"}) public void on(Cell c) {} }
                """), "filters by the keys [a, a], which has a blank or a repeated one");
    }

    /** T4.1: a payload in this compilation hears and sends on the one topic declared beside it. */
    @Test
    void aSubscribedPayloadWithNoDeclaredTopicIsAnError() {
        onlyError(app("""
                public record Edit(int at) {}
                """, """
                @Component
                public final class Sink { @Subscribe(topic = "edits") public void on(Edit e) {} }
                """), "T4.1: @Subscribe Sink.on hears Edit, and no topic of it is declared");
    }

    @Test
    void aPayloadWithTwoDeclaredTopicsIsAnError() {
        onlyError(app("""
                public record Edit(int at) {
                    public static final sibarum.atchung.Topic<Edit> A = sibarum.atchung.Topic.edge("a", Edit.class);
                    public static final sibarum.atchung.Topic<Edit> B = sibarum.atchung.Topic.edge("b", Edit.class);
                }
                """, """
                @Component
                public final class Sink { @Subscribe(topic = "a") public void on(Edit e) {} }
                """), "and 2 topics of it are declared (A, B)");
    }

    /** T4.1: a topic in source says its kind; {@code Topic.of} does not, wherever the constant is and whoever hears it. */
    @Test
    void aTopicMadeWithOfDoesNotDeclareItsKind() {
        onlyError(app("""
                public final class Messages {
                    public record Said(String s) {}
                    public static final sibarum.atchung.Topic<Said> SAID = sibarum.atchung.Topic.of("said", Said.class);
                }
                """), "T4.1: the topic SAID does not declare its kind: it is made with Topic.of");
    }

    @Test
    void aTopicNameCarryingTwoPayloadTypesIsAnError() {
        onlyError(app("""
                public record Edit(int at) {
                    public static final sibarum.atchung.Topic<Edit> TOPIC = sibarum.atchung.Topic.edge("edits", Edit.class);
                }
                """, """
                public record Move(int by) {
                    public static final sibarum.atchung.Topic<Move> TOPIC = sibarum.atchung.Topic.edge("edits", Move.class);
                }
                """, """
                @Component(lane = "a")
                public final class Sink { @Subscribe(topic = "edits") public void on(Edit e) {} }
                """, """
                @Component(lane = "b")
                public final class Other { @Subscribe(topic = "edits") public void on(Move m) {} }
                """), "T4.1: the topic \"edits\" carries");
    }

    /**
     * ROADMAP fork 5: a send is declared by construction. A component's {@code Sender<T>} is its own inbox's, on its
     * lane, so what it sends is ordered by the tick; a view's or a recipe's is an ordinary publish.
     */
    @Test
    void aSenderIsBuiltOnThePayloadsDeclaredTopic() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, EDIT, """
                @Component(lane = "compose")
                public final class Composer { public Composer(sibarum.atchung.Sender<Edit> edits) {} }
                """, """
                @View
                public final class Toolbar { public Toolbar(sibarum.atchung.Sender<Edit> edits) {} }
                """, """
                @Configuration
                final class Recipes {
                    @Provides void start(sibarum.atchung.Sender<Edit> edits) {}
                }
                """);
        assertEquals(List.of(), compiled.errors());
        String wiring = Files.readString(compiled.out().resolve("app/DemoAppWiring.java"));
        assertTrue(wiring.contains("new app.Composer(dev.vexelray.framework.shell.Inboxes.sender(shell, \"app.Composer\","
                + " \"compose\", app.Edit.TOPIC))"), wiring);
        assertTrue(wiring.contains("new app.Toolbar(shell.senders().to(app.Edit.TOPIC))"), wiring);
        assertTrue(wiring.contains("recipes.start(shell.senders().to(app.Edit.TOPIC))"), wiring);
        assertEquals(java.util.Set.of("sender app.Composer compose edits", "senders edits"), java.util.Set.copyOf(
                compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).phase("config")));
    }

    @Test
    void aSenderWhosePayloadHasNoDeclaredTopicIsAnError() {
        onlyError(app("""
                public record Edit(int at) {}
                """, """
                @Component
                public final class Composer { public Composer(sibarum.atchung.Sender<Edit> edits) {} }
                """), "sends Edit, and no topic of it is declared");
    }

    @Test
    void aJobAnswersWithItsReplyAndTakesNoSender() {
        onlyError(app(MESSAGES_ASK, MESSAGES_TOLD, TOLD, EDIT, """
                @Job(topic = "ask", reply = "told")
                public final class Answer {
                    public Answer(sibarum.atchung.Sender<Edit> edits) {}
                    public Told run(Ask a) { return new Told(a.q()); }
                }
                """), "@Job Answer takes edits, a Sender. A job answers with its reply");
    }

    // --- a part compiled in another module ----------------------------------------------------------------------

    /**
     * An application split into modules: lib.Remote and lib.Measure are class files to this compilation, which no
     * round offers. Named through the starter that lists them, they are built, placed and subscribed as if they were
     * the application's own, and checked against its channels: Remote's mailbox is what hears Measure's reply.
     */
    @Test
    void aComponentAndAJobFromAnotherModuleAreWiredThroughTheConfigurationThatListsThem() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo", starters = Parts.class)
                public final class DemoApp {}
                """);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());
        assertEquals(List.of("mailbox lib.Remote remote lib.measured lib.measured 64 []",
                "inbox lib.Ledgered",
                "job lib.Measure lib.ask Asked -> lib.measured Measured"), run.phase("config"));
        String graph = run.busGraph();
        assertTrue(graph.contains("publishes lib.Remote topic=lib.ask"), "a send read off a class file:\n" + graph);
        assertTrue(graph.contains("publishes lib.Measure topic=lib.measured"), "a job answers:\n" + graph);
    }

    @Test
    void withoutTheStarterAnotherModulesComponentIsNotBuilt() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """);
        assertEquals(List.of(), compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).phase("config"));
    }

    @Test
    void aConfigurationListsOnlyComponentsAsComponents() {
        onlyError(app("""
                @Configuration(components = Measure.class)
                public final class Wrong {}
                """), "lists lib.Measure among its components, and it is not a @Component. It is a @Job: list it"
                + " under jobs");
    }

    // --- @Job --------------------------------------------------------------------------------------------------

    @Test
    void aJobIsBuiltAndSubscribedOnTheJobLane() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                public record Read(String path) {}
                """, """
                public record Text(String path, String text) {
                    public static final sibarum.atchung.Topic<Text> TOPIC = sibarum.atchung.Topic.edge("file.text", Text.class);
                }
                """, """
                @Job(topic = "file.read", reply = "file.text")
                public final class ReadFile {
                    private final Device device;
                    public ReadFile(Device device) { this.device = device; }
                    public Text run(Read r) { return new Text(r.path(), ""); }
                }
                """, """
                @Job(topic = "file.write")
                public final class WriteFile {
                    public void run(Text t) {}
                }
                """, """
                @Component(lane = "editor")
                public final class Editor { @Subscribe(topic = "file.text") public void read(Text t) {} }
                """, """
                @Configuration
                public final class Parts { @Provides public Device device() { return null; } }
                """);
        assertEquals(List.of(), compiled.errors());
        List<String> config = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).phase("config");
        assertTrue(config.contains("job app.ReadFile file.read Read -> file.text Text"), config.toString());
        assertTrue(config.contains("job app.WriteFile file.write Text"), config.toString());
    }

    private static String job(String body) {
        return """
                @Job(topic = "ask", reply = "told")
                public final class Answer {
                %s
                }
                """.formatted(body);
    }

    private static final String TOLD = """
            @Component(lane = "listener")
            public final class Listener { @Subscribe(topic = "told") public void told(Told t) {} }
            """;

    private static final String MESSAGES_ASK = """
            public record Ask(String q) {}
            """;

    private static final String MESSAGES_TOLD = """
            public record Told(String a) {
                public static final sibarum.atchung.Topic<Told> TOPIC = sibarum.atchung.Topic.edge("told", Told.class);
            }
            """;

    @Test
    void aJobKeepsNoStateThatChanges() {
        onlyError(app(MESSAGES_ASK, MESSAGES_TOLD, TOLD, job("""
                    private int asked;
                    public Told run(Ask a) { return new Told(a.q()); }
                """)), "has the field asked, which is not final");
    }

    @Test
    void aJobsFieldIsShareableOrSupplied() {
        onlyError(app(MESSAGES_ASK, MESSAGES_TOLD, TOLD, job("""
                    private final java.util.List<String> seen = new java.util.ArrayList<>();
                    public Told run(Ask a) { return new Told(a.q()); }
                """)), "has the field seen, a java.util.List<java.lang.String>, which is neither shareable nor"
                + " something the container supplies");
    }

    @Test
    void aJobDeclaresNoThrows() {
        onlyError(app(MESSAGES_ASK, MESSAGES_TOLD, TOLD, job("""
                    public Told run(Ask a) throws java.io.IOException { return new Told(a.q()); }
                """)), "Answer.run declares java.io.IOException. A failure a job expects is part of its reply");
    }

    @Test
    void aJobsRequestCrossesALaneAsItself() {
        onlyError(app(MESSAGES_TOLD, TOLD, """
                public final class Ask { public int[] counts; }
                """, job("""
                    public Told run(Ask a) { return new Told(""); }
                """)), "T2.4: Answer.run's request on \"ask\" is app.Ask");
    }

    @Test
    void aReplyNobodyHearsIsALostResult() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, MESSAGES_ASK, MESSAGES_TOLD, job("""
                    public Told run(Ask a) { return new Told(a.q()); }
                """)), "@Job Answer replies on \"told\", and no component subscribes to it");
    }

    @Test
    void aReplyMustBeNamedWhenOneIsReturned() {
        onlyError(app(MESSAGES_ASK, MESSAGES_TOLD, """
                @Job(topic = "ask")
                public final class Answer { public Told run(Ask a) { return new Told(a.q()); } }
                """), "Answer.run returns app.Told, and @Job Answer names no reply");
    }

    @Test
    void oneJobPerTopic() {
        onlyError(app(MESSAGES_ASK, """
                @Job(topic = "ask")
                public final class One { public void run(Ask a) {} }
                """, """
                @Job(topic = "ask")
                public final class Two { public void run(Ask a) {} }
                """), "both take requests on \"ask\"");
    }

    @Test
    void aJobIsReachedByPublishingNeverHeld() {
        onlyError(app(MESSAGES_ASK, """
                @Job(topic = "ask")
                public final class Answer { public void run(Ask a) {} }
                """, """
                @Component(lane = "asker")
                public final class Asker { public Asker(Answer answer) {} }
                """), "Asker takes the job Answer. A job is reached by publishing its topic");
    }

    @Test
    void aJobTakesNoComponent() {
        onlyError(app(MESSAGES_ASK, """
                @Component(lane = "asker")
                public final class Asker {}
                """, """
                @Job(topic = "ask")
                public final class Answer { public Answer(Asker asker) {} public void run(Ask a) {} }
                """), "T2.3: @Job Answer takes the component Asker");
    }

    @Test
    void aJobsRequestTopicCarriesOneType() {
        onlyError(app(MESSAGES_ASK, MESSAGES_TOLD, """
                @Component(lane = "watcher")
                public final class Watcher { @Subscribe(topic = "ask") public void saw(Told t) {} }
                """, """
                @Job(topic = "ask")
                public final class Answer { public void run(Ask a) {} }
                """), "T4.1: the topic \"ask\" carries app.Ask at Answer.run and app.Told to Watcher.saw");
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
                                   java.util.UUID id) {
                    public static final sibarum.atchung.Topic<Edit> T = sibarum.atchung.Topic.edge("t", Edit.class);
                }
                """, sink("Edit")));
    }

    @Test
    void aFinalClassOfFinalValuesCanCrossALane() {
        assertEquals(List.of(), app("""
                public final class Point {
                    public static final sibarum.atchung.Topic<Point> T = sibarum.atchung.Topic.edge("t", Point.class);
                    private final int x;
                    private final int y;
                    public Point(int x, int y) { this.x = x; this.y = y; }
                }
                """, sink("Point")));
    }

    @Test
    void aSealedInterfaceOfRecordsCanCrossALane() {
        assertEquals(List.of(), app("""
                public sealed interface Shape permits Circle, Square {
                    sibarum.atchung.Topic<Shape> T = sibarum.atchung.Topic.edge("t", Shape.class);
                }
                """, """
                public record Circle(int r) implements Shape {}
                """, """
                public record Square(int side) implements Shape {}
                """, sink("Shape")));
    }

    @Test
    void aListSetOrMapOfShareableValuesMayCrossInAPayload() {
        assertEquals(List.of(), app("""
                public record Edit(java.util.List<String> lines, java.util.Set<Long> ids,
                                   java.util.Map<String, java.util.List<Integer>> marks) {
                    public static final sibarum.atchung.Topic<Edit> T = sibarum.atchung.Topic.edge("t", Edit.class);
                    public Edit {
                        lines = java.util.List.copyOf(lines);
                        ids = java.util.Set.copyOf(ids);
                        marks = java.util.Map.copyOf(marks);
                    }
                }
                """, sink("Edit")));
    }

    @Test
    void aCollectionOfSomethingMutableIsRefusedAndTheMessageNamesThePath() {
        onlyError(app("""
                public record Edit(java.util.List<StringBuilder> lines) {}
                """, sink("Edit")), "T2.4: Sink.on receives app.Edit on \"t\", and it cannot cross a lane as itself:"
                + " Edit.lines -> List of java.lang.StringBuilder -> AbstractStringBuilder.value: is not final");
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
                public final class Derived extends Base {
                    public static final sibarum.atchung.Topic<Derived> T = sibarum.atchung.Topic.edge("t", Derived.class);
                    private final String y = "";
                }
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
                    public static final class Ping {
                        public static final sibarum.atchung.Topic<Ping> T = sibarum.atchung.Topic.edge("t", Ping.class);
                    }
                    @Subscribe(topic = "t") public void on(Ping p) {}
                }
                """));
    }

    @Test
    void aSealedClassOfFinalSubclassesCanCrossALane() {
        assertEquals(List.of(), app("""
                public sealed abstract class Shape permits Circle {
                    public static final sibarum.atchung.Topic<Shape> T = sibarum.atchung.Topic.edge("t", Shape.class);
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

    // --- the declared graph, as text (T4.5) ---------------------------------------------------------------------
    /**
     * A publisher that is not a component is a chip too; a method's send is its type's; a constructor's
     * {@code Sender<T>} is a send on T's declared topic, by the name its constant is made with; a component this build
     * does not construct is not drawn; and every mailbox is {@code FAIL}, which is what the tick does with an edge one
     * past its capacity, and what a state topic never reaches.
     */
    @Test
    void theWiringCarriesTheDeclaredGraphAsTheBusGraphText() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                public record Job(int id) {
                    public static final sibarum.atchung.Topic<Job> TOPIC = sibarum.atchung.Topic.edge("witness.job", Job.class);
                }
                """, """
                public record Ping(int n) {
                    public static final sibarum.atchung.Topic<Ping> TOPIC = sibarum.atchung.Topic.edge("witness.ping", Ping.class);
                }
                """, """
                @Component(lane = "worker")
                public final class Worker {
                    @Subscribe(topic = "witness.job", capacity = 8) public void onJob(Job j) {}
                    @Publishes("witness.ping") public void reply() {}
                }
                """, """
                @Component
                public final class Echo {
                    @Subscribe(topic = "witness.ping") public void onPing(Ping p) {}
                }
                """, """
                @Component(lane = "pinger")
                public final class Pinger { public Pinger(sibarum.atchung.Sender<Ping> ping) {} }
                """, """
                @Publishes({"witness.ping", "witness.job"})
                public final class Ui {}
                """, """
                @Component(lane = "ghost") @ConditionalOnType("does.not.Exist") @Publishes("witness.job")
                public final class Ghost {}
                """);
        assertEquals("""
                # vexelray bus graph 1
                component app.Echo lane=<default>
                component app.Pinger lane=pinger
                component app.Worker lane=worker
                publisher app.Ui lane=-
                mailbox app.Echo#onPing topic=witness.ping type=app.Ping overflow=FAIL capacity=64
                mailbox app.Worker#onJob topic=witness.job type=app.Job overflow=FAIL capacity=8
                publishes app.Pinger topic=witness.ping
                publishes app.Ui topic=witness.job
                publishes app.Ui topic=witness.ping
                publishes app.Worker topic=witness.ping
                """, compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).busGraph());
    }

    @Test
    void anApplicationThatDeclaresNoChannelHasAnEmptyGraphRatherThanNone() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """);
        assertEquals("# vexelray bus graph 1\n",
                compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of()).busGraph());
    }

    // --- cycles: T4.4 and T4.7 are withdrawn ------------------------------------------------------------------

    /**
     * Under the tick no send blocks: a message is queued for the next round and the sender carries on, so a cycle of
     * declared sends is request and response, and a loop is the hop limit's to catch at run time (T5.7). 0.1's
     * {@code BLOCK} mailbox, and the checks of a blocking cycle (T4.4) and a blocking send from the main thread (T4.7),
     * are gone with {@code Overflow}.
     */
    @Test
    void aCycleOfDeclaredSendsIsRequestAndResponseAndIsFine() {
        assertEquals(List.of(), app("""
                public record Order(int id) {
                    public static final sibarum.atchung.Topic<Order> TOPIC = sibarum.atchung.Topic.edge("orders", Order.class);
                }
                """, """
                public record Ack(int id) {
                    public static final sibarum.atchung.Topic<Ack> TOPIC = sibarum.atchung.Topic.edge("acks", Ack.class);
                }
                """, """
                @Component(lane = "a")
                public final class Orders {
                    public Orders(sibarum.atchung.Sender<Order> orders) {}
                    @Subscribe(topic = "acks") public void acked(Ack a) {}
                }
                """, """
                @Component(lane = "b")
                public final class Billing {
                    public Billing(sibarum.atchung.Sender<Ack> acks) {}
                    @Subscribe(topic = "orders") public void billed(Order o) {}
                }
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

    /**
     * T7.1: a frame hook is application code on the main thread, in the frame budget, so {@code @BeforeFrame} is
     * refused wherever it is, and the refusal says where the work goes instead. 0.1's checks of a hook's shape (no
     * parameters, no throws, not a component's, something builds it) went with it.
     */
    @Test
    void aFrameHookIsRefused() {
        onlyError(app("""
                public final class Hooks {
                    @BeforeFrame void drain() {}
                }
                """), "@BeforeFrame Hooks.drain: no application code runs on the main thread (T7.1)");
        onlyError(app("""
                @Component(lane = "compose")
                public final class Composer {
                    @BeforeFrame void drain() {}
                }
                """), "@BeforeFrame Composer.drain: no application code runs on the main thread (T7.1)");
    }

    @Test
    void aStarterMustBeAConfiguration() {
        onlyError(app("""
                @VexelApp(name = "demo", title = "Demo", starters = Device.class)
                public final class DemoApp {}
                """), "names lib.Device as a starter, and it is not a @Configuration class");
    }

    /**
     * A starter whose module left the class path — MainFrame dropping vexelray-codec and still naming its
     * ImageStarter — used to crash the processor with a ClassCastException, which is all the build said. Now
     * javac's own error stands, and the processor says what it most likely means.
     */
    @Test
    void aStarterThatDoesNotResolveIsAnErrorNotACrash() {
        List<String> errors = app("""
                @VexelApp(name = "demo", title = "Demo", starters = {Starter.class, Gone.class})
                public final class DemoApp {}
                """);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Gone")), () -> "javac names the class:\n" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("names a starter that does not resolve")),
                () -> "and the processor says what that means:\n" + errors);
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
            record Task(String what) {
                static final sibarum.atchung.Topic<Task> TOPIC = sibarum.atchung.Topic.edge("jobs", Task.class);
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
                @Provides Socket socket(dev.vexelray.framework.shell.Shell shell) { return new Socket(shell); }
                @OnMode(RunMode.WINDOWED) @Provides Preview preview(Model model) { return new Preview(model); }
            }
            """,
            """
            @Component(lane = "work")
            public final class Worker {
                public Worker(Model model) { dev.vexelray.framework.shell.Shell.LOG.add("worker"); }
                @Subscribe(topic = "jobs") public void job(Task task) {}
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
        assertEquals(null, run.part("ui"), "the Ui is built in TREE, which has not run");
        run.phase("model");
        run.phase("gui");
        assertEquals(null, run.part("ui"), "the Gui is configured in GUI and handed to parts only from TREE");
        run.phase("tree");
        assertEquals("app.Ui", run.part("ui").getClass().getName());
    }
    @Test
    void eachPartIsBuiltInThePhaseItsParametersPutItIn() throws Exception {
        Compiled compiled = build(RECORDING_APP);
        assertEquals(List.of(), compiled.errors());
        Run run = compiled.run(dev.vexelray.framework.api.RunMode.WINDOWED, Map.of());

        // CONFIG: the look is applied the moment it exists; the model takes only a setting; the worker takes the
        // model, a CONFIG value, and its mailbox is subscribed on the logic lane's tick as it is built.
        assertEquals(List.of("look", "appearance applied", "model 4", "preview", "worker",
                "mailbox app.Worker work jobs jobs 64 []"),
                run.phase("config"));
        assertEquals(List.of(), run.phase("model"));
        assertEquals(List.of(), run.phase("gui"), "GUI is the framework's: no part is handed the Gui in it");
        assertEquals(List.of("ui"), run.phase("tree"), "the Gui is handed to parts from TREE");
        assertEquals(List.of(), run.phase("window"), "the GuiApp is the main thread's, and no part takes it");
        assertEquals(List.of("socket"), run.phase("attach"), "a part taking the whole Shell is built last");

        assertEquals(0, run.hooks(), "no application part reaches the frame (T7.1)");
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
                """), "provides the Appearance, and takes something that exists only from TREE");
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
        assertEquals(List.of(), run.phase("gui"));
        assertEquals(List.of("clipboard handed back"), run.phase("tree"), "a provider taking the Gui is built in TREE");
        run.shutdown();
        assertEquals(List.of(), run.phase("shutdown"), "the wiring registers neither: the shell owns both");
    }

    /**
     * {@code Provides}' promise: a provider answering null is absent, its dependents are still built with the null,
     * and the framework reports it once — naming the provider and each part then built with it, a part guarded to
     * another mode left out. A hand-back's report says the framework keeps its own. A provider answering a value
     * reports nothing.
     */
    @Test
    void aProviderAnsweringNullIsReportedOnceNamingWhatWasBuiltWithIt() throws Exception {
        Compiled compiled = build("""
                @VexelApp(name = "demo", title = "Demo")
                public final class DemoApp {}
                """, """
                final class Backend {}
                """, """
                final class User {
                    User(Backend backend) { dev.vexelray.framework.shell.Shell.LOG.add("user got " + backend); }
                }
                """, """
                final class Viewer {}
                """, """
                final class Present {}
                """, """
                @Configuration
                final class Recipes {
                    @Provides Backend backend() { return null; }
                    @Provides User user(Backend backend) { return new User(backend); }
                    @OnMode(RunMode.WINDOWED) @Provides Viewer viewer(Backend backend) { return new Viewer(); }
                    @Provides Present present() { return new Present(); }
                    @Provides dev.vexelray.framework.shell.InputBackend input() { return null; }
                }
                """);
        assertEquals(List.of(), compiled.errors());
        String wiring = Files.readString(compiled.out().resolve("app/DemoAppWiring.java"));
        assertTrue(wiring.contains("if (backend == null) {\n            dev.vexelray.framework.shell.Absences.provided("
                + "\"Recipes.backend\", \"Backend\", \"Recipes.user\", (shell.launch().mode() =="
                + " dev.vexelray.framework.api.RunMode.WINDOWED ? \"Recipes.viewer\" : null));"), wiring);
        assertTrue(wiring.contains("} else {\n            dev.vexelray.framework.shell.Absences.handedBack("
                + "\"Recipes.input\", \"InputBackend\");"), wiring);

        Run run = compiled.run(dev.vexelray.framework.api.RunMode.FRAMES, Map.of());
        @SuppressWarnings("unchecked")
        List<String> reported = (List<String>) run.shellType().getClassLoader()
                .loadClass("dev.vexelray.framework.shell.Absences").getField("REPORTED").get(null);
        assertEquals(List.of("user got null"), run.phase("config"), "the null is still passed on");
        assertEquals(List.of("Recipes.backend Backend [Recipes.user, null]", "Recipes.input InputBackend kept []"),
                reported, "once each, the viewer left out: FRAMES does not build it");
        for (String phase : List.of("model", "gui", "tree", "window", "attach")) {
            run.phase(phase);
        }
        assertEquals(2, reported.size(), "a provider is called once, so it is reported once");
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

    /**
     * T2.5 and T4.5: the framework's machinery for sending, running and closing is not handed to an application part.
     * A part sends by taking a {@code Sender<T>}, runs where its declaration puts it, and is closed by being
     * {@code AutoCloseable}. ({@code Placement}, 0.1's door to a component's own thread, is gone from the framework.)
     */
    @Test
    void theFrameworksMachineryIsNotHandedToAPart() {
        Map<String, String> refused = Map.of(
                "sibarum.atchung.Atchung", "T4.5: the raw bus is not handed to an application part",
                "dev.vexelray.framework.shell.Senders", "T4.5: Senders hands out a sender for any topic",
                "dev.vexelray.framework.core.Lanes", "T2.5: the lanes are the framework's",
                "dev.vexelray.framework.core.FrameHooks", "T7.1: no application code runs in the frame",
                "dev.vexelray.framework.core.Disposer", "the disposer runs code at shutdown that nothing declares",
                "java.util.concurrent.ExecutorService", "T2.5: an executor runs whatever it is handed");
        for (Map.Entry<String, String> r : refused.entrySet()) {
            onlyError(app("""
                    @Component(lane = "work")
                    public final class Worker { public Worker(%s it) {} }
                    """.formatted(r.getKey())), "Worker takes it, a " + r.getKey().substring(r.getKey().lastIndexOf('.') + 1)
                    + ". " + r.getValue());
        }
        onlyError(app("""
                public interface Store {}
                """, """
                @Configuration
                public final class Recipes {
                    @Provides public Store store(sibarum.atchung.Atchung bus) { return null; }
                }
                """), "Recipes.store takes bus, a Atchung. T4.5: the raw bus");
        onlyError(app(MESSAGES_ASK, """
                @Job(topic = "ask")
                public final class Answer {
                    public Answer(java.util.concurrent.Executor pool) {}
                    public void run(Ask a) {}
                }
                """), "@Job Answer takes pool, a Executor. T2.5: an executor runs whatever it is handed");
    }

    /**
     * T2.6: a provided value can reach any number of parts, so one a component takes is deeply immutable, or the
     * framework's own service.
     */
    @Test
    void aProvidedValueAComponentTakesIsShareable() {
        onlyError(app("""
                public interface Store {}
                """, """
                @Configuration
                public final class AppConfig {
                    @Provides public Store store() { return null; }
                }
                """, """
                @Component
                public final class Composer { public Composer(Store store) {} }
                """), "T2.6: Composer takes store, which AppConfig.store provides, and it is not shareable");
        assertEquals(List.of(), app("""
                @Configuration
                public final class AppConfig {
                    @Provides public Device device() { return null; }
                }
                """, """
                @Component
                public final class Composer {
                    public Composer(Device device, dev.vexelray.framework.core.Timer timer) {}
                }
                """), "a final class of no state is shareable, and the Timer is the framework's");
    }

    /** T2.7: a component's state is changed by its own deliveries and nothing else, so no field asks for a lock. */
    @Test
    void aComponentsFieldAsksForNoSynchronisation() {
        onlyError(app("""
                @Component
                public final class Counter { private volatile int n; }
                """), "T2.7: Counter's field n is volatile");
        onlyError(app("""
                @Component
                public final class Counter { private final java.util.concurrent.atomic.AtomicInteger n = null; }
                """), "T2.7: Counter's field n is a AtomicInteger, from java.util.concurrent");
        onlyError(app("""
                @Component
                public final class Counter { private Runnable later; }
                """), "T2.7: Counter's field later is a Runnable, a functional interface");
        assertEquals(List.of(), app("""
                @Component
                public final class Counter {
                    private static final java.util.concurrent.atomic.AtomicInteger MADE = null;
                    private int n;
                    private final java.util.List<String> seen = new java.util.ArrayList<>();
                }
                """), "plain state, and a constant that is not the instance's, are the component's own");
    }

    /** T3.1: the compute queue's submissions would make every other component's tick wait on the logic lane. */
    @Test
    void theComputeQueueIsLentOnlyToAComponentOnALaneOfItsOwn() {
        onlyError(app("""
                @Component
                public final class Physics { public Physics(dev.vexelray.gui.core.app.ComputeQueue queue) {} }
                """), "T3.1: Physics takes the ComputeQueue and runs on the logic lane");
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
        onlyError(errors, "@MainThread on Composer");
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

        /** What the wiring answers {@code Wiring.busGraph}: the declared message graph, as text. */
        String busGraph() throws Exception {
            return (String) wiring.getClass().getSuperclass().getMethod("busGraph").invoke(wiring);
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

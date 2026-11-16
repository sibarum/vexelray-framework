package dev.vexelray.framework.template;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled template, generated and inspected.
 *
 * <p>The last test is the one that matters most and is the least obvious: a bundled template's files are
 * classpath resources, and the manifest's {@code file} lines are the only record of what is in it. Nothing at
 * runtime can notice a file that was added to the folder and not to the list -- it simply never ships. This
 * holds the two together at build time, which is the only place it can be held.
 */
class VexelDesktopTest {

    private static final String ID = "vexel-desktop";

    private static Template template() { return Catalogue.bundled().get(ID); }

    private static Answers answers(Map<String, String> overrides) {
        Map<String, String> given = new LinkedHashMap<>(Answers.presets(template()).values());
        given.put("where", "/tmp");
        given.putAll(overrides);
        return Answers.of(given).filled(template());
    }

    private static Blueprint blueprint(Map<String, String> overrides) {
        return Scaffold.of(template(), answers(overrides), Catalogue.bundled());
    }

    // ---- what it asks for -------------------------------------------------------------

    @Test
    void theTemplateReads() {
        Template template = template();
        assertEquals(ID, template.id());
        assertFalse(template.slots().isEmpty());
        assertFalse(template.items().isEmpty());
        assertFalse(template.needs().isEmpty());
    }

    @Test
    void theNamesAreWorkedOutFromTheProjectName() {
        Answers answers = answers(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        assertEquals("dev.example.plotviewer", answers.get("packageName"));
        assertEquals("dev/example/plotviewer", answers.get("packagePath"));
        assertEquals("PlotViewer", answers.get("className"));
        assertEquals("Plot viewer", answers.get("title"));
        assertEquals("plot-viewer", answers.get("appName"));
    }

    @Test
    void namesGivenExplicitlyWin() {
        Answers answers = answers(Map.of(
                "artifactId", "plot-viewer",
                "packageName", "com.acme.viewer",
                "className", "Viewer",
                "title", "The Viewer"));
        assertEquals("com.acme.viewer", answers.get("packageName"));
        assertEquals("com/acme/viewer", answers.get("packagePath"));
        assertEquals("Viewer", answers.get("className"));
        assertEquals("The Viewer", answers.get("title"));
    }

    // ---- what it writes ---------------------------------------------------------------

    /** Where plot-viewer's view and controller land: the two modules under the parent's folder. */
    private static final String GUI = "plot-viewer-gui/src/main/java/dev/example/plotviewer/";
    private static final String CORE = "plot-viewer-core/src/main/java/dev/example/plotviewer/core/";

    @Test
    void theMainClassLandsAtItsPackagePath() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        assertNotNull(blueprint.entry(GUI + "PlotViewer.java"), "expected the main class; got " + blueprint.paths());
        assertNotNull(blueprint.entry(GUI + "Ui.java"));
        assertNotNull(blueprint.entry(CORE + "Editor.java"), "the controller is in -core, in a package of its own");
        assertNotNull(blueprint.entry("pom.xml"));
        assertNotNull(blueprint.entry(".gitignore"));
        assertNotNull(blueprint.entry("docs/framework-notes.md"));
        assertNotNull(blueprint.entry("docs/retrospective.md"));
    }

    /**
     * A parent and two modules: -core the controller, -gui the view. The parent builds both, and -gui depends on -core
     * and not the other way round, which is the line the split is drawn on.
     */
    @Test
    void theProjectIsAParentWithACoreAndAGuiModule() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        String parent = blueprint.text("pom.xml");
        assertTrue(parent.contains("<packaging>pom</packaging>"), parent);
        assertTrue(parent.contains("<module>plot-viewer-core</module>") && parent.contains("<module>plot-viewer-gui</module>"));
        String core = blueprint.text("plot-viewer-core/pom.xml");
        String gui = blueprint.text("plot-viewer-gui/pom.xml");
        assertTrue(core.contains("<artifactId>plot-viewer-core</artifactId>") && core.contains("<artifactId>plot-viewer</artifactId>"),
                "-core names itself and its parent");
        assertTrue(gui.contains("<artifactId>plot-viewer-core</artifactId>"), "-gui builds on -core");
        assertFalse(core.contains("plot-viewer-gui") || core.contains("vexelray-gui-widget") || core.contains("framework-shell"),
                "-core builds against no widget and no shell:\n" + core);
        assertTrue(gui.contains("<app.run.skip>false</app.run.skip>") && parent.contains("<app.run.skip>true</app.run.skip>"),
                "exec:exec from the parent runs the one module with an application in it");
        String listed = blueprint.text(CORE + "Core.java");
        assertTrue(listed.contains("@Configuration(components = Editor.class") && listed.contains("WriteSession.class"),
                "-core lists what it is made of, since the processor compiling -gui cannot see it");
    }

    @Test
    void anAgentIsToldTheRulesInTheProjectsOwnNames() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        String agent = blueprint.text("CLAUDE.md");
        assertNotNull(agent, "expected CLAUDE.md; got " + blueprint.paths());
        assertTrue(agent.contains("# Working in Plot viewer"), agent);
        assertTrue(agent.contains("`PlotViewerAppWiring`"), "the generated class, by its name");
        assertTrue(agent.contains("target/logs/plot-viewer.log"));
    }

    @Test
    void theApplicationIsDeclaredOnceWithItsAutomation() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        String app = blueprint.text(GUI + "PlotViewerApp.java");
        assertNotNull(app, "expected the @VexelApp declaration; got " + blueprint.paths());
        assertTrue(app.contains("starters = {AutomationStarter.class, Core.class}"),
                "every build, the release one too, can be driven; and -core's parts are built");
        assertFalse(blueprint.paths().stream().anyMatch(p -> p.contains("src/edition-")),
                "one declaration, not one per edition: " + blueprint.paths());
        assertFalse(blueprint.text("plot-viewer-gui/pom.xml").contains("automation.scope"),
                "the automation modules are on every build's class path");
        assertFalse(blueprint.text(GUI + "PlotViewer.java").contains("@VexelApp("),
                "the entry class declares nothing, or the build would have two @VexelApp classes");
        assertTrue(blueprint.text(GUI + "PlotViewer.java")
                .contains("new PlotViewerAppWiring()"), "main runs the wiring the processor makes from its declaration");
    }

    /**
     * A native build from a plain shell has no {@code rc.exe} or MSVC {@code link.exe}, so the project carries the
     * wrapper that loads them, names it where the native commands are, and the pom's icon step says to use it
     * rather than failing on exec's "not recognized". The wrapper is cmd.exe's, so it is CR LF throughout, whatever
     * endings the template's copy was checked out with.
     */
    @Test
    void theNativeBuildCarriesItsWrapperAndPointsAtIt() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        String wrapper = blueprint.text("native-build.cmd");
        assertNotNull(wrapper, "expected native-build.cmd; got " + blueprint.paths());
        assertTrue(wrapper.contains("vcvars64.bat") && wrapper.contains("mvn %*"), wrapper);
        assertFalse(wrapper.replace("\r\n", "").contains("\n") || wrapper.replace("\r\n", "").contains("\r"),
                "a batch file is CR LF throughout");

        String pom = blueprint.text("plot-viewer-gui/pom.xml");
        int check = pom.indexOf("<fail unless=\"rc.exe.found\"");
        assertTrue(check > 0 && check < pom.indexOf("<exec executable=\"rc.exe\""),
                "rc.exe is looked for before it is run");
        assertTrue(pom.substring(check, pom.indexOf("/>", check)).contains("native-build.cmd"),
                "and its absence names the wrapper");
        assertTrue(pom.contains("filepath=\"${env.PATH}\""), "searched for on PATH, as exec would");

        assertTrue(blueprint.text("README.md").contains("native-build.cmd -Pnative-release package"));
        assertTrue(blueprint.text("CLAUDE.md").contains("native-build.cmd -Pnative package"));
    }

    /**
     * One mark, three readers: the window (from the class path, via {@code @VexelApp(icon)}), the executable (via the
     * {@code .rc}), and the person who will redraw it (the {@code .svg}). And it is the framework's own, byte for
     * byte, so a project that never replaces it looks the same as one that names no icon at all.
     */
    @Test
    void theMarkIsTheFrameworksAndTheWindowAndTheExecutableShareIt() throws IOException {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        String rc = "plot-viewer-gui/src/main/rc/";
        Blueprint.Entry ico = blueprint.entry(rc + "plot-viewer.ico");
        assertNotNull(ico, "expected the icon; got " + blueprint.paths());
        Path shell = Path.of("../vexelray-framework-shell/src/main/resources/dev/vexelray/framework/shell/new-app.ico");
        assertTrue(java.util.Arrays.equals(Files.readAllBytes(shell), ico.bytes()),
                "the template's new-app.ico has drifted from -shell's; copy it again (both render new-app.svg)");
        assertTrue(java.util.Arrays.equals(
                Files.readAllBytes(Path.of("../vexelray-framework-shell/src/main/rc/new-app.svg")),
                blueprint.entry(rc + "plot-viewer.svg").bytes()), "and its source");

        assertTrue(blueprint.text(rc + "plot-viewer.rc").contains("1 ICON \"plot-viewer.ico\""));
        assertTrue(blueprint.text(rc + "plot-viewer.rc").contains("1 24 \"plot-viewer.manifest\""),
                "the executable declares its DPI awareness, as java.exe does");
        assertTrue(blueprint.text(rc + "plot-viewer.manifest").contains("PerMonitorV2"));
        String pom = blueprint.text("plot-viewer-gui/pom.xml");
        assertTrue(pom.contains("<include>plot-viewer.ico</include>"), "on the class path for the window");
        assertTrue(pom.contains("/src/main/rc/plot-viewer.rc"), "compiled for the executable");
        assertTrue(blueprint.text(GUI + "PlotViewer.java")
                .contains("ICON = \"/plot-viewer.ico\""), "and named, from the root it lands at");
    }

    /**
     * A native build inherits what it needs from the jars it depends on, so the project carries none of it: no
     * reachability metadata (native-image registers the entry point it is given, and the launcher and locale entries
     * a JVM trace records are the {@code java} launcher's, not the image's) and no FFM flag. Ruled 2026-11-16: a
     * file in the application is an edit to every application when a backend or an OS is added. A generated project
     * built this way was built natively and driven with nothing of its own registered.
     */
    @Test
    void theNativeBuildCopiesNothingALibraryCarries() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        assertTrue(blueprint.paths().stream().noneMatch(p -> p.contains("META-INF/native-image")),
                "the project should register nothing of its own; got " + blueprint.paths());
        // What native-image is told by the project is the project's own: no fallback, and how this executable links.
        java.util.regex.Matcher arg = java.util.regex.Pattern.compile("<buildArg>([^<]*)</buildArg>")
                .matcher(blueprint.text("plot-viewer-gui/pom.xml"));
        int args = 0;
        while (arg.find()) {
            args++;
            String a = arg.group(1);
            assertTrue(a.equals("--no-fallback") || a.startsWith("-H:NativeLinkerOption="),
                    "the native build passes " + a + ", which belongs in the jar that needs it");
        }
        assertTrue(args > 0, "expected the native build's arguments in -gui's pom");
    }

    @Test
    void nothingGeneratedStillHasAPlaceholderInIt() {
        for (Blueprint.Entry entry : blueprint(Map.of()).entries()) {
            String text = new String(entry.bytes(), java.nio.charset.StandardCharsets.UTF_8);
            // Maven's own ${} survives on purpose; the template's escape is what tells them apart.
            for (String name : Answers.namesIn(text)) {
                assertFalse(template().placeholders().contains(name),
                        entry.path() + " still has ${" + name + "} in it");
            }
        }
    }

    @Test
    void thePomKeepsMavensOwnProperties() {
        Blueprint blueprint = blueprint(Map.of());
        String poms = blueprint.text("pom.xml") + blueprint.text("my-app-core/pom.xml")
                + blueprint.text("my-app-gui/pom.xml");
        assertTrue(poms.contains("${vexelray-gui.version}"), "Maven's own property should survive");
        assertTrue(poms.contains("${java.home}/bin/java"), "the exec plugin's command line should survive");
        assertFalse(poms.contains("$${"), "no escape should be left in the output");
    }

    /**
     * The processor is named on the compiler's processor path, and the builder checks it is installed. Both
     * halves, because a path naming an artifact nobody installed is a build that fails on its first line.
     */
    @Test
    void theProjectCompilesThroughTheFrameworksProcessor() {
        String pom = blueprint(Map.of()).text("pom.xml");
        assertTrue(pom.contains("<annotationProcessorPaths>"));
        assertTrue(pom.contains("<artifactId>vexelray-framework-processor</artifactId>"));
        assertTrue(template().needs().stream().anyMatch(n -> n.artifact().equals("vexelray-framework-processor")),
                "the builder should check the processor is installed before writing anything");
    }

    /**
     * The one that would have caught the template's own first bug: an em dash in an
     * XML comment, which is two hyphens, which XML will not have.
     */
    @Test
    void everyGeneratedXmlFileParses() {
        assertEquals(List.of(), Checks.malformedXml(blueprint(Map.of())));
    }

    @Test
    void anAnswerThatWouldBreakThePomIsCaught() {
        Blueprint broken = blueprint(Map.of("summary", "a <thing> & another"));
        assertFalse(Checks.malformedXml(broken).isEmpty(),
                "an answer carrying markup should not quietly produce an unparseable pom");
    }

    @Test
    void thePomCarriesTheAnswers() {
        String pom = blueprint(Map.of(
                "artifactId", "plot-viewer",
                "groupId", "dev.example")).text("pom.xml");
        assertTrue(pom.contains("<artifactId>plot-viewer</artifactId>"));
        assertTrue(pom.contains("<groupId>dev.example</groupId>"));
        assertTrue(pom.contains("<app.mainClass>dev.example.plotviewer.PlotViewer</app.mainClass>"));
    }

    /**
     * The stack a project builds against is one version, vexelray-bom's, and it is this framework's own, so a project
     * generated by a framework builds against it without a hand edit. The version is read from this module's pom,
     * whose parent is the framework. What a BOM cannot carry is held to where it comes from: tactroller's version
     * (written only for the builder's installed check) to the BOM's, and the build plugin's to the one this framework
     * builds with.
     */
    @Test
    void theStackIsTheBomsVersion() throws IOException {
        String own = firstVersion(Files.readString(Path.of("pom.xml")));
        String bom = Files.readString(Path.of("../vexelray-bom/pom.xml"));
        assertEquals(own, firstVersion(bom), "vexelray-bom moves with the framework");
        String pom = blueprint(Map.of()).text("pom.xml");
        assertTrue(pom.contains("<vexelray-bom.version>" + own + "</vexelray-bom.version>"), pom);
        assertTrue(pom.contains("<vexelray-gui.version>" + own + "</vexelray-gui.version>"), "the icons plugin's");
        java.util.regex.Matcher tactroller = java.util.regex.Pattern
                .compile("<tactroller\\.version>([^<]+)</tactroller\\.version>").matcher(bom);
        assertTrue(tactroller.find(), "the BOM names tactroller's version");
        assertEquals(tactroller.group(1), answers(Map.of()).resolve("${tactrollerVersion}"),
                "the builder checks for the tactroller the BOM names");
        java.util.regex.Matcher build = java.util.regex.Pattern
                .compile("<artifactId>vexelray-build-maven-plugin</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(Files.readString(Path.of("../pom.xml")));
        assertTrue(build.find(), "the framework names the build plugin's version");
        assertTrue(pom.contains("<vexelray-build.version>" + build.group(1) + "</vexelray-build.version>"),
                "the project builds with the build plugin the framework builds with");
        for (String p : List.of("pom.xml", "my-app-core/pom.xml", "my-app-gui/pom.xml")) {
            assertTrue(blueprint(Map.of()).text(p).contains("<version>" + own + "</version>"),
                    "and the project starts at it, in " + p);
        }
    }

    /**
     * Every library of the stack the project names takes its version from vexelray-bom: none names one of its own,
     * and every one is in the BOM, since a dependency with no version that the BOM does not list is a build that
     * fails on its first line. The processor on the compiler's path too (maven-compiler-plugin 3.12 and later take
     * that version from dependency management as well).
     */
    @Test
    void everyLibraryTakesItsVersionFromTheBom() throws IOException {
        Set<String> managed = new HashSet<>();
        java.util.regex.Matcher entry = java.util.regex.Pattern.compile(
                "<groupId>([^<]+)</groupId><artifactId>([^<]+)</artifactId>")
                .matcher(Files.readString(Path.of("../vexelray-bom/pom.xml")).replaceAll("\\s+", ""));
        while (entry.find()) managed.add(entry.group(1) + ":" + entry.group(2));
        Blueprint blueprint = blueprint(Map.of("groupId", "dev.example"));
        int named = 0;
        for (String p : List.of("pom.xml", "my-app-core/pom.xml", "my-app-gui/pom.xml")) {
            java.util.regex.Matcher block = java.util.regex.Pattern
                    .compile("<(dependency|path)>(\\s*<groupId>.*?)</\\1>", java.util.regex.Pattern.DOTALL)
                    .matcher(blueprint.text(p));
            while (block.find()) {
                String body = block.group(2).replaceAll("\\s+", "");
                java.util.regex.Matcher id = java.util.regex.Pattern
                        .compile("<groupId>([^<]+)</groupId><artifactId>([^<]+)</artifactId>").matcher(body);
                assertTrue(id.find(), "a dependency in " + p + " with no coordinates: " + body);
                String coordinate = id.group(1) + ":" + id.group(2);
                if (id.group(1).equals("dev.example") || id.group(1).startsWith("org.junit")
                        || id.group(2).equals("vexelray-bom")) continue;
                named++;
                assertFalse(body.contains("<version>"), coordinate + " names a version in " + p);
                assertTrue(managed.contains(coordinate), coordinate + ", named in " + p + ", is not in vexelray-bom");
            }
        }
        assertTrue(named > 10, "expected the stack's libraries in the poms; found " + named);
    }

    /** A pom's own version, or its parent's when that comes first: the first one outside a comment. */
    private static String firstVersion(String pom) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<version>([^<]+)</version>")
                .matcher(pom.replaceAll("(?s)<!--.*?-->", ""));
        assertTrue(m.find(), "a pom with no version");
        return m.group(1);
    }

    @Test
    void aVersionGivenWins() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "version", "1.4.0"));
        for (String p : List.of("pom.xml", "plot-viewer-core/pom.xml", "plot-viewer-gui/pom.xml")) {
            assertTrue(blueprint.text(p).contains("<version>1.4.0</version>"), p);
        }
    }

    /** A project that brings its mark keeps its file name, and every reader of the mark follows it. */
    @Test
    void anIconNameGivenNamesTheMark() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example", "icon", "plot"));
        String rc = "plot-viewer-gui/src/main/rc/";
        assertNotNull(blueprint.entry(rc + "plot.ico"), "expected the icon; got " + blueprint.paths());
        assertNotNull(blueprint.entry(rc + "plot.svg"));
        assertNull(blueprint.entry(rc + "plot-viewer.ico"));
        assertTrue(blueprint.text(rc + "plot-viewer.rc").contains("1 ICON \"plot.ico\""));
        assertTrue(blueprint.text("plot-viewer-gui/pom.xml").contains("<include>plot.ico</include>"));
        assertTrue(blueprint.text(GUI + "PlotViewer.java").contains("ICON = \"/plot.ico\""));
    }

    @Test
    void everyGeneratedSourceDeclaresItsPackage() {
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        for (Blueprint.Entry entry : blueprint.entries()) {
            if (!entry.path().endsWith(".java")) continue;
            String text = blueprint.text(entry.path());
            // The package is the folder the file lands in, so a sub-package's file declares the sub-package.
            String folder = entry.path().substring(0, entry.path().lastIndexOf('/'))
                    .replaceFirst("^plot-viewer-(core|gui)/src/(main|test)/java/", "");
            String expected = "package " + folder.replace('/', '.') + ";";
            assertTrue(folder.startsWith("dev/example/plotviewer"), entry.path() + " is outside the project's package");
            assertTrue(text.startsWith(expected),
                    entry.path() + " should declare " + expected + ", and starts: "
                            + text.substring(0, Math.min(60, text.length())));
        }
    }

    @Test
    void turningTheTestsOffLeavesThemOut() {
        Map<String, String> off = new LinkedHashMap<>();
        off.put("tests", "no");
        Blueprint without = blueprint(off);
        assertTrue(without.paths().stream().noneMatch(p -> p.contains("/src/test/")),
                "no tests should be written; got " + without.paths());
        assertFalse(without.notes().isEmpty(), "leaving files out should be said out loud");
        assertTrue(blueprint(Map.of("tests", "yes")).paths().stream()
                .anyMatch(p -> p.contains("/src/test/")));
    }

    // ---- modules ------------------------------------------------------------------------

    @Test
    void everyModuleIsTakenUnlessAsked() {
        Blueprint all = blueprint(Map.of());
        assertTrue(all.paths().contains("EXAMPLES.md"));
        assertTrue(all.paths().stream().anyMatch(p -> p.endsWith("examples/later/Later.java")));
        assertTrue(all.text("EXAMPLES.md").contains("| Do something later"), "later's row is gathered");
        assertTrue(all.text("CLAUDE.md").contains("Look in `EXAMPLES.md` first"), "the examples' part is gathered");
    }

    @Test
    void noneLeavesEveryModuleOutAndTheBaseAsItIs() {
        Blueprint all = blueprint(Map.of());
        Blueprint none = blueprint(Map.of(Template.MODULES, "none"));
        assertTrue(none.paths().stream().noneMatch(p -> p.contains("/examples/") || p.equals("EXAMPLES.md")
                        || p.startsWith("checks/") || p.endsWith("ExamplesTest.java")
                        || p.endsWith("WindowTest.java")),
                "no module should be written; got " + none.paths());
        assertEquals(none.paths(), all.paths().stream().filter(none.paths()::contains).toList(),
                "leaving the modules out changes nothing else that is written");
        assertFalse(none.text("CLAUDE.md").contains("EXAMPLES.md"),
                "the base's docs name no module it does not have");
    }

    @Test
    void aModuleComesWithWhatItRequiresAndSaysSo() {
        Blueprint later = blueprint(Map.of(Template.MODULES, "later"));
        assertTrue(later.paths().stream().anyMatch(p -> p.endsWith("examples/Examples.java")),
                "later requires examples; got " + later.paths());
        assertTrue(later.notes().stream().anyMatch(n -> n.contains("later requires examples")), later.notes()::toString);
    }

    @Test
    void theFrameAloneHasTheWindowAndNoExample() {
        Blueprint frame = blueprint(Map.of(Template.MODULES, "examples"));
        assertTrue(frame.paths().stream().anyMatch(p -> p.endsWith("examples/Examples.java")));
        assertTrue(frame.paths().stream().noneMatch(p -> p.contains("/later/") || p.endsWith("LaterWindowTest.java")
                || p.startsWith("checks/")), "got " + frame.paths());
        assertFalse(frame.text("EXAMPLES.md").contains("Do something later"), "no row for an example it lacks");
    }

    @Test
    void aModulesTestNeedsTheTestsToo() {
        Blueprint noTests = blueprint(Map.of("tests", "no"));
        assertTrue(noTests.paths().stream().anyMatch(p -> p.endsWith("examples/later/Later.java")));
        assertTrue(noTests.paths().stream().noneMatch(p -> p.contains("/src/test/")),
                "\"in later when tests\" writes nothing with the tests off; got " + noTests.paths());
    }

    @Test
    void anUnknownModuleIsRefusedBeforeAnythingIsWritten() {
        Answers answers = answers(Map.of(Template.MODULES, "examples latr"));
        List<String> problems = Checks.problems(template(), answers);
        assertTrue(problems.stream().anyMatch(p -> p.contains("no latr module") && p.contains("did you mean later")),
                problems::toString);
        assertThrows(TemplateError.class, () -> Scaffold.of(template(), answers, Catalogue.bundled()));
    }

    /**
     * The base alone, each module alone with what it requires, and all of them: the sets a module that names only the
     * base and what it requires can break, so these stand for every combination (rule 4 of the plan). Each is planned
     * whole, with nothing left to substitute and no marker left unfilled; the acceptance run builds them.
     */
    @Test
    void theBaseEachModuleAloneAndAllArePlannedWhole() {
        assertEquals(List.of("none", "examples", "all"), template().matrix(),
                "later requires examples and nothing else, so later alone is all");
        for (String answer : template().matrix()) {
            Blueprint blueprint = blueprint(Map.of(Template.MODULES, answer));
            for (Blueprint.Entry entry : blueprint.entries()) {
                String text = blueprint.text(entry.path());
                if (text == null) continue;
                assertFalse(text.contains("<!-- part:"), answer + ": " + entry.path() + " kept a marker");
            }
        }
    }

    /**
     * Rule 2 of the plan, held: the base names no module, and a module names only the base and the modules it
     * requires. Read in the sources of every module taken, by the classes each module's files declare and the
     * packages only its files are in, so a reach across is found whichever way it is written. This is what lets the
     * matrix above stand for every combination; it is the check that keeps the template cheap to keep.
     */
    @Test
    void noFileNamesAModuleItDoesNotRequire() {
        Template template = template();
        Answers answers = answers(Map.of());
        Blueprint all = Scaffold.of(template, answers, Catalogue.bundled());

        Map<String, String> owner = new LinkedHashMap<>();
        for (Template.Item item : template.items()) owner.put(answers.resolve(item.path()), item.module());
        Map<String, Set<String>> classes = new LinkedHashMap<>();
        Map<String, Set<String>> packages = new LinkedHashMap<>();
        Map<String, String> packageOwner = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : owner.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            String pkg = packageOf(all.text(e.getKey()));
            String was = packageOwner.putIfAbsent(pkg, e.getValue() == null ? "" : e.getValue());
            if (was != null && !was.equals(e.getValue() == null ? "" : e.getValue())) packageOwner.put(pkg, "");
            if (e.getValue() == null) continue;
            String name = e.getKey().substring(e.getKey().lastIndexOf('/') + 1, e.getKey().length() - ".java".length());
            classes.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(name);
        }
        packageOwner.forEach((pkg, module) -> {
            if (!module.isEmpty()) packages.computeIfAbsent(module, k -> new TreeSet<>()).add(pkg);
        });

        List<String> found = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : owner.entrySet()) {
            if (!e.getKey().endsWith(".java") || all.text(e.getKey()) == null) continue;
            String text = all.text(e.getKey());
            Set<String> allowed = new HashSet<>();
            if (e.getValue() != null) allowed.addAll(template.choose(e.getValue()).names());
            for (String module : template.moduleNames()) {
                if (allowed.contains(module)) continue;
                for (String name : classes.getOrDefault(module, Set.of())) {
                    if (java.util.regex.Pattern.compile("\\b" + name + "\\b").matcher(text).find()) {
                        found.add(e.getKey() + " (" + (e.getValue() == null ? "base" : e.getValue()) + ") names "
                                + name + " (" + module + ")");
                    }
                }
                for (String pkg : packages.getOrDefault(module, Set.of())) {
                    if (text.contains(pkg + ".") || text.contains(pkg + ";")) {
                        found.add(e.getKey() + " names the package " + pkg + " (" + module + ")");
                    }
                }
            }
        }
        assertEquals(List.of(), found, "a file reaches a module it does not require, so leaving that module out "
                + "breaks it: move the file into the module, or have its module require the other");
    }

    private static String packageOf(String source) {
        var m = java.util.regex.Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(source);
        return m.find() ? m.group(1) : "";
    }

    @Test
    void theFolderIsTheProjectName() {
        Path where = Path.of("/tmp/checkouts");
        assertEquals(where.resolve("plot-viewer"),
                Scaffold.folder(template(), answers(Map.of("artifactId", "plot-viewer")), where));
    }

    @Test
    void aProjectNameThatIsAPathIsRefused() {
        assertThrows(TemplateError.class, () -> Scaffold.folder(template(),
                answers(Map.of("artifactId", "../elsewhere")), Path.of("/tmp")));
    }

    // ---- writing, and taking it back ---------------------------------------------------

    @Test
    void itWritesTheWholeTreeAndOnlyItsOwnFiles(@org.junit.jupiter.api.io.TempDir Path temp)
            throws IOException {
        Path target = temp.resolve("plot-viewer");
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer", "groupId", "dev.example"));
        Blueprint.Writing writing = new Blueprint.Writing(target);
        writing.begin();
        for (Blueprint.Entry entry : blueprint.entries()) writing.write(entry);

        for (String path : blueprint.paths()) {
            assertTrue(Files.isRegularFile(target.resolve(path)), path + " should be on disk");
        }
        assertEquals(blueprint.size(), writing.written().size());
    }

    @Test
    void undoTakesBackEverythingItMade(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Path target = temp.resolve("nested").resolve("plot-viewer");
        Blueprint blueprint = blueprint(Map.of("artifactId", "plot-viewer"));
        Blueprint.Writing writing = new Blueprint.Writing(target);
        writing.begin();
        for (Blueprint.Entry entry : blueprint.entries()) writing.write(entry);
        writing.undo();

        assertFalse(Files.exists(target), "the project folder should be gone");
        assertFalse(Files.exists(temp.resolve("nested")), "and the folders made on the way to it");
        assertTrue(Files.isDirectory(temp), "but not the folder that was already there");
    }

    @Test
    void aFolderThatWasAlreadyThereSurvivesUndo(@org.junit.jupiter.api.io.TempDir Path temp)
            throws IOException {
        Path target = Files.createDirectory(temp.resolve("plot-viewer"));
        Path theirs = Files.writeString(target.resolve("NOTES.txt"), "mine");
        Blueprint.Writing writing = new Blueprint.Writing(target);
        writing.begin();
        writing.write(new Blueprint.Entry("pom.xml", "x".getBytes()));
        writing.undo();

        assertTrue(Files.isDirectory(target), "a folder this did not create is not removed");
        assertEquals("mine", Files.readString(theirs));
        assertFalse(Files.exists(target.resolve("pom.xml")));
    }

    // ---- the checks ---------------------------------------------------------------------

    @Test
    void aFolderWithThingsInItIsRefusedUnlessAskedFor(@org.junit.jupiter.api.io.TempDir Path temp)
            throws IOException {
        Path target = Files.createDirectory(temp.resolve("taken"));
        Files.writeString(target.resolve("something.txt"), "mine");
        assertNotNull(Checks.destinationProblem(target, false, null));
        assertNull(Checks.destinationProblem(target, true, null));
    }

    @Test
    void aFolderThatIsAFileIsAlwaysRefused(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Path file = Files.writeString(temp.resolve("thing"), "x");
        assertNotNull(Checks.destinationProblem(file, true, null));
    }

    @Test
    void anExistingFileIsNeverWrittenOver(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Path target = Files.createDirectory(temp.resolve("plot-viewer"));
        Files.writeString(target.resolve("README.md"), "mine");
        List<String> hit = Checks.collisions(target, blueprint(Map.of("artifactId", "plot-viewer")));
        assertEquals(List.of("README.md"), hit);
    }

    @Test
    void aMissingLibraryIsFoundBeforeAnythingIsWritten(@org.junit.jupiter.api.io.TempDir Path temp)
            throws IOException {
        // An empty repository that exists: every declared artifact is missing from it.
        Path repository = Files.createDirectory(temp.resolve("repository"));
        assertEquals(template().needs().size(),
                Checks.missingArtifacts(template(), answers(Map.of()), repository).size());
        // A repository that does not exist establishes nothing, so it says nothing.
        assertTrue(Checks.missingArtifacts(template(), answers(Map.of()),
                temp.resolve("nowhere")).isEmpty());
    }

    /**
     * The BOM is needed too, and it is a pom: installed is its pom being there. A library with a pom and no jar is
     * still half-installed and still missing.
     */
    @Test
    void theBomIsNeededAndAPomIsEnoughForIt(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Template.Need bom = template().needs().stream()
                .filter(n -> n.artifact().equals("vexelray-bom")).findFirst().orElse(null);
        assertNotNull(bom, "the builder should check the BOM is installed");
        assertEquals("pom", bom.packaging());
        String version = answers(Map.of()).resolve(bom.version());
        Path repository = Files.createDirectory(temp.resolve("repository"));
        Path bomFolder = Files.createDirectories(repository.resolve("dev/vexelray/framework/vexelray-bom/" + version));
        Files.writeString(bomFolder.resolve("vexelray-bom-" + version + ".pom"), "<project/>");
        Path apiFolder = Files.createDirectories(
                repository.resolve("dev/vexelray/framework/vexelray-framework-api/" + version));
        Files.writeString(apiFolder.resolve("vexelray-framework-api-" + version + ".pom"), "<project/>");
        List<String> missing = Checks.missingArtifacts(template(), answers(Map.of()), repository);
        assertFalse(missing.contains("dev.vexelray.framework:vexelray-bom:pom:" + version), missing.toString());
        assertTrue(missing.contains("dev.vexelray.framework:vexelray-framework-api:" + version), missing.toString());
    }

    // ---- the list and the folder ------------------------------------------------------------

    /**
     * Every file in the template folder is named by the manifest, and every file the manifest names is in the
     * folder.
     *
     * <p>Reaches for the source tree rather than the classpath on purpose: the failure this catches is a file
     * that was added to the folder and not to the list, and on the classpath that file is indistinguishable
     * from one that does not exist. Skipped rather than failed when the source tree is not there, so a run
     * against a packaged jar is not a false alarm.
     */
    @Test
    void theManifestAndTheFolderAgree() throws IOException {
        Path folder = Path.of("src/main/resources/dev/vexelray/framework/template", ID, "files");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(folder),
                "only meaningful against the source tree");

        Set<String> onDisk = new TreeSet<>();
        try (var walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> onDisk.add(folder.relativize(p).toString().replace('\\', '/')));
        }
        Set<String> named = new TreeSet<>();
        for (Template.Item item : template().items()) named.add(item.source());
        for (Template.Part part : template().parts()) named.add(part.source());

        Set<String> unlisted = new HashSet<>(onDisk);
        unlisted.removeAll(named);
        assertTrue(unlisted.isEmpty(),
                "these files are in the folder and would never ship, because no manifest line names "
                        + "them: " + unlisted);

        Set<String> absent = new HashSet<>(named);
        absent.removeAll(onDisk);
        assertTrue(absent.isEmpty(), "the manifest names files that are not there: " + absent);
    }

    /**
     * The native-image metadata names every resource the builder reads, at the root {@link Catalogue} reads it
     * from.
     *
     * <p>A native binary ships only the resources it was told about, and one missing here is a builder that
     * lists the template, asks every question, and then cannot find a file to write — a green JVM build is no
     * evidence either way. The metadata came from {@code mainframe-template} after the resources did, which is
     * the drift this exists to catch: globs naming a root nothing reads any more.
     */
    @Test
    void theNativeImageMetadataCoversEveryBundledFile() throws IOException {
        String json;
        try (var in = Catalogue.class.getResourceAsStream(
                "/META-INF/native-image/dev.vexelray.framework/vexelray-framework-template/reachability-metadata.json")) {
            assertNotNull(in, "the metadata is not on the classpath");
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        List<java.nio.file.PathMatcher> globs = new java.util.ArrayList<>();
        var m = java.util.regex.Pattern.compile("\"glob\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            globs.add(java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + m.group(1)));
        }
        assertFalse(globs.isEmpty(), "the metadata names no resources");

        String root = "dev/vexelray/framework/template/" + ID + "/";
        List<String> read = new java.util.ArrayList<>();
        read.add(root + "template.manifest");
        for (Template.Item item : template().items()) read.add(root + "files/" + item.source());
        for (Template.Part part : template().parts()) read.add(root + "files/" + part.source());
        for (String resource : read) {
            Path path = Path.of(resource);
            assertTrue(globs.stream().anyMatch(g -> g.matches(path)),
                    resource + " is read by the builder and would not ship in a native image");
        }
    }

}

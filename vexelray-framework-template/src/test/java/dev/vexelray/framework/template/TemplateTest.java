package dev.vexelray.framework.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The manifest reader, substitution, and the checks that are not shaped like a pattern. */
class TemplateTest {

    private static final String SIMPLE = """
            template  demo
            title     A demo
            summary   something to read
            folder    ${name}

            slot name
                label     project name
                required  yes
                preset    my-app
                match     [a-z][a-z0-9-]*

            slot extras
                kind      flag
                required  no
                preset    yes

            fill always shouty upper ${name}

            file pom.xml
            file src/${shouty}/A.java  from A.java
            file EXTRA.md              when extras
            """;

    private static Template simple() { return Manifest.read(SIMPLE, "test"); }

    // ---- reading ------------------------------------------------------------------------

    @Test
    void readsTheWholeManifest() {
        Template template = simple();
        assertEquals("demo", template.id());
        assertEquals("A demo", template.title());
        assertEquals("something to read", template.summary());
        assertEquals(2, template.slots().size());
        assertEquals(3, template.items().size());
    }

    @Test
    void aSlotKeepsItsRules() {
        Template.Slot name = simple().slot("name");
        assertNotNull(name);
        assertEquals("project name", name.label());
        assertEquals(Template.Kind.TEXT, name.kind());
        assertTrue(name.required());
        assertEquals("my-app", name.preset());
        assertEquals("[a-z][a-z0-9-]*", name.match());
    }

    @Test
    void aLabelDefaultsToTheName() {
        assertEquals("extras", simple().slot("extras").label());
    }

    @Test
    void aFileWithoutFromIsItsOwnSource() {
        assertEquals("pom.xml", simple().items().getFirst().source());
        assertEquals("A.java", simple().items().get(1).source());
    }

    /**
     * A template that does not say what it is called is named after the first slot
     * that could plausibly be a name -- never the destination folder, which is what
     * almost every template asks for first and is never what the new folder should
     * be called.
     */
    @Test
    void aTemplateThatDoesNotSayItsFolderSkipsTheDestination() {
        Template template = Manifest.read("""
                template t
                slot where
                    kind folder
                slot name
                file a.txt
                """, "test");
        assertEquals("${name}", template.folder());
    }

    @Test
    void whatToDoNextComesFromTheTemplate() {
        assertNull(simple().next());
        Template template = Manifest.read("""
                template t
                next cd ${name} && mvn test
                slot name
                file a.txt
                """, "test");
        assertEquals("cd ${name} && mvn test", template.next());
    }

    @Test
    void aLineItDoesNotKnowIsRefusedWithItsNumber() {
        TemplateError e = assertThrows(TemplateError.class,
                () -> Manifest.read("template t\nwibble yes\nfile a\n", "test"));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
        assertTrue(e.getMessage().contains("wibble"), e.getMessage());
    }

    @Test
    void aSlotKeyBeforeAnySlotIsRefused() {
        TemplateError e = assertThrows(TemplateError.class,
                () -> Manifest.read("template t\nlabel oops\nfile a\n", "test"));
        assertTrue(e.getMessage().contains("no slot has been opened"), e.getMessage());
    }

    @Test
    void aConditionalFileNeedsAFlagThatExists() {
        TemplateError e = assertThrows(TemplateError.class, () -> Manifest.read("""
                template t
                slot name
                file a.txt when nosuch
                """, "test"));
        assertTrue(e.getMessage().contains("nosuch"), e.getMessage());
    }

    @Test
    void twoFilesAtOnePathAreRefused() {
        TemplateError e = assertThrows(TemplateError.class, () -> Manifest.read("""
                template t
                slot name
                file a.txt
                file a.txt from b.txt
                """, "test"));
        assertTrue(e.getMessage().contains("twice"), e.getMessage());
    }

    @Test
    void aTemplateThatWritesNothingIsRefused() {
        assertThrows(TemplateError.class, () -> Manifest.read("template t\nslot name\n", "test"));
    }

    @Test
    void aBadPatternIsRefusedWhenTheManifestIsRead() {
        assertThrows(TemplateError.class, () -> Manifest.read("""
                template t
                slot name
                    match [unclosed
                file a.txt
                """, "test"));
    }

    // ---- answers ------------------------------------------------------------------------

    @Test
    void presetsComeOutOfTheManifest() {
        Answers presets = Answers.presets(simple());
        assertEquals("my-app", presets.get("name"));
        assertEquals("yes", presets.get("extras"));
    }

    @Test
    void fillsAreWorkedOut() {
        Answers answers = Answers.of(Map.of("name", "plotter")).filled(simple());
        assertEquals("PLOTTER", answers.get("shouty"));
    }

    @Test
    void aBlankFillOnlyFillsInABlank() {
        Template template = Manifest.read("""
                template t
                slot a
                slot b
                    required no
                fill blank b upper ${a}
                file x
                """, "test");
        assertEquals("Q", Answers.of(Map.of("a", "q")).filled(template).get("b"));
        Map<String, String> both = new LinkedHashMap<>();
        both.put("a", "q");
        both.put("b", "mine");
        assertEquals("mine", Answers.of(both).filled(template).get("b"));
    }

    @Test
    void anUnknownPlaceholderIsRefusedRatherThanWritten() {
        Answers answers = Answers.of(Map.of("name", "plotter"));
        TemplateError e = assertThrows(TemplateError.class, () -> answers.resolve("hello ${nope}"));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    @Test
    void aNearMissIsSuggested() {
        Answers answers = Answers.of(Map.of("artifactId", "plotter"));
        TemplateError e = assertThrows(TemplateError.class, () -> answers.resolve("${artifactID}"));
        assertTrue(String.join(" ", e.hints()).contains("artifactId"), e.hints().toString());
    }

    /**
     * cmd.exe is a batch file's only reader and it misreads bare LF, while the source's endings are whatever git
     * checked out; so a {@code .cmd} or {@code .bat} is written CR LF, and nothing else has its endings touched.
     */
    @Test
    void aBatchFileIsWrittenWithCrLfAndNothingElseIsChanged(@TempDir Path temp) throws IOException {
        Path folder = Files.createDirectories(temp.resolve("eol"));
        Files.writeString(folder.resolve(Catalogue.MANIFEST), """
                template  eol
                title     Line endings
                summary   one of each
                slot name
                    required  yes
                    preset    x
                file build.cmd
                file run.BAT
                file notes.txt
                """);
        Path files = Files.createDirectories(folder.resolve("files"));
        Files.writeString(files.resolve("build.cmd"), "@echo off\nrem ${name}\r\nmvn %*\n");
        Files.writeString(files.resolve("run.BAT"), "a\rb\n");
        Files.writeString(files.resolve("notes.txt"), "one\ntwo\r\n");

        Catalogue catalogue = Catalogue.including(temp);
        Template template = catalogue.get("eol");
        Answers answers = Answers.of(Map.of("name", "demo")).filled(template);
        Blueprint blueprint = Scaffold.of(template, answers, catalogue);

        assertEquals("@echo off\r\nrem demo\r\nmvn %*\r\n", blueprint.text("build.cmd"));
        assertEquals("a\r\nb\r\n", blueprint.text("run.BAT"));
        assertEquals("one\ntwo\r\n", blueprint.text("notes.txt"));
    }

    @Test
    void aDoubledDollarIsALiteral() {
        Answers answers = Answers.of(Map.of("name", "plotter"));
        assertEquals("${maven.property} and plotter",
                answers.resolve("$${maven.property} and ${name}"));
    }

    @Test
    void whatSomebodyTypedIsNotItselfATemplate() {
        // A project genuinely called ${home} gets that name rather than a surprise.
        Answers answers = Answers.of(Map.of("name", "${home}", "home", "elsewhere"));
        assertEquals("${home}", answers.resolve("${name}"));
    }

    @Test
    void flagsReadTheWordsAPersonWouldType() {
        Answers answers = Answers.of(Map.of("a", "yes", "b", "true", "c", "no", "d", ""));
        assertTrue(answers.flag("a"));
        assertTrue(answers.flag("b"));
        assertFalse(answers.flag("c"));
        assertFalse(answers.flag("d"));
        assertFalse(answers.flag("missing"));
    }

    // ---- transforms ---------------------------------------------------------------------

    @Test
    void transformsDoWhatTheyAreNamedFor() {
        assertEquals("MyApp", Transform.TYPE.apply("my-app"));
        assertEquals("A3dPlot", Transform.TYPE.apply("3d-plot"));
        assertEquals("dev/example/app", Transform.PATH.apply("dev.example.app"));
        assertEquals("my-app", Transform.SLUG.apply("My App!"));
        assertEquals("myapp", Transform.SEGMENT.apply("My App"));
        assertEquals("dev.example.myapp", Transform.PACKAGE.apply("dev.example.My App"));
        assertEquals("My app", Transform.TITLE.apply("my-app"));
    }

    // ---- the checks a pattern cannot make -------------------------------------------------

    @Test
    void aJavaKeywordInAPackageIsRefused() {
        assertNull(Checks.packageProblem("package", "dev.example.app"));
        assertTrue(Checks.packageProblem("package", "dev.new.app").contains("keyword"));
        assertNotNull(Checks.packageProblem("package", "dev..app"));
        assertNotNull(Checks.packageProblem("package", "dev.9lives"));
    }

    @Test
    void aClassNameHasToBeOne() {
        assertNull(Checks.typeProblem("main class", "Plotter"));
        assertNotNull(Checks.typeProblem("main class", "class"));
        assertNotNull(Checks.typeProblem("main class", "9Lives"));
        assertNotNull(Checks.typeProblem("main class", "plotter"));   // wants a capital
        assertNotNull(Checks.typeProblem("main class", "My Class"));
    }

    @Test
    void aMavenIdIsLowerCase() {
        assertNull(Checks.mavenIdProblem("project name", "my-app"));
        assertNull(Checks.mavenIdProblem("group", "dev.example"));
        assertNotNull(Checks.mavenIdProblem("project name", "My App"));
        assertNotNull(Checks.mavenIdProblem("project name", "-leading"));
    }

    @Test
    void everyProblemIsReportedRatherThanTheFirst() {
        Template template = Manifest.read("""
                template t
                slot pkg
                    label package
                    rule java-package
                slot cls
                    label class
                    rule java-type
                file x
                """, "test");
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("pkg", "dev.new.app");
        answers.put("cls", "9Lives");
        assertEquals(2, Checks.problems(template, Answers.of(answers)).size());
    }

    // ---- modules ------------------------------------------------------------------------

    private static final String MODULAR = """
            template m
            slot name
            slot modules
                required  no
                preset    all

            module frame
                about     the window the others show in
            module later
                about     do it later
                requires  frame

            file INDEX.md
            file Frame.java  in frame
            file Later.java  in later
            part INDEX.md rows  from later-row.md  in later
            """;

    private static Template modular() { return Manifest.read(MODULAR, "test"); }

    @Test
    void modulesAreReadWithWhatTheyRequire() {
        Template template = modular();
        assertEquals(List.of("frame", "later"), template.moduleNames());
        assertEquals(List.of("frame"), template.module("later").requires());
        assertEquals("later", template.items().get(2).module());
        assertNull(template.items().getFirst().module(), "a file in no module is the base's");
        assertEquals("rows", template.parts().getFirst().marker());
    }

    @Test
    void anAnswerTakesAllNoneOrNamesAndWhatTheyRequire() {
        Template template = modular();
        assertEquals(List.of("frame", "later"), template.choose("all").names());
        assertEquals(List.of(), template.choose("none").names());
        assertEquals(List.of(), template.choose("").names());
        Template.Choice later = template.choose("later");
        assertEquals(List.of("frame", "later"), later.names(), "in the manifest's order");
        assertEquals(List.of("later requires frame, so it comes too"), later.notes());
        assertEquals(List.of("frame"), template.choose("frame, frame").names());
        assertFalse(template.choose("all later").problems().isEmpty(), "all stands alone");
        assertTrue(template.choose("latter").problems().getFirst().contains("did you mean later"));
    }

    @Test
    void aModuleThatRequiresOneThatIsNotThereIsRefused() {
        TemplateError e = assertThrows(TemplateError.class,
                () -> Manifest.read(MODULAR.replace("requires  frame", "requires  fram"), "test"));
        assertTrue(e.getMessage().contains("fram"), e.getMessage());
    }

    @Test
    void modulesThatRequireEachOtherAreRefused() {
        TemplateError e = assertThrows(TemplateError.class, () -> Manifest.read(
                MODULAR.replace("about     the window the others show in",
                        "about     the window\n    requires  later"), "test"));
        assertTrue(e.getMessage().contains("requires itself"), e.getMessage());
    }

    @Test
    void aTemplateWithModulesHasToAskWhich() {
        TemplateError e = assertThrows(TemplateError.class, () -> Manifest.read(
                MODULAR.replace("slot modules\n    required  no\n    preset    all\n", ""), "test"));
        assertTrue(e.getMessage().contains("does not ask which"), e.getMessage());
    }

    @Test
    void aFileInAModuleThatIsNotDeclaredIsRefused() {
        TemplateError e = assertThrows(TemplateError.class,
                () -> Manifest.read(MODULAR + "file Other.java in other\n", "test"));
        assertTrue(e.getMessage().contains("no such module"), e.getMessage());
    }

    @Test
    void aPartIntoAModuleItDoesNotRequireIsRefused() {
        TemplateError e = assertThrows(TemplateError.class, () -> Manifest.read(
                MODULAR + "part Later.java notes from frame-notes.md in frame\n", "test"));
        assertTrue(e.getMessage().contains("does not require it"), e.getMessage());
    }

    @Test
    void aPartNeedsAModuleAndASource() {
        assertThrows(TemplateError.class, () -> Manifest.read(MODULAR + "part INDEX.md rows from x.md\n", "test"));
        assertThrows(TemplateError.class, () -> Manifest.read(MODULAR + "part INDEX.md rows in later\n", "test"));
    }

    @Test
    void aModuleKeyBeforeAnyModuleIsRefused() {
        TemplateError e = assertThrows(TemplateError.class,
                () -> Manifest.read("template t\nslot name\nabout oops\nfile a\n", "test"));
        assertTrue(e.getMessage().contains("no module has been opened"), e.getMessage());
    }

    /** A template on disk, so a test can say what its files hold. */
    private static Catalogue onDisk(Path temp, Map<String, String> files) throws IOException {
        Path folder = Files.createDirectories(temp.resolve("m"));
        Files.writeString(folder.resolve(Catalogue.MANIFEST), MODULAR);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = folder.resolve("files").resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        return Catalogue.including(temp);
    }

    private static Map<String, String> modularFiles(String index) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("INDEX.md", index);
        files.put("Frame.java", "class Frame {}\n");
        files.put("Later.java", "class Later {}\n");
        files.put("later-row.md", "| later | ${name} |\n");
        return files;
    }

    private static Blueprint modularBlueprint(Catalogue catalogue, String modules) {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("name", "demo");
        answers.put("modules", modules);
        return Scaffold.of(catalogue.get("m"), Answers.of(answers), catalogue);
    }

    @Test
    void aPartFillsItsMarkerOrTheMarkerGoes(@TempDir Path temp) throws IOException {
        Catalogue catalogue = onDisk(temp, modularFiles("# Index\r\n| a | b |\r\n<!-- part: rows -->\r\nend\r\n"));
        Blueprint all = modularBlueprint(catalogue, "all");
        assertEquals("# Index\r\n| a | b |\r\n| later | demo |\r\nend\r\n", all.text("INDEX.md"),
                "substituted, and in the file's own line endings");
        Blueprint none = modularBlueprint(catalogue, "none");
        assertEquals("# Index\r\n| a | b |\r\nend\r\n", none.text("INDEX.md"));
        assertEquals(List.of("INDEX.md"), none.paths());
        assertTrue(none.notes().stream().anyMatch(n -> n.contains("later module was not chosen")), none.notes()::toString);
    }

    @Test
    void aPartWithNoMarkerAndAMarkerWithNoPartAreBothRefused(@TempDir Path temp) throws IOException {
        Catalogue noMarker = onDisk(temp.resolve("a"), modularFiles("# Index\n"));
        TemplateError e = assertThrows(TemplateError.class, () -> modularBlueprint(noMarker, "none"));
        assertTrue(e.getMessage().contains("has no <!-- part: rows --> line"), e.getMessage());

        Catalogue stray = onDisk(temp.resolve("b"), modularFiles("<!-- part: rows -->\n<!-- part: rowz -->\n"));
        e = assertThrows(TemplateError.class, () -> modularBlueprint(stray, "all"));
        assertTrue(e.getMessage().contains("rowz"), e.getMessage());
    }
}

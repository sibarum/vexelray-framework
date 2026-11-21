package dev.vexelray.framework.highlight;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.draw.Ink;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundle of grammars, held to its word. A claim with no grammar behind it, or a grammar TM4E cannot actually run,
 * both fail the same silent way in an application: the file opens as plain text and nothing says why. These tests say
 * why.
 *
 * <p>Two of them guard upstream breakages found while bundling, both invisible until a document was tokenized: CSS on
 * vscode's {@code main} uses a variable-length look-behind that joni rejects, which threw partway through a document
 * and cost an HTML file with a {@code <style>} block all its colours; and YAML on {@code main} is a dispatcher whose
 * captures TM4E cannot parse. Both are pinned to the 1.96.0 tag. If someone re-syncs them from main, these fail.
 *
 * <p>Carried from the text editor's {@code GrammarsTest}, where these were written; the Pontif cases stayed there.
 */
final class HighlightingTest {

    static final Color COMMENT = Color.rgb(0x7f8994);
    static final Color STRING = Color.rgb(0xa6d189);
    static final Color CONSTANT = Color.rgb(0xe5a477);
    static final Color KEYWORD = Color.rgb(0x8aa9f0);
    static final Color TYPE = Color.rgb(0x7dd3e6);
    static final Color NAME = Color.rgb(0xc8d3e2);
    static final Color INSERTED = Color.rgb(0xa6d189);
    static final Color DELETED = Color.rgb(0xf7768e);

    /** The families these tests ask about, in the shape of an application's own table. */
    static final Colours COLOURS = Colours.of(
            Colour.of("comment", COMMENT),
            Colour.of("string", STRING),
            Colour.of("constant", CONSTANT),
            Colour.of("keyword", KEYWORD),
            Colour.of("storage", KEYWORD),
            Colour.of("entity.name.type", TYPE),
            Colour.of("variable.other", NAME),
            Colour.of("entity.name.tag", Color.rgb(0xf7768e)),
            Colour.of("support.type", TYPE),
            Colour.of("markup.inserted", INSERTED),
            Colour.of("markup.deleted", DELETED),
            Colour.of("punctuation", Color.rgb(0x8b95a1)));

    private static final Highlighting HIGHLIGHTING = Highlighting.of(COLOURS, List.of());

    private static String scope(String fileName) {
        return scope(HIGHLIGHTING, fileName);
    }

    static String scope(Highlighting highlighting, String fileName) {
        String scope = highlighting.scopeFor(fileName);
        assertNotNull(scope, fileName + " should map to a scope");
        assertTrue(highlighting.highlights(scope), scope + " is claimed by " + fileName + " but nothing colours it");
        return scope;
    }

    private static Color colourOf(String fileName, String source, String needle) {
        return colourOf(HIGHLIGHTING, fileName, source, needle);
    }

    /** The colour of the span covering exactly {@code needle}, or null if nothing colours it. */
    static Color colourOf(Highlighting highlighting, String fileName, String source, String needle) {
        int start = source.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("'" + needle + "' is not in the sample; the test is wrong, not the code");
        }
        for (Span span : highlighting.spans(scope(highlighting, fileName), source)) {
            if (span.start() == start && span.end() == start + needle.length()) {
                return Ink.fixedOf(span.fg());
            }
        }
        return null;
    }

    /**
     * The colour covering the first character of {@code needle}. Some grammars, YAML among them, split a scalar's first
     * character into a token of its own, so no one span covers the whole word though every part of it is one colour.
     */
    private static Color colourAt(String fileName, String source, String needle) {
        int at = source.indexOf(needle);
        if (at < 0) {
            throw new AssertionError("'" + needle + "' is not in the sample; the test is wrong, not the code");
        }
        for (Span span : HIGHLIGHTING.spans(scope(fileName), source)) {
            if (span.covers(at)) {
                return Ink.fixedOf(span.fg());
            }
        }
        return null;
    }

    // ---- nothing is claimed for a grammar that is not there ----

    @Test
    void everyClaimedExtensionHasAGrammar() {
        for (String extension : HIGHLIGHTING.extensions()) {
            scope("sample." + extension);
        }
    }

    @Test
    void everyClaimedFileNameHasAGrammar() {
        for (String name : HIGHLIGHTING.fileNames()) {
            scope(name);
        }
    }

    /** A bundled language's scope is its grammar's own: the file is what other grammars embed it by. */
    @Test
    void everyBundledLanguageIsTheGrammarItSaysItIs() {
        for (Language language : Highlighting.BUNDLED) {
            IGrammar grammar = HIGHLIGHTING.grammar(language.scope());
            assertNotNull(grammar, language + " has no grammar");
            assertEquals(language.scope(), grammar.getScopeName());
        }
        assertEquals(16, Highlighting.BUNDLED.size(), "sixteen, as the class comment and the docs say");
    }

    /** The status line says a language's name, never its scope: a grammar added without a name would show one. */
    @Test
    void everyClaimedFormatHasAName() {
        for (String extension : HIGHLIGHTING.extensions()) {
            String scope = HIGHLIGHTING.scopeFor("sample." + extension);
            assertNotEquals(scope, HIGHLIGHTING.nameOf(scope), scope + " has no name");
        }
        for (String name : HIGHLIGHTING.fileNames()) {
            String scope = HIGHLIGHTING.scopeFor(name);
            assertNotEquals(scope, HIGHLIGHTING.nameOf(scope), scope + " has no name");
        }
        assertEquals("Plain text", HIGHLIGHTING.nameOf(null));
        assertEquals("source.unknown", HIGHLIGHTING.nameOf("source.unknown"), "a scope nobody named shows as itself");
        assertEquals("JSON with comments", HIGHLIGHTING.nameOf("source.json.comments"));
    }

    /** A whole name beats an extension, and it is the only thing that works for these two shapes of file. */
    @Test
    void filesWithoutAUsableExtensionStillFindTheirGrammar() {
        assertEquals("source.dockerfile", HIGHLIGHTING.scopeFor("Dockerfile"), "no extension at all");
        assertEquals("source.dockerfile", HIGHLIGHTING.scopeFor("dockerfile"));
        assertEquals("source.shell", HIGHLIGHTING.scopeFor(".bashrc"), "nothing but an extension");
        assertEquals("source.shell", HIGHLIGHTING.scopeFor(".ZSHRC"), "matched case-insensitively, like extensions");
        assertEquals("source.java", HIGHLIGHTING.scopeFor("A.JAVA"));
    }

    @Test
    void plainTextHasNoScopeAndNoColours() {
        assertNull(HIGHLIGHTING.scopeFor("notes.txt"));
        assertNull(HIGHLIGHTING.scopeFor("README"));
        assertNull(HIGHLIGHTING.scopeFor(null));
        assertTrue(HIGHLIGHTING.spans(null, "anything").isEmpty());
        assertTrue(HIGHLIGHTING.spans("source.unknown", "anything").isEmpty());
        assertFalse(HIGHLIGHTING.highlights(null));
        assertFalse(HIGHLIGHTING.highlights("source.unknown"));
    }

    // ---- the two upstream breakages ----

    /** CSS is bundled for HTML's sake as much as for .css files, and the failure guarded was a throw, not a colour. */
    @Test
    void cssTokenizesRatherThanThrowing() {
        String src = ".cls, a#id > b + i { color: #fff; margin: 0 auto; }\n"
                + "@media print and (min-width: 5px) { a:hover::before { content: \"x\"; } }\n";
        assertFalse(HIGHLIGHTING.spans(scope("site.css"), src).isEmpty(),
                "a stylesheet with no spans means the grammar silently did nothing");
    }

    @Test
    void htmlStyleBlocksAreColouredAndDoNotPoisonThePage() {
        String src = "<html><style>a { color: red; }</style><p>hi</p></html>\n";
        assertNotNull(colourOf("page.html", src, "color"), "<style> is why CSS is bundled at all");
        assertNotNull(colourOf("page.html", src, "p"), "and the markup around it still has to colour");
    }

    @Test
    void yamlActuallyColoursSomething() {
        String src = "# c\nname: value\nlist:\n  - one\nquoted: \"s\"\n";
        assertFalse(HIGHLIGHTING.spans(scope("config.yaml"), src).isEmpty(),
                "the dispatcher-only grammar on main parses but colours nothing; this catches that swap");
        assertEquals(STRING, colourAt("config.yaml", src, "value"));
    }

    // ---- the languages, briefly ----

    @Test
    void javaColoursItsKeywordsAndStrings() {
        String src = "final class C { String s = \"text\"; }\n";
        assertEquals(KEYWORD, colourOf("C.java", src, "final"));
        assertEquals(STRING, colourAt("C.java", src, "text"));
    }

    @Test
    void aDiffIsRedAndGreen() {
        String src = "--- a/f.txt\n+++ b/f.txt\n@@ -1,2 +1,2 @@\n context\n-gone\n+added\n";
        assertEquals(DELETED, colourOf("change.diff", src, "gone"));
        assertEquals(INSERTED, colourOf("change.diff", src, "added"));
    }

    @Test
    void shellPowerShellAndBatchColourTheirKeywords() {
        assertEquals(KEYWORD, colourOf("run.sh", "if [ -f x ]; then echo hi; fi\n", "if"));
        String ps = "function Get-Thing {\n    return $items.Count\n}\n";
        assertEquals(KEYWORD, colourOf("run.ps1", ps, "function"));
        assertNotNull(colourOf("run.ps1", ps, "items"), "PowerShell is mostly variables");
        assertNotNull(colourOf("run.cmd", "@echo off\nset NAME=world\n", "set"));
    }

    @Test
    void theRestOfTheFormatsColourTheirObviousTokens() {
        assertEquals(KEYWORD, colourOf("app.ini", "[section]\nkey=value\n", "key"));
        assertNotNull(colourOf("Dockerfile", "FROM alpine:3.19\nRUN true\n", "FROM"));
        assertNotNull(colourOf("tsconfig.jsonc", "{\n  // c\n  \"k\": 1\n}\n", " c"),
                "the comment is the whole point of jsonc");
    }

    /** Markdown colours fenced code with whatever grammar the fence names, so each one bundled pays off twice. */
    @Test
    void markdownFencesUseTheOtherGrammars() {
        assertEquals(DELETED, colourOf("notes.md", "```diff\n-gone\n```\n", "gone"));
        assertEquals(KEYWORD, colourOf("notes.md", "```python\ndef f():\n    pass\n```\n", "def"));
        assertTrue(HIGHLIGHTING.spans(scope("notes.md"), "```yaml\nkey: value\n```\n").size() > 2,
                "a yaml fence should be more than its own backticks");
    }

    // ---- limits ----

    @Test
    void aLineTooLongToTrustLeavesTheWholeFilePlain() {
        String src = "int a;\n" + "x".repeat(Highlighting.MAX_LINE_CHARS + 1) + "\nint b;\n";
        assertTrue(HIGHLIGHTING.spans(scope("A.java"), src).isEmpty());
        String fits = "int a;\n" + "x".repeat(Highlighting.MAX_LINE_CHARS) + "\nint b;\n";
        assertFalse(HIGHLIGHTING.spans(scope("A.java"), fits).isEmpty(), "the limit is the limit, not one short of it");
    }

    @Test
    void aDocumentTooLargeIsPlainText() {
        String line = "final class C { int n = 4; }\n";
        String large = line.repeat(Highlighting.MAX_CHARS / line.length() + 1);
        assertTrue(large.length() > Highlighting.MAX_CHARS);
        assertTrue(HIGHLIGHTING.spans(scope("A.java"), large).isEmpty());
    }

    /** The spans are an answer to send, and nothing the receiver may change under the sender. */
    @Test
    void theSpansCannotBeChanged() {
        List<Span> spans = HIGHLIGHTING.spans(scope("A.java"), "final class A {}\n");
        assertFalse(spans.isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, spans::clear);
    }

    // ---- one part, two requests at once ----

    /**
     * The job that colours documents may run two requests at once, and a grammar compiles its rules lazily as it
     * tokenizes, so this is several threads driving one piece of mutable state. The race is worst on a cold grammar, so
     * each round starts a fresh part. Asserts the answers rather than merely that nothing threw: a lost rule id need not
     * crash, it can just as well colour a token wrong.
     */
    @Test
    void oneGrammarTokenizesTheSameUnderConcurrentDocuments() throws Exception {
        String source = """
                package p;
                /* a comment */
                final class C {
                    String s = "text";
                    int n = 42;
                }
                """;
        String scope = scope("Cold.java");
        List<Span> expected = HIGHLIGHTING.spans(scope, source);
        assertFalse(expected.isEmpty(), "the sample should colour something to begin with");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 4; round++) {
                Highlighting cold = Highlighting.of(COLOURS, List.of());
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<Span>>> runs = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    runs.add(pool.submit(() -> {
                        start.await();
                        return cold.spans(scope, source);
                    }));
                }
                start.countDown();
                for (Future<List<Span>> run : runs) {
                    assertEquals(expected, run.get(30, TimeUnit.SECONDS), "every thread should see the same colours");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Two different languages at once, from one cold part: what restoring a session of a Java file, a pom and a README
     * does. The registry's theme answers every grammar's scope lookups through one plain {@code HashMap}, which a lock
     * per grammar did not cover in the text editor's 0.1.
     */
    @Test
    void differentGrammarsFromOnePartTokenizeTogether() throws Exception {
        List<String> scopes = List.of(scope("a.java"), scope("a.md"), scope("a.xml"));
        List<String> sources = List.of(
                "package p;\n/* c */\nfinal class C { String s = \"t\"; int n = 4; }\n".repeat(20),
                "# Title\n\nSome *text* with `code` and a [link](http://x).\n\n```java\nint x;\n```\n".repeat(20),
                "<?xml version=\"1.0\"?>\n<a b=\"c\"><!-- d --><e>f</e></a>\n".repeat(20));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 8; round++) {
                Highlighting cold = Highlighting.of(COLOURS, List.of());
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<Span>>> runs = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    int which = i % 3;
                    runs.add(pool.submit(() -> {
                        start.await();
                        return cold.spans(scopes.get(which), sources.get(which));
                    }));
                }
                start.countDown();
                for (Future<List<Span>> run : runs) {
                    assertFalse(run.get(30, TimeUnit.SECONDS).isEmpty(), "every document should be coloured");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The grammars are parsed once for the part, not once per document: the reason it is a part at all. */
    @Test
    void everyDocumentOfALanguageGetsTheSameGrammar() {
        IGrammar first = HIGHLIGHTING.grammar(HIGHLIGHTING.scopeFor("A.java"));
        assertSame(first, HIGHLIGHTING.grammar(HIGHLIGHTING.scopeFor("B.java")), "two Java documents should share one grammar");
        assertNotSame(first, HIGHLIGHTING.grammar(HIGHLIGHTING.scopeFor("b.py")), "different languages are different grammars");
    }

    /** Asking which file is which parses nothing, so a view may ask on the GUI thread and a start pays nothing. */
    @Test
    void whichFileIsWhichParsesNoGrammar() {
        Highlighting fresh = Highlighting.of(COLOURS, List.of());
        assertEquals("source.java", fresh.scopeFor("A.java"));
        assertEquals("Java", fresh.nameOf("source.java"));
        assertFalse(fresh.extensions().isEmpty());
        assertFalse(fresh.grammarsRead(), "no grammar should be read until a document wants one");
    }
}

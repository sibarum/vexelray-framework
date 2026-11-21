package dev.vexelray.framework.highlight;

import dev.vexelray.gui.core.text.Span;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.vexelray.framework.highlight.HighlightingTest.COLOURS;
import static dev.vexelray.framework.highlight.HighlightingTest.COMMENT;
import static dev.vexelray.framework.highlight.HighlightingTest.CONSTANT;
import static dev.vexelray.framework.highlight.HighlightingTest.KEYWORD;
import static dev.vexelray.framework.highlight.HighlightingTest.NAME;
import static dev.vexelray.framework.highlight.HighlightingTest.colourOf;
import static dev.vexelray.framework.highlight.HighlightingTest.scope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam for a language of the application's own: a {@link Lexer}, or another TextMate grammar from its resources,
 * each one {@link Language} laid over the bundled ones and coloured by the same table.
 */
final class LanguageTest {

    /** A toy language: words, where "let" is a keyword, digits a number, and '#' to the end of a line a comment. */
    private static final Lexer TOY = (text, tokens) -> {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int start = i;
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                tokens.token(start, i, "comment.line.toy");
            } else if (Character.isDigit(c)) {
                while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
                tokens.token(start, i, "constant.numeric.toy");
            } else if (Character.isLetter(c)) {
                while (i < text.length() && Character.isLetter(text.charAt(i))) i++;
                tokens.token(start, i, text.substring(start, i).equals("let") ? "keyword.other.toy" : "variable.other.toy");
            } else {
                i++;
            }
        }
    };

    private static final Language TOY_LANGUAGE = Language.lexed("source.toy", "Toy", TOY).extensions("toy")
            .fileNames("Toyfile");

    private static final Highlighting WITH_TOY = Highlighting.of(COLOURS, List.of(TOY_LANGUAGE));

    // ---- a lexer ----

    @Test
    void aLexedLanguageIsClaimedNamedAndColouredByTheSameTable() {
        String src = "let x = 42 # the answer\n";
        assertEquals("source.toy", scope(WITH_TOY, "a.toy"));
        assertEquals("source.toy", WITH_TOY.scopeFor("A.TOY"), "matched case-insensitively, like the bundled claims");
        assertEquals("source.toy", WITH_TOY.scopeFor("toyfile"), "by whole name too");
        assertEquals("Toy", WITH_TOY.nameOf("source.toy"));
        assertEquals(KEYWORD, colourOf(WITH_TOY, "a.toy", src, "let"));
        assertEquals(NAME, colourOf(WITH_TOY, "a.toy", src, "x"));
        assertEquals(CONSTANT, colourOf(WITH_TOY, "a.toy", src, "42"));
        assertEquals(COMMENT, colourOf(WITH_TOY, "a.toy", src, "# the answer"));
        assertTrue(WITH_TOY.extensions().contains("toy"));
        assertTrue(WITH_TOY.fileNames().contains("toyfile"));
    }

    @Test
    void theBundledLanguagesAreStillThere() {
        assertEquals("source.java", WITH_TOY.scopeFor("A.java"));
        assertEquals(KEYWORD, colourOf(WITH_TOY, "A.java", "final class A {}\n", "final"));
    }

    @Test
    void aLexerThatThrowsGivesNoColoursAndBreaksNothing() {
        Highlighting h = Highlighting.of(COLOURS, List.of(Language.lexed("source.bad", "Bad", (text, tokens) -> {
            tokens.token(0, 1, "keyword.bad");
            throw new IllegalStateException("a lexer with a bug");
        }).extensions("bad")));
        assertEquals(List.of(), h.spans("source.bad", "let"));
        assertEquals(KEYWORD, colourOf(h, "A.java", "final class A {}\n", "final"), "and every other language still colours");
    }

    @Test
    void tokensOutsideTheTextAreCutEmptyOnesDroppedAndUncolouredOnesLeftOut() {
        Highlighting h = Highlighting.of(COLOURS, List.of(Language.lexed("source.edge", "Edge", (text, tokens) -> {
            tokens.token(-5, 3, "keyword.edge");             // starts before the text
            tokens.token(4, 4, "keyword.edge");              // empty
            tokens.token(5, 1, "keyword.edge");              // backwards
            tokens.token(6, 7, "nothing.this.table.knows");  // no rule
            tokens.token(8, 99, "constant.edge");            // runs past the end
            tokens.token(4, 5, null);                        // no scope
        })));
        List<Span> spans = h.spans("source.edge", "abcdefghij");
        assertEquals(2, spans.size(), spans.toString());
        assertEquals(0, spans.get(0).start());
        assertEquals(3, spans.get(0).end());
        assertEquals(8, spans.get(1).start());
        assertEquals(10, spans.get(1).end());
    }

    @Test
    void tokensToldOutOfOrderComeBackInOrder() {
        Highlighting h = Highlighting.of(COLOURS, List.of(Language.lexed("source.back", "Back", (text, tokens) -> {
            tokens.token(4, 6, "keyword.back");
            tokens.token(0, 2, "constant.back");
        })));
        List<Span> spans = h.spans("source.back", "abcdefgh");
        assertEquals(List.of(0, 4), spans.stream().map(Span::start).toList());
    }

    /** A lexer takes no lock and parses no grammar: a document of its language never waits behind a TextMate one. */
    @Test
    void aLexedDocumentReadsNoGrammar() {
        Highlighting h = Highlighting.of(COLOURS, List.of(TOY_LANGUAGE));
        assertFalse(h.spans("source.toy", "let x\n").isEmpty());
        assertTrue(h.highlights("source.toy"));
        assertFalse(h.grammarsRead(), "colouring a lexed document should read no grammar");
    }

    @Test
    void aDocumentPastTheSizeLimitIsNotLexed() {
        AtomicInteger calls = new AtomicInteger();
        Highlighting h = Highlighting.of(COLOURS, List.of(Language.lexed("source.count", "Count",
                (text, tokens) -> calls.incrementAndGet())));
        h.spans("source.count", "x".repeat(Highlighting.MAX_CHARS + 1));
        assertEquals(0, calls.get());
    }

    // ---- a grammar of the application's own ----

    private static final Language LEDGER = Language.grammar("source.ledger", "Ledger", LanguageTest.class,
            "/dev/vexelray/framework/highlight/ledger.tmLanguage.json").extensions("ledger");

    @Test
    void anApplicationsGrammarIsTheSameShapeAndColoursAsABundledOne() {
        Highlighting h = Highlighting.of(COLOURS, List.of(LEDGER));
        String src = "debit 40 # rent\ncredit 12\n";
        assertEquals("Ledger", h.nameOf(scope(h, "may.ledger")));
        assertEquals(KEYWORD, colourOf(h, "may.ledger", src, "debit"));
        assertEquals(KEYWORD, colourOf(h, "may.ledger", src, "credit"));
        assertEquals(CONSTANT, colourOf(h, "may.ledger", src, "40"));
        assertEquals(COMMENT, colourOf(h, "may.ledger", src, "# rent"));
    }

    @Test
    void aGrammarThatIsNotThereIsAStartupError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Language.grammar("source.x", "X",
                LanguageTest.class, "/no/such/grammar.tmLanguage.json"));
        assertTrue(e.getMessage().contains("/no/such/grammar.tmLanguage.json"), e.getMessage());
    }

    /** The file's scopeName and the claims disagree: the grammar colours nothing, and the others are untouched. */
    @Test
    void aGrammarWhoseScopeIsNotTheLanguagesColoursNothing() {
        Highlighting h = Highlighting.of(COLOURS, List.of(Language.grammar("source.misnamed", "Misnamed",
                LanguageTest.class, "/dev/vexelray/framework/highlight/misnamed.tmLanguage.json").extensions("mis")));
        assertEquals("source.misnamed", h.scopeFor("a.mis"));
        assertFalse(h.highlights("source.misnamed"));
        assertEquals(List.of(), h.spans("source.misnamed", "debit\n"));
        assertEquals(KEYWORD, colourOf(h, "A.java", "final class A {}\n", "final"));
    }

    @Test
    void aGrammarThatCannotBeReadColoursNothingAndBreaksNoOther() {
        Highlighting h = Highlighting.of(COLOURS, List.of(Language.grammar("source.broken", "Broken",
                LanguageTest.class, "/dev/vexelray/framework/highlight/broken.tmLanguage.json").extensions("broken")));
        assertFalse(h.highlights("source.broken"));
        assertEquals(List.of(), h.spans("source.broken", "anything\n"));
        assertEquals(KEYWORD, colourOf(h, "A.java", "final class A {}\n", "final"));
    }

    // ---- collisions ----

    /** An application's claim is its own, and its scope replaces a bundled language of that scope. */
    @Test
    void anApplicationsLanguageWinsOverABundledOne() {
        Highlighting h = Highlighting.of(COLOURS, List.of(
                Language.lexed("source.mine", "Mine", TOY).extensions("md"),
                Language.lexed("source.java", "Java, lexed", TOY)));
        assertEquals("source.mine", h.scopeFor("README.md"), "the application's claim on an extension wins");
        assertEquals("Java, lexed", h.nameOf("source.java"), "and its scope replaces the bundled language's name");
        assertEquals("source.java", h.scopeFor("A.java"), "keeping the bundled claims that point at it");
        assertEquals(KEYWORD, colourOf(h, "A.java", "let x\n", "let"), "coloured by the application's lexer now");
        assertNull(h.grammar("source.java"), "and not by the bundled grammar");
    }

    @Test
    void twoOfTheApplicationsLanguagesClaimingOneThingIsRefusedNamingBoth() {
        Language a = Language.lexed("source.a", "A", TOY).extensions("x");
        Language b = Language.lexed("source.b", "B", TOY).extensions("X");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Highlighting.of(COLOURS, List.of(a, b)));
        assertTrue(e.getMessage().contains("source.a") && e.getMessage().contains("source.b")
                && e.getMessage().contains(".x"), e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> Highlighting.of(COLOURS, List.of(
                Language.lexed("source.a", "A", TOY), Language.lexed("source.a", "A again", TOY))), "one scope twice");
        assertThrows(IllegalArgumentException.class, () -> Highlighting.of(COLOURS, List.of(
                Language.lexed("source.a", "A", TOY).fileNames("Makefile"),
                Language.lexed("source.b", "B", TOY).fileNames("makefile"))), "one file name twice");
    }

    // ---- the shape ----

    @Test
    void claimsAreNormalisedAndReplacedNotAdded() {
        Language l = Language.lexed("source.n", "N", TOY).extensions(".NN", "nn", "Mm").fileNames("Build.N");
        assertEquals(Set.of("nn", "mm"), l.extensions());
        assertEquals(Set.of("build.n"), l.fileNames());
        assertEquals(Set.of("q"), l.extensions("q").extensions(), "a wither replaces what was claimed");
        assertEquals(Set.of("toyfile"), TOY_LANGUAGE.extensions("q").fileNames(), "and leaves the other claims as they were");
    }

    @Test
    void whatCouldNeverMatchIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Language.lexed("source.n", "N", TOY).extensions("tar.gz"));
        assertThrows(IllegalArgumentException.class, () -> Language.lexed("source.n", "N", TOY).extensions(""));
        assertThrows(IllegalArgumentException.class, () -> Language.lexed("source.n", "N", TOY).fileNames("a/b"));
        assertThrows(IllegalArgumentException.class, () -> Language.lexed("source n", "N", TOY));
        assertThrows(IllegalArgumentException.class, () -> Language.lexed("source.n", " ", TOY));
        assertThrows(NullPointerException.class, () -> Language.lexed("source.n", "N", null));
    }
}

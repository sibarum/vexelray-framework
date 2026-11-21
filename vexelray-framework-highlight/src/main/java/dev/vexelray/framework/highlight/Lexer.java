package dev.vexelray.framework.highlight;

import dev.vexelray.framework.api.Stability;

/**
 * A language's own token scan, for a language that is not a TextMate grammar: given a document, it says where each
 * token is and what kind, as a TextMate-style scope, and {@link Highlighting} colours each through the same
 * {@link Colours} as every grammar's tokens. So a language with a lexer of its own wears the application's palette and
 * not one of its own, and gains a colour when the table does.
 *
 * {@snippet :
 * Lexer lexer = (text, tokens) -> {
 *     for (MyTokens.Token t : MyTokens.scan(text)) {
 *         tokens.token(t.start(), t.end(), t.isKeyword() ? "keyword.other.mine" : "variable.other.mine");
 *     }
 * };
 * }
 *
 * <p><b>Which thread runs it.</b> Whichever asks {@link Highlighting#spans} for a document of its language, which in an
 * application is the job that colours documents, and two documents may be coloured at once. So a lexer keeps no state
 * between calls, or keeps it safe from several threads itself. It runs outside the lock TextMate grammars share, so a
 * document of its language never waits behind one of theirs.
 *
 * <p><b>A lexer that throws gives the document no colours</b>, with a warning in the log, and never a broken editor. A
 * token outside the text is cut to it, an empty one is dropped, and one whose scope no rule colours is drawn in the
 * field's ink.
 */
@FunctionalInterface
@Stability(Stability.Level.EXPERIMENTAL)
public interface Lexer {

    /** Every token of {@code text} worth colouring, told to {@code tokens} in order of where it starts. */
    void lex(String text, Tokens tokens);

    /** Where a lexer says what it found. */
    @FunctionalInterface
    interface Tokens {

        /**
         * The characters from {@code start} (inclusive) to {@code end} (exclusive) are one token, whose innermost
         * scope is {@code scope}: {@code "keyword.other.mine"}, say, which a table's {@code keyword} rule colours.
         */
        void token(int start, int end, String scope);
    }
}

package dev.vexelray.framework.highlight;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.text.Span;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;
import sibarum.probe.Log;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Syntax highlighting: which language a file is in, what that language is called, and a document's colours as
 * {@link Span}s, safe to ask from any thread.
 *
 * <p>Sixteen TextMate grammars are bundled, from microsoft/vscode (MIT, {@code grammars/VSCODE-LICENSE.txt} beside
 * them in this jar): Java, Python, JavaScript, JSON and JSON with comments, Markdown, HTML, CSS, XML, YAML, Diff, INI,
 * Shell, PowerShell, Batch and Dockerfile. An application adds a language of its own, a grammar or a {@link Lexer},
 * as a {@link Language}. The file name picks the language; a grammar is tokenized line by line by TM4E, threading its
 * rule stack, and a lexer scans the whole text; and each token's scope is coloured by the application's
 * {@link Colours}.
 *
 * <p><b>A failure means no colours, never a broken editor.</b> A document too large to be worth it (over half a
 * megabyte), a line too long to trust (over twenty thousand characters, a minified or generated file, past which the
 * grammar's state would be wrong anyway), a line the tokenizer gives up on (200 ms), a grammar that throws and a lexer
 * that throws each give the document no colours, and the last two say so in the log.
 *
 * <h2>Built once, and why every grammar shares one lock</h2>
 *
 * <p>Parsing a grammar is slow and its compiled rules are worth keeping, so this is built once, as a part, and the job
 * that colours documents takes it: a job keeps no state of its own, since two requests may run at once, and a part the
 * container supplies is how it takes something that does. The grammars are parsed when the first document wants one,
 * so a start that opens none pays nothing.
 *
 * <p>Those two requests are why every tokenize is under one lock, and it is one lock rather than one per grammar: a
 * grammar compiles its rules lazily while it tokenizes, and every grammar a registry makes resolves scopes through the
 * registry's one theme, whose match cache is a plain {@code HashMap}. A Java document and a Markdown one tokenizing
 * together threw {@code ConcurrentModificationException} out of {@code Theme.match} in the text editor's 0.1, which
 * locked per grammar. A document only re-tokenizes when its own text changes, and only one is being typed into, so the
 * wait is felt only while a session restores. A {@link Lexer} keeps no state of TM4E's, so it takes no lock and never
 * waits behind a grammar.
 *
 * <p><b>Which file is which is free to ask anywhere</b>, a view on the GUI thread included: {@link #scopeFor},
 * {@link #nameOf}, {@link #extensions} and {@link #fileNames} read tables fixed at construction and take no lock.
 * {@link #spans} is a pass over a whole document, so it belongs to a job, never to the frame.
 *
 * <p>Not a value: the compiled grammars are state, kept under the lock. So a {@code @Component} does not take one (the
 * processor refuses a provided value that is not deeply immutable); it reaches highlighting by a message to the job
 * that holds it, as the text editor's {@code MarkText} is reached.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Highlighting {

    private static final Log LOG = Log.of("framework.highlight");

    /** Above these, a document is plain text rather than regexes ground over pathological input. */
    static final int MAX_CHARS = 512 * 1024;
    static final int MAX_LINE_CHARS = 20_000;
    static final Duration LINE_TIME_LIMIT = Duration.ofMillis(200);

    /** Where the bundled grammars are in this jar, beside their licence. */
    static final String GRAMMARS = "/dev/vexelray/framework/highlight/grammars/";

    // The markdown grammar refers to embedded languages that are not bundled, and TM4E logs a WARNING per missing
    // scope through java.util.logging. Held strongly: JUL keeps loggers weakly, and a collected logger forgets the
    // level set on it.
    private static final java.util.logging.Logger TM4E_LOG = java.util.logging.Logger.getLogger("org.eclipse.tm4e");

    static {
        TM4E_LOG.setLevel(java.util.logging.Level.SEVERE);
    }

    /**
     * The bundled languages: each grammar, and the files it claims. css and yaml are pinned to vscode 1.96.0, unlike
     * the rest: main's css uses a variable-length look-behind that joni rejects mid-document, and main's yaml is a
     * dispatcher whose captures TM4E cannot parse. {@code HighlightingTest} fails if either is re-synced from main.
     */
    static final List<Language> BUNDLED = List.of(
            bundled("source.java", "Java", "java").extensions("java"),
            bundled("source.python", "Python", "python").extensions("py", "pyw", "pyi"),
            bundled("source.json", "JSON", "JSON").extensions("json"),
            bundled("text.html.markdown", "Markdown", "markdown").extensions("md", "markdown"),
            bundled("text.xml", "XML", "xml").extensions("xml", "xsd", "xsl", "svg"),
            bundled("text.html.basic", "HTML", "html").extensions("html", "htm"),
            bundled("source.js", "JavaScript", "JavaScript").extensions("js", "mjs", "cjs"),
            bundled("source.css", "CSS", "css").extensions("css"),
            bundled("source.yaml", "YAML", "yaml").extensions("yaml", "yml"),
            bundled("source.json.comments", "JSON with comments", "JSONC").extensions("jsonc"),
            bundled("source.diff", "Diff", "diff").extensions("diff", "patch"),
            bundled("source.ini", "INI", "ini").extensions("ini", "properties"),
            // Dockerfile has no extension, and .bashrc is nothing but one: whole names, matched before extensions.
            bundled("source.dockerfile", "Dockerfile", "docker").extensions("dockerfile")
                    .fileNames("Dockerfile", "Containerfile"),
            bundled("source.shell", "Shell", "shell-unix-bash").extensions("sh", "bash", "zsh", "ksh")
                    .fileNames(".bashrc", ".bash_profile", ".bash_aliases", ".zshrc", ".zprofile", ".profile"),
            bundled("source.powershell", "PowerShell", "powershell").extensions("ps1", "psm1", "psd1"),
            bundled("source.batchfile", "Batch", "batchfile").extensions("bat", "cmd"));

    private static Language bundled(String scope, String name, String file) {
        return Language.grammar(scope, name, Highlighting.class, GRAMMARS + file + ".tmLanguage.json");
    }

    private final Colours colours;
    /** Scope -> the language of that scope, the application's laid over the bundled. */
    private final Map<String, Language> languages;
    private final Map<String, String> byExtension;
    private final Map<String, String> byName;
    private final Map<String, Lexer> lexers;

    /** Everything TM4E shares between documents; see the class comment. */
    private final Object tokenizing = new Object();
    /** Parsed on the first document that wants a grammar. Under {@link #tokenizing}. */
    private Registry registry;

    private Highlighting(Colours colours, Map<String, Language> languages, Map<String, String> byExtension,
                         Map<String, String> byName) {
        this.colours = colours;
        this.languages = languages;
        this.byExtension = byExtension;
        this.byName = byName;
        Map<String, Lexer> lexed = new HashMap<>();
        for (Language language : languages.values()) {
            if (language.lexer() != null) {
                lexed.put(language.scope(), language.lexer());
            }
        }
        this.lexers = Map.copyOf(lexed);
    }

    /**
     * The bundled languages and {@code languages} over them, coloured by {@code colours}. Parses nothing: the grammars
     * are read when the first document wants one.
     *
     * @param languages the application's own, in no particular order; see {@link Language} for how one that collides
     *                  with a bundled language wins
     * @throws IllegalArgumentException when two of {@code languages} have the same scope or claim the same extension or
     *                                  file name, naming both: nothing says which was meant
     */
    public static Highlighting of(Colours colours, List<Language> languages) {
        Objects.requireNonNull(colours, "colours");
        Map<String, Language> byScope = new LinkedHashMap<>();
        Map<String, String> byExtension = new HashMap<>();
        Map<String, String> byName = new HashMap<>();
        for (Language language : BUNDLED) {
            claim(language, byScope, byExtension, byName);
        }
        Map<String, Language> own = new HashMap<>();
        Map<String, Language> ownExtensions = new HashMap<>();
        Map<String, Language> ownNames = new HashMap<>();
        for (Language language : List.copyOf(languages)) {
            refuseTwice("the scope", language.scope(), language, own);
            for (String extension : language.extensions()) {
                refuseTwice("the extension ." + extension, extension, language, ownExtensions);
            }
            for (String name : language.fileNames()) {
                refuseTwice("the file name " + name, name, language, ownNames);
            }
            claim(language, byScope, byExtension, byName);
        }
        return new Highlighting(colours, java.util.Collections.unmodifiableMap(byScope), Map.copyOf(byExtension),
                Map.copyOf(byName));
    }

    private static void claim(Language language, Map<String, Language> byScope, Map<String, String> byExtension,
                              Map<String, String> byName) {
        byScope.put(language.scope(), language);
        for (String extension : language.extensions()) {
            byExtension.put(extension, language.scope());
        }
        for (String name : language.fileNames()) {
            byName.put(name, language.scope());
        }
    }

    private static void refuseTwice(String what, String key, Language language, Map<String, Language> seen) {
        Language before = seen.putIfAbsent(key, language);
        if (before != null) {
            throw new IllegalArgumentException(before + " and " + language + " both claim " + what + "; an"
                    + " application's languages are in no order, so nothing says which was meant. Leave the claim"
                    + " out of one of them");
        }
    }

    /** The scope of the language {@code fileName} is in, by its whole name and then its extension, or null for plain text. */
    public String scopeFor(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        String byWholeName = byName.get(name);
        if (byWholeName != null) {
            return byWholeName;
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : byExtension.get(name.substring(dot + 1));
    }

    /** What a person is shown for {@code scope}: its language's name, "Plain text" for none, or the scope itself. */
    public String nameOf(String scope) {
        if (scope == null) {
            return "Plain text";
        }
        Language language = languages.get(scope);
        return language == null ? scope : language.name();
    }

    /** Every extension some language claims, lower case and without the dot; unmodifiable. */
    public Set<String> extensions() {
        return byExtension.keySet();
    }

    /** Every whole file name some language claims, lower case; unmodifiable. */
    public Set<String> fileNames() {
        return byName.keySet();
    }

    /**
     * Whether a document in {@code scope} can be coloured at all: a lexer's, or a grammar TM4E could read. Reads the
     * grammars if nothing has yet, so this is a job's question, as {@link #spans} is.
     */
    public boolean highlights(String scope) {
        return scope != null && (lexers.containsKey(scope) || grammar(scope) != null);
    }

    /**
     * The colours of {@code text} as a document in {@code scope}, in order: none for no scope, a scope nothing colours,
     * a document past the limits, or a tokenizer that failed. See the class comment for the limits, and for why this
     * may wait on another document's grammar.
     */
    public List<Span> spans(String scope, String text) {
        if (scope == null || text.length() > MAX_CHARS) {
            return List.of();
        }
        Lexer lexer = lexers.get(scope);
        if (lexer != null) {
            return lexed(scope, lexer, text);
        }
        synchronized (tokenizing) {
            IGrammar grammar = grammar(scope);
            if (grammar == null) {
                return List.of();
            }
            try {
                return tokenize(grammar, text);
            } catch (RuntimeException e) {
                LOG.warn("highlighting " + scope + " failed; showing plain text", e);
                return List.of();
            }
        }
    }

    /** The grammar for {@code scope}, compiled once and shared by every document of it; null for none. */
    IGrammar grammar(String scope) {
        synchronized (tokenizing) {
            if (registry == null) {
                registry = registry();
            }
            return scope == null || lexers.containsKey(scope) ? null : registry.grammarForScopeName(scope);
        }
    }

    /** Whether the grammars have been read yet: how a test sees that a start pays nothing. */
    boolean grammarsRead() {
        synchronized (tokenizing) {
            return registry != null;
        }
    }

    /** Every grammar the languages name, read once. A grammar that cannot be read is left out, and says so. */
    private Registry registry() {
        Registry r = new Registry();
        for (Language language : languages.values()) {
            if (language.resource() == null) {
                continue;
            }
            try {
                IGrammar grammar = r.addGrammar(IGrammarSource.fromResource(language.owner(), language.resource()));
                if (!language.scope().equals(grammar.getScopeName())) {
                    LOG.error("the grammar at " + language.resource() + " is for " + grammar.getScopeName() + ", not "
                            + language.scope() + " as its Language says, so " + language.name() + " is plain text."
                            + " A grammar's scope is the scopeName written in its file");
                }
            } catch (RuntimeException e) {
                LOG.error("the grammar at " + language.resource() + " could not be read, so " + language.name()
                        + " is plain text", e);
            }
        }
        return r;
    }

    private List<Span> tokenize(IGrammar grammar, String text) {
        List<Span> spans = new ArrayList<>();
        IStateStack state = null;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd);
            if (line.length() > MAX_LINE_CHARS) {
                // A minified or generated line: past it the grammar's state would be wrong anyway, so give up on the
                // whole file rather than mislabel the rest.
                return List.of();
            }
            ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine(line, state, LINE_TIME_LIMIT);
            state = result.getRuleStack();
            for (IToken token : result.getTokens()) {
                Paint paint = colours.paintOf(token.getScopes());
                int start = lineStart + Math.min(token.getStartIndex(), line.length());
                int end = lineStart + Math.min(token.getEndIndex(), line.length());
                if (paint != null && end > start) {
                    spans.add(Span.foreground(start, end, paint));
                }
            }
            lineStart = lineEnd + 1;
        }
        return java.util.Collections.unmodifiableList(spans);
    }

    /** A lexed document's colours: each token through the colour table, cut to the text. */
    private List<Span> lexed(String scope, Lexer lexer, String text) {
        Collected collected = new Collected(colours, text.length());
        try {
            lexer.lex(text, collected);
        } catch (RuntimeException e) {
            LOG.warn("highlighting " + scope + " failed; showing plain text", e);
            return List.of();
        }
        return collected.spans();
    }

    /** What a lexer tells, kept as spans. One per document, so it needs no lock. */
    private static final class Collected implements Lexer.Tokens {

        private final Colours colours;
        private final int length;
        private final List<Span> spans = new ArrayList<>();
        private boolean ordered = true;

        Collected(Colours colours, int length) {
            this.colours = colours;
            this.length = length;
        }

        @Override
        public void token(int start, int end, String scope) {
            int from = Math.max(0, start);
            int to = Math.min(length, end);
            Paint paint = colours.paintOf(scope);
            if (paint == null || to <= from) {
                return;
            }
            if (!spans.isEmpty() && spans.getLast().start() > from) {
                ordered = false;
            }
            spans.add(Span.foreground(from, to, paint));
        }

        List<Span> spans() {
            if (!ordered) {
                spans.sort(Comparator.comparingInt(Span::start));
            }
            return java.util.Collections.unmodifiableList(spans);
        }
    }
}

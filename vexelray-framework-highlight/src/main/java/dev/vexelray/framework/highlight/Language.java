package dev.vexelray.framework.highlight;

import dev.vexelray.framework.api.Stability;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A language {@link Highlighting} colours: its scope, what it is called, the files it claims, and what finds its
 * tokens, which is either a TextMate grammar or a {@link Lexer} of its own.
 *
 * {@snippet :
 * // A language with its own token scan, coloured by the same table as every grammar:
 * Language.lexed("source.mine", "Mine", MyLexer::lex).extensions("mine");
 * // Another TextMate grammar, from the application's own resources:
 * Language.grammar("source.toml", "TOML", Recipes.class, "/grammars/toml.tmLanguage.json").extensions("toml");
 * }
 *
 * <p><b>Both are one shape</b>, because they are one thing to everything but the tokenizer: the scope is the key, the
 * name is what a status line shows, and the claims are how a file finds it. The bundled grammars are languages of
 * this same shape, so an application's language is not a special case anywhere: it is one more entry, which
 * {@link Highlighting#of} lays over the bundled ones.
 *
 * <p><b>An application's language wins over a bundled one</b> it collides with, claim by claim: an extension or a file
 * name it claims is its own, and a scope it shares replaces the bundled language of that scope (its name, and what
 * colours it), keeping the bundled claims that point at it. Two of the application's own languages claiming the same
 * thing is refused at startup, naming both, since nothing says which was meant.
 *
 * <p><b>A grammar's scope is the grammar's own.</b> The scope given here must be the {@code scopeName} written in the
 * grammar file, and it is what other grammars embed it by (a Markdown fence names its language that way). If they
 * differ, the grammar colours nothing, with an error in the log, because the file says one thing and the claims
 * another and either could be the mistake. The file is read when the first document of any grammar is coloured, so
 * that is when a broken one shows; whether the resource exists at all is checked here, at startup.
 *
 * <p>A final class with factories and withers rather than a record, so a field it gains later is not a change to every
 * application that builds one.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Language {

    private final String scope;
    private final String name;
    private final Set<String> extensions;
    private final Set<String> fileNames;
    /** What finds the tokens: exactly one of the lexer and the grammar resource. */
    private final Lexer lexer;
    private final Class<?> owner;
    private final String resource;

    private Language(String scope, String name, Set<String> extensions, Set<String> fileNames, Lexer lexer,
                     Class<?> owner, String resource) {
        this.scope = scope;
        this.name = name;
        this.extensions = extensions;
        this.fileNames = fileNames;
        this.lexer = lexer;
        this.owner = owner;
        this.resource = resource;
    }

    /**
     * A language whose tokens {@code lexer} finds, claiming no files until {@link #extensions} or {@link #fileNames}
     * says which.
     *
     * @param scope its TextMate-style scope, conventionally {@code source.<language>}: what a document's scope is
     *              when it is in this language, and the key every other part of highlighting knows it by
     * @param name  what a person is shown, on a status line say: {@code "Pontif"}
     */
    public static Language lexed(String scope, String name, Lexer lexer) {
        return new Language(scope(scope), name(name), Set.of(), Set.of(), Objects.requireNonNull(lexer, "lexer"),
                null, null);
    }

    /**
     * A language coloured by the TextMate grammar at {@code resource}, read through {@code owner}'s class loader as
     * {@link Class#getResource} reads it (so an absolute path starts with {@code /}). JSON, YAML and plist grammars are
     * read, by the file's extension. A native image needs the resource registered, which is the application's
     * metadata, since it is the application's file.
     *
     * @param scope the grammar's own {@code scopeName}; see the class comment
     * @throws IllegalArgumentException when there is no such resource: a typo is a startup error, not a document that
     *                                  is quietly plain text
     */
    public static Language grammar(String scope, String name, Class<?> owner, String resource) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resource, "resource");
        if (owner.getResource(resource) == null) {
            throw new IllegalArgumentException("the grammar for " + scope + " is not at " + resource + ", read through "
                    + owner.getName() + ".class.getResource; a path from the root of the class path starts with /");
        }
        return new Language(scope(scope), name(name), Set.of(), Set.of(), null, owner, resource);
    }

    /**
     * This language claiming files by these extensions, in place of any it claimed: {@code "java"}, matched without
     * regard to case. A leading dot is allowed and dropped.
     *
     * @throws IllegalArgumentException for an empty extension or one with a dot or a path separator inside it: a
     *                                  file's extension is what follows its last dot, so that could never match
     */
    public Language extensions(String... extensions) {
        Set<String> out = new LinkedHashSet<>();
        for (String e : extensions) {
            String x = Objects.requireNonNull(e, "extension").toLowerCase(Locale.ROOT);
            x = x.startsWith(".") ? x.substring(1) : x;
            if (x.isEmpty() || x.indexOf('.') >= 0 || x.indexOf('/') >= 0 || x.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("\"" + e + "\" is not an extension a file name can end in; a whole"
                        + " name like \".bashrc\" or \"Dockerfile\" is a fileNames claim");
            }
            out.add(x);
        }
        return new Language(scope, name, Set.copyOf(out), fileNames, lexer, owner, resource);
    }

    /**
     * This language claiming files by these whole names, in place of any it claimed: {@code "Dockerfile"},
     * {@code ".bashrc"}, matched without regard to case and before any extension, the more specific claim of the two.
     *
     * @throws IllegalArgumentException for an empty name or one with a path separator in it
     */
    public Language fileNames(String... fileNames) {
        Set<String> out = new LinkedHashSet<>();
        for (String f : fileNames) {
            String x = Objects.requireNonNull(f, "file name").toLowerCase(Locale.ROOT);
            if (x.isEmpty() || x.indexOf('/') >= 0 || x.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("\"" + f + "\" is not a file name");
            }
            out.add(x);
        }
        return new Language(scope, name, extensions, Set.copyOf(out), lexer, owner, resource);
    }

    /** The scope, the key everything else knows this language by. */
    public String scope() {
        return scope;
    }

    /** What a person is shown. */
    public String name() {
        return name;
    }

    /** The extensions it claims, lower case and without the dot; unmodifiable. */
    public Set<String> extensions() {
        return extensions;
    }

    /** The whole file names it claims, lower case; unmodifiable. */
    public Set<String> fileNames() {
        return fileNames;
    }

    /** Its lexer, or null when a grammar colours it. */
    Lexer lexer() {
        return lexer;
    }

    /** The class its grammar is read through, or null when a lexer colours it. */
    Class<?> owner() {
        return owner;
    }

    /** Its grammar's resource path, or null when a lexer colours it. */
    String resource() {
        return resource;
    }

    @Override
    public String toString() {
        return "Language[" + scope + " \"" + name + "\" " + (lexer != null ? "lexed" : resource) + "]";
    }

    private static String scope(String scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope.isBlank() || scope.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("a language's scope is one TextMate scope, like \"source.java\", and \""
                    + scope + "\" is not");
        }
        return scope;
    }

    private static String name(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a language needs a name to be shown by");
        }
        return name;
    }
}

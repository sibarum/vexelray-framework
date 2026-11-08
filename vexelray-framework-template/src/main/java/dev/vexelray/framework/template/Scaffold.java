package dev.vexelray.framework.template;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A template plus a set of answers, worked out into files.
 *
 * <p>The whole of the engine's public shape is here and it is one method: hand it
 * a template, what somebody said, and where the files come from, and it hands
 * back every byte it would write. Nothing in this package touches a disk except
 * {@link Blueprint.Writing}, and nothing decides to.
 *
 * <p>That split is what lets the shell put a plan on the screen and lets a test
 * assert on the contents of a generated pom without a temporary directory.
 */
public final class Scaffold {

    /**
     * Files that are copied rather than filled in.
     *
     * <p>A template made of text is the ordinary case, and substituting a
     * {@code ${...}} into a PNG would be a way of quietly corrupting it. Rather
     * than keeping a list of extensions -- which is a list that is always one
     * entry out of date -- a file that is not valid UTF-8 is copied through
     * untouched. Anything a person could have typed a placeholder into decodes;
     * anything that does not decode had no placeholder in it.
     */
    private Scaffold() {}

    /**
     * Everything the template would write, with every placeholder resolved.
     *
     * @param answers what somebody said, after {@link Answers#filled} -- callers
     *                that skip that step will be told about the placeholders it
     *                would have supplied, which is the right failure but a
     *                confusing one
     */
    public static Blueprint of(Template template, Answers answers, Catalogue catalogue) {
        List<Blueprint.Entry> entries = new ArrayList<>(template.items().size());
        List<String> notes = new ArrayList<>();
        Template.Choice choice = template.choose(answers.get(Template.MODULES));
        if (!choice.problems().isEmpty()) {
            TemplateError.Builder error = TemplateError.of(String.join("; ", choice.problems()));
            return throwing(error.build());
        }
        notes.addAll(choice.notes());
        for (Template.Item item : template.items()) {
            String path = resolvePath(item.path(), answers, template);
            if (!choice.has(item.module())) {
                notes.add(path + " left out (the " + item.module() + " module was not chosen)");
                continue;
            }
            Template.Condition failed = failed(item.conditions(), answers);
            if (failed != null) {
                notes.add(path + " left out (" + failed.flag() + " is " + (failed.unless() ? "yes" : "no") + ")");
                continue;
            }
            byte[] source = catalogue.read(template.id(), item.source());
            byte[] bytes = render(source, answers, item.source(), path);
            entries.add(new Blueprint.Entry(path, gather(bytes, item, template, answers, catalogue, choice)));
        }
        return new Blueprint(List.copyOf(entries), List.copyOf(notes));
    }

    /** The first condition that does not hold, or null when all of them do. */
    private static Template.Condition failed(List<Template.Condition> conditions, Answers answers) {
        for (Template.Condition c : conditions) if (!c.holds(answers.flag(c.flag()))) return c;
        return null;
    }

    private static <T> T throwing(TemplateError error) { throw error; }

    // ---- parts ---------------------------------------------------------------------------

    /**
     * A rendered file with its markers filled: each {@code <!-- part: name -->} line replaced by the text of every
     * part for it whose module was chosen and whose conditions hold, in the manifest's order, or removed when there
     * is none. A marker no part line names, and a part whose marker is not in its file, are both refused whatever
     * was chosen, so a misspelling cannot hide behind a module that happened to be left out.
     */
    private static byte[] gather(byte[] bytes, Template.Item item, Template template, Answers answers,
                                 Catalogue catalogue, Template.Choice choice) {
        List<Template.Part> parts = new ArrayList<>();
        for (Template.Part part : template.parts()) if (part.file().equals(item.path())) parts.add(part);
        String text = decode(bytes);
        if (text == null) {
            if (parts.isEmpty()) return bytes;
            throw TemplateError.of(item.path() + " is not text, so nothing can be added to it").build();
        }
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\r?\n", -1);
        Set<String> markers = new LinkedHashSet<>();
        for (String line : lines) {
            String marker = markerIn(line);
            if (marker != null) markers.add(marker);
        }
        if (markers.isEmpty() && parts.isEmpty()) return bytes;
        for (Template.Part part : parts) {
            if (!markers.contains(part.marker())) {
                throw TemplateError.of(template.id() + " adds the " + part.marker() + " part of " + part.module()
                                + " to " + item.path() + ", which has no " + part.line() + " line")
                        .hint("its markers are: " + (markers.isEmpty() ? "none" : String.join(", ", markers)))
                        .build();
            }
        }
        for (String marker : markers) {
            if (parts.stream().noneMatch(p -> p.marker().equals(marker))) {
                throw TemplateError.of(item.path() + " has a " + Template.Part.marker(marker)
                                + " line that no part line fills")
                        .hint("add one: part " + item.path() + " " + marker + " from <file> in <module>")
                        .build();
            }
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String marker = markerIn(lines[i]);
            if (marker == null) {
                out.append(lines[i]);
                if (i < lines.length - 1) out.append(newline);
                continue;
            }
            for (Template.Part part : parts) {
                if (!part.marker().equals(marker) || !choice.has(part.module())) continue;
                if (failed(part.conditions(), answers) != null) continue;
                String piece = decode(render(catalogue.read(template.id(), part.source()), answers, part.source(),
                        item.path()));
                if (piece == null) {
                    throw TemplateError.of(part.source() + " is not text, so it cannot be added to "
                            + item.path()).build();
                }
                piece = piece.replace("\r\n", "\n");
                if (!piece.endsWith("\n")) piece += "\n";
                out.append(piece.replace("\n", newline));
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The marker a line is, or null when it is not one. */
    private static String markerIn(String line) {
        String stripped = line.strip();
        if (!stripped.startsWith("<!-- part: ") || !stripped.endsWith(" -->")) return null;
        return stripped.substring("<!-- part: ".length(), stripped.length() - " -->".length()).strip();
    }

    /** Where the project folder itself goes, under the folder somebody chose. */
    public static Path folder(Template template, Answers answers, Path where) {
        String name = answers.resolve(template.folder()).strip();
        if (name.isEmpty()) {
            throw TemplateError.of("the new project has no name")
                    .hint("the template says its folder is " + template.folder())
                    .build();
        }
        // One segment, always. A template whose folder resolved to a path would be
        // a template that could put files two directories up from where it was
        // pointed, which is not something a folder chooser can be asked about.
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw TemplateError.of("the new project's name is not a plain name: " + name)
                    .hint("it becomes one folder inside the one you chose")
                    .build();
        }
        return where.resolve(name);
    }

    // ---- rendering -----------------------------------------------------------------------

    private static String resolvePath(String path, Answers answers, Template template) {
        String resolved = answers.resolve(path);
        if (resolved.contains("//") || resolved.startsWith("/") || resolved.contains("..")) {
            throw TemplateError.of(template.id() + " would write to " + resolved)
                    .hint("that came from: " + path)
                    .hint("a template writes underneath the project folder and nowhere else")
                    .build();
        }
        return resolved;
    }

    /**
     * One file's bytes, with its placeholders filled in -- or exactly as they were,
     * when the file is not text.
     */
    private static byte[] render(byte[] source, Answers answers, String name, String path) {
        String text = decode(source);
        if (text == null) return source;
        try {
            String resolved = answers.resolve(text);
            if (isBatchFile(path)) resolved = crlf(resolved);
            return resolved.getBytes(StandardCharsets.UTF_8);
        } catch (TemplateError e) {
            // The message from Answers says which placeholder; only this level
            // knows which file it was in, and that is the half that saves the time.
            throw TemplateError.of(e.getMessage() + ", and " + name + " uses it")
                    .hint(hints(e))
                    .because(e)
                    .build();
        }
    }

    /**
     * A Windows batch file, which cmd.exe is the only reader of.
     *
     * <p>cmd.exe misreads a batch file with bare LF line endings (a {@code goto} or {@code call :label} can miss its
     * label, a line straddling a 512-byte read is split), and which endings a template file has is decided by git,
     * not by its author: the repository stores text as LF, and a checkout may or may not convert it. So the endings
     * are decided here, by where the file lands. That is a fact about one format and its one reader, not a list of
     * extensions to keep up to date; every other file is written with the endings it came with.
     */
    private static boolean isBatchFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    /** {@code text} with every line ending, whatever it was, as CR LF. */
    private static String crlf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
    }

    private static String hints(TemplateError e) {
        return e.hints().isEmpty() ? null : String.join("; ", e.hints());
    }

    /** The text of a file, or null when it is not UTF-8 and so is not text. */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}

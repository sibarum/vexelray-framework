package dev.vexelray.framework.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reading a template's manifest.
 *
 * <p>A line-based format of its own, which wants a word of justification because
 * MainFrame already has a perfectly good one. The engine cannot use it: the reader
 * is in the shell, and a template engine that could only parse its own templates
 * inside MainFrame would be a template engine that is not reusable, which is the
 * whole reason this is a module. So it parses its own, the format is small enough
 * to hold in the head, and the duplication is the boundary being real rather than
 * a directory that looks tidy.
 *
 * <pre>
 * template  vexel-desktop
 * title     VexelRay desktop application
 * summary   a window on vexelray-gui, wired the way the reference is
 * folder    ${artifactId}
 *
 * slot artifactId
 *     label     project name
 *     kind      text
 *     required  yes
 *     preset    my-app
 *     match     [a-z][a-z0-9]*(-[a-z0-9]+)*
 *     help      the folder it goes in, and the Maven artifactId
 *
 * fill blank  packageName  package  ${groupId}.${artifactId}
 * fill always packagePath  path     ${packageName}
 *
 * file pom.xml
 * file src/main/java/${packagePath}/${className}.java  from App.java
 * file src/main/java/${packagePath}/Capture.java       when capture
 * file src/test/java/${packagePath}/CaptureTest.java   when tests when capture
 *
 * needs dev.vexelray.gui:vexelray-gui-widget:${guiVersion}
 * </pre>
 *
 * <p>A line beginning with {@code #} is a comment, a blank line is nothing, and a
 * line's first word is what it is. Indentation is for reading: a {@code label}
 * line belongs to the {@code slot} above it because it says {@code label}, not
 * because of the spaces in front of it.
 *
 * <p>A file line may say {@code when} and {@code unless} as often as it needs, and
 * the file is written only if every one holds: a test of something optional waits on
 * both the tests and the thing.
 *
 * <p>A template may be made of modules, each a set of whole files somebody takes or
 * leaves (see {@link Template.Module}). A module is declared like a slot, and a file
 * says which one it is in; a {@code part} is what a module adds to a file it shares,
 * in place of a marker line there:
 *
 * <pre>
 * slot modules
 *     required  no
 *     preset    all
 *
 * module later
 *     about     do something later, or once the typing stops
 *     requires  examples
 *
 * file src/main/java/${packagePath}/later/Later.java  from Later.java  in later
 * part EXAMPLES.md rows  from later-row.md  in later
 * </pre>
 *
 * <p>A module's keys are not {@code summary} and {@code needs} because the top level
 * has both, and a line's first word is what it is.
 *
 * <p>The whole thing is refused if any part of it is wrong, and every refusal
 * carries the line number. That is the same promise the rest of MainFrame makes,
 * and here it earns its keep twice over: a manifest that half-loaded would produce
 * a project with a placeholder still sitting in it.
 */
public final class Manifest {

    /** The keys a slot may use, in the order they are worth reading in. */
    private static final List<String> SLOT_KEYS = List.of(
            "label", "kind", "required", "preset", "match", "help", "least", "most", "choose", "rule");

    /** The keys a module may use. */
    private static final List<String> MODULE_KEYS = List.of("about", "requires");

    /** The keys a manifest may use at the top level. */
    private static final List<String> TOP_KEYS = List.of(
            "template", "title", "summary", "folder", "next", "slot", "fill", "file", "needs", "module", "part");

    private Manifest() {}

    /**
     * Reads a manifest.
     *
     * @param text  the manifest's whole contents
     * @param where what to call it in an error -- a path, usually
     */
    public static Template read(String text, String where) {
        String id = null;
        String title = null;
        String summary = null;
        String folder = null;
        String next = null;
        List<Template.Slot> slots = new ArrayList<>();
        List<Template.Fill> fills = new ArrayList<>();
        List<Template.Item> items = new ArrayList<>();
        List<Template.Need> needs = new ArrayList<>();
        List<Template.Module> modules = new ArrayList<>();
        List<Template.Part> parts = new ArrayList<>();
        Draft draft = null;
        ModuleDraft module = null;

        String[] lines = text.split("\r\n|\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            int line = i + 1;
            String raw = lines[i].strip();
            if (raw.isEmpty() || raw.startsWith("#")) continue;
            String word = firstWord(raw);
            String rest = raw.substring(word.length()).strip();

            // A slot's keys are read while its slot is open, so one of them turning
            // up before any slot is a mistake worth naming rather than ignoring.
            if (SLOT_KEYS.contains(word)) {
                if (draft == null) {
                    throw at(where, line, word + " belongs to a slot, and no slot has been opened")
                            .hint("open one first, e.g. slot artifactId")
                            .build();
                }
                draft.set(word, rest, where, line);
                continue;
            }
            if (MODULE_KEYS.contains(word)) {
                if (module == null) {
                    throw at(where, line, word + " belongs to a module, and no module has been opened")
                            .hint("open one first, e.g. module later")
                            .build();
                }
                module.set(word, rest, where, line);
                continue;
            }
            if (draft != null) {
                slots.add(draft.build(where));
                draft = null;
            }
            if (module != null) {
                modules.add(module.build());
                module = null;
            }

            switch (word) {
                case "template" -> id = one(rest, where, line, "template", "an id, e.g. vexel-desktop");
                case "title" -> title = one(rest, where, line, "title", "a title people will read");
                case "summary" -> summary = one(rest, where, line, "summary", "one line about it");
                case "folder" -> folder = one(rest, where, line, "folder",
                        "what the new folder is called, e.g. folder ${artifactId}");
                case "next" -> next = one(rest, where, line, "next",
                        "what to do once it is written, e.g. next cd ${artifactId} && mvn test");
                case "slot" -> draft = new Draft(one(rest, where, line, "slot", "a name, e.g. slot artifactId"), line);
                case "module" -> module = new ModuleDraft(one(rest, where, line, "module", "a name, e.g. module later"));
                case "fill" -> fills.add(fill(rest, where, line));
                case "file" -> items.add(item(rest, where, line));
                case "part" -> parts.add(part(rest, where, line));
                case "needs" -> needs.add(need(rest, where, line));
                default -> throw at(where, line, "a manifest has no " + quoted(word) + " line")
                        .hint("it can say: " + String.join(", ", TOP_KEYS))
                        .hint("a slot can say: " + String.join(", ", SLOT_KEYS))
                        .hint("a module can say: " + String.join(", ", MODULE_KEYS))
                        .build();
            }
        }
        if (draft != null) slots.add(draft.build(where));
        if (module != null) modules.add(module.build());

        if (id == null || id.isBlank()) {
            throw TemplateError.of(where + " does not say which template it is")
                    .hint("its first line should be: template <id>")
                    .build();
        }
        if (items.isEmpty()) {
            throw TemplateError.of(id + " writes no files")
                    .hint("a template needs at least one, e.g. file pom.xml")
                    .build();
        }
        checkWhole(id, slots, fills, items);
        checkModules(id, slots, items, modules, parts);
        return new Template(id,
                title == null ? id : title,
                summary == null ? "" : summary,
                folder == null ? "${" + firstSlotName(slots) + "}" : folder,
                next,
                List.copyOf(slots), List.copyOf(fills), List.copyOf(items), List.copyOf(needs),
                List.copyOf(modules), List.copyOf(parts));
    }

    // ---- the lines that are not slots --------------------------------------------------

    /** {@code fill blank packageName package ${groupId}.${artifactId}} */
    private static Template.Fill fill(String rest, String where, int line) {
        String[] parts = rest.split("\\s+", 4);
        if (parts.length < 4) {
            throw at(where, line, "a fill says when, what, how, and from what")
                    .hint("e.g. fill always packagePath path ${packageName}")
                    .hint("when is always or blank; how is one of: " + String.join(", ", Transform.names()))
                    .build();
        }
        Template.Fill.When when = switch (parts[0]) {
            case "always" -> Template.Fill.When.ALWAYS;
            case "blank" -> Template.Fill.When.BLANK;
            default -> throw at(where, line, "a fill happens always, or when the answer is blank -- not "
                    + quoted(parts[0])).build();
        };
        Transform how = Transform.of(parts[2]);
        if (how == null) {
            throw at(where, line, "there is no " + quoted(parts[2]) + " transform")
                    .hint("the transforms are: " + String.join(", ", Transform.names()))
                    .build();
        }
        return new Template.Fill(parts[1], when, how, parts[3]);
    }

    /** {@code file src/main/java/${packagePath}/Capture.java from Capture.java in capture when tests} */
    private static Template.Item item(String rest, String where, int line) {
        List<String> words = words(rest);
        if (words.isEmpty()) {
            throw at(where, line, "a file line needs a path").hint("e.g. file pom.xml").build();
        }
        Tail tail = tail(words, 1, "file", where, line);
        return new Template.Item(words.getFirst(), tail.source == null ? words.getFirst() : tail.source,
                tail.module, tail.conditions);
    }

    /** {@code part EXAMPLES.md rows from later-row.md in later} */
    private static Template.Part part(String rest, String where, int line) {
        List<String> words = words(rest);
        if (words.size() < 2) {
            throw at(where, line, "a part line names the file it adds to and the marker it goes in place of")
                    .hint("e.g. part EXAMPLES.md rows from later-row.md in later")
                    .build();
        }
        Tail tail = tail(words, 2, "part", where, line);
        if (tail.source == null) {
            throw at(where, line, "a part line needs from: the file whose text it adds").build();
        }
        if (tail.module == null) {
            throw at(where, line, "a part line needs in: the module it belongs to")
                    .hint("text every project gets goes in the file itself")
                    .build();
        }
        return new Template.Part(words.get(0), words.get(1), tail.source, tail.module, tail.conditions);
    }

    /** What may follow a file's or a part's path: where it comes from, its module, and its conditions. */
    private record Tail(String source, String module, List<Template.Condition> conditions) {}

    private static Tail tail(List<String> words, int from, String kind, String where, int line) {
        String source = null;
        String module = null;
        // Every one must hold: "in later when tests" is an example's test, written only if both do.
        List<Template.Condition> conditions = new ArrayList<>();
        for (int i = from; i < words.size(); i++) {
            String key = words.get(i);
            if (i + 1 >= words.size()) {
                throw at(where, line, key + " on a " + kind + " line needs a value after it").build();
            }
            String value = words.get(++i);
            switch (key) {
                case "from" -> source = value;
                case "in" -> {
                    if (module != null) {
                        throw at(where, line, "a " + kind + " line is in one module, not " + module + " and " + value)
                                .build();
                    }
                    module = value;
                }
                case "when" -> conditions.add(new Template.Condition(value, false));
                case "unless" -> conditions.add(new Template.Condition(value, true));
                default -> throw at(where, line, "a " + kind + " line has no " + quoted(key))
                        .hint("it can say: from, in, when, unless")
                        .build();
            }
        }
        return new Tail(source, module, conditions);
    }

    /** {@code needs dev.vexelray.gui:vexelray-gui-widget:${guiVersion}} */
    private static Template.Need need(String rest, String where, int line) {
        String[] parts = rest.split(":");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw at(where, line, "a needs line is group:artifact:version")
                    .hint("e.g. needs dev.vexelray:vexelray-surface:${vexelrayVersion}")
                    .build();
        }
        return new Template.Need(parts[0].strip(), parts[1].strip(), parts[2].strip());
    }

    // ---- a slot, gathered a line at a time ----------------------------------------------

    private static final class Draft {

        private final String name;
        private final int line;
        private String label;
        private Template.Kind kind = Template.Kind.TEXT;
        private boolean required = true;
        private String preset;
        private String match;
        private String help;
        private Long least;
        private Long most;
        private final List<String> choices = new ArrayList<>();
        private final List<String> rules = new ArrayList<>();

        Draft(String name, int line) {
            this.name = name;
            this.line = line;
        }

        void set(String key, String value, String where, int at) {
            switch (key) {
                case "label" -> label = value;
                case "help" -> help = value;
                case "preset" -> preset = value;
                case "match" -> {
                    try {
                        Pattern.compile(value);
                    } catch (PatternSyntaxException e) {
                        throw Manifest.at(where, at,
                                name + " has a match that is not a pattern: " + e.getDescription()).build();
                    }
                    match = value;
                }
                case "kind" -> {
                    kind = Template.Kind.of(value.toLowerCase(Locale.ROOT));
                    if (kind == null) {
                        throw Manifest.at(where, at, "there is no " + quoted(value) + " kind of slot")
                                .hint("the kinds are: " + String.join(", ", Template.Kind.names()))
                                .build();
                    }
                }
                case "required" -> required = value.equals("yes") || value.equals("true");
                case "least" -> least = number(value, where, at, name + " has a least that");
                case "most" -> most = number(value, where, at, name + " has a most that");
                case "choose" -> choices.addAll(words(value));
                case "rule" -> rules.addAll(words(value));
                default -> throw Manifest.at(where, at, "a slot has no " + quoted(key)).build();
            }
        }

        private static long number(String value, String where, int at, String what) {
            try {
                return Long.parseLong(value.strip());
            } catch (NumberFormatException e) {
                throw Manifest.at(where, at, what + " is not a number: " + value).build();
            }
        }

        Template.Slot build(String where) {
            if (name.isBlank()) throw Manifest.at(where, line, "a slot needs a name").build();
            for (String rule : rules) {
                if (!Checks.knows(rule)) {
                    throw Manifest.at(where, line, "there is no " + quoted(rule) + " rule")
                            .hint("the rules are: " + String.join(", ", Checks.ruleNames()))
                            .build();
                }
            }
            return new Template.Slot(name,
                    label == null || label.isBlank() ? name.replace('-', ' ') : label,
                    kind, required, preset, match, help, least, most,
                    choices.isEmpty() ? null : List.copyOf(choices),
                    List.copyOf(rules));
        }
    }

    // ---- a module, gathered a line at a time ----------------------------------------------

    private static final class ModuleDraft {

        private final String name;
        private String about;
        private final List<String> requires = new ArrayList<>();

        ModuleDraft(String name) { this.name = name; }

        void set(String key, String value, String where, int at) {
            switch (key) {
                case "about" -> about = value;
                case "requires" -> requires.addAll(words(value));
                default -> throw Manifest.at(where, at, "a module has no " + quoted(key)).build();
            }
        }

        Template.Module build() {
            return new Template.Module(name, about == null ? "" : about, requires);
        }
    }

    // ---- refusing a manifest that would half-work ---------------------------------------

    /**
     * The checks that need the whole file rather than one line: that no name is
     * claimed twice, that a conditional file names a yes-or-no that exists, and
     * that nothing writes to the same place twice.
     */
    private static void checkWhole(String id, List<Template.Slot> slots, List<Template.Fill> fills,
                                   List<Template.Item> items) {
        Set<String> names = new LinkedHashSet<>();
        for (Template.Slot slot : slots) {
            if (!names.add(slot.name())) {
                throw TemplateError.of(id + " asks for " + slot.name() + " twice")
                        .hint("each slot is one placeholder, so each needs its own name")
                        .build();
            }
        }
        Set<String> flags = new LinkedHashSet<>();
        for (Template.Slot slot : slots) if (slot.kind() == Template.Kind.FLAG) flags.add(slot.name());
        for (Template.Fill fill : fills) {
            if (fill.when() == Template.Fill.When.BLANK && !names.contains(fill.name())) {
                throw TemplateError.of(id + " fills in " + fill.name()
                                + " when it is left blank, and there is no such slot to leave blank")
                        .hint("use \"fill always\" for a value nobody is asked about")
                        .build();
            }
            names.add(fill.name());
        }
        for (Template.Item item : items) {
            for (Template.Condition c : item.conditions()) {
                if (!flags.contains(c.flag())) {
                    throw TemplateError.of(id + " writes " + item.path() + " only when "
                                    + quoted(c.flag()) + " says so, and there is no such yes-or-no slot")
                            .hint(flags.isEmpty()
                                    ? "add one: slot " + c.flag() + ", then kind flag"
                                    : "the yes-or-no slots are: " + String.join(", ", flags))
                            .build();
                }
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Template.Item item : items) {
            if (!paths.add(item.path())) {
                throw TemplateError.of(id + " writes " + item.path() + " twice")
                        .hint("two lines that resolve to one path would mean the second silently won")
                        .build();
            }
        }
    }

    /**
     * The checks modules need the whole file for: that each is named once, that what a module requires exists and
     * does not come back round to it, that a template with modules asks which, and that a file or a part is in a
     * module that exists. A part may add to a file of the base, of its own module, or of a module it requires, and
     * to no other: a module that wrote into one it does not require would be the reach across modules that the
     * whole-file rule exists to stop.
     */
    private static void checkModules(String id, List<Template.Slot> slots, List<Template.Item> items,
                                     List<Template.Module> modules, List<Template.Part> parts) {
        Map<String, Template.Module> byName = new LinkedHashMap<>();
        for (Template.Module module : modules) {
            if (byName.put(module.name(), module) != null) {
                throw TemplateError.of(id + " declares the " + module.name() + " module twice").build();
            }
        }
        for (Template.Module module : modules) {
            for (String required : module.requires()) {
                if (!byName.containsKey(required)) {
                    throw TemplateError.of(module.name() + " requires " + required + ", and " + id
                                    + " has no such module")
                            .hint("its modules are: " + String.join(", ", byName.keySet()))
                            .build();
                }
            }
            if (reach(module.name(), byName).contains(module.name())) {
                throw TemplateError.of(module.name() + " requires itself, by way of the modules it requires")
                        .hint("a module that comes round to itself can never be left out on its own")
                        .build();
            }
        }
        if (!modules.isEmpty()) {
            Template.Slot slot = null;
            for (Template.Slot s : slots) if (s.name().equals(Template.MODULES)) slot = s;
            if (slot == null || slot.kind() == Template.Kind.FLAG) {
                throw TemplateError.of(id + " has modules and does not ask which: it needs a text slot called "
                                + Template.MODULES)
                        .hint("e.g. slot modules, then preset all")
                        .build();
            }
        }
        for (Template.Item item : items) {
            if (item.module() != null && !byName.containsKey(item.module())) {
                throw unknownModule(id, item.path(), item.module(), byName);
            }
        }
        Set<String> flags = new LinkedHashSet<>();
        for (Template.Slot slot : slots) if (slot.kind() == Template.Kind.FLAG) flags.add(slot.name());
        for (Template.Part part : parts) {
            String what = "the " + part.marker() + " part of " + part.file();
            if (!byName.containsKey(part.module())) throw unknownModule(id, what, part.module(), byName);
            if (!part.marker().matches("[A-Za-z0-9_-]+")) {
                throw TemplateError.of(what + ": a marker is one word of letters, digits, - and _").build();
            }
            for (Template.Condition c : part.conditions()) {
                if (!flags.contains(c.flag())) {
                    throw TemplateError.of(id + " adds " + what + " only when " + quoted(c.flag())
                            + " says so, and there is no such yes-or-no slot").build();
                }
            }
            Template.Item file = null;
            for (Template.Item item : items) if (item.path().equals(part.file())) file = item;
            if (file == null) {
                throw TemplateError.of(id + " adds " + what + ", and writes no file at " + part.file())
                        .hint("a part names the path of a file line, as the manifest writes it")
                        .build();
            }
            if (file.module() != null && !file.module().equals(part.module())
                    && !reach(part.module(), byName).contains(file.module())) {
                throw TemplateError.of(part.module() + " adds to " + part.file() + ", which is "
                                + file.module() + "'s, and " + part.module() + " does not require it")
                        .hint("a module adds to the base's files, its own, and those of the modules it requires")
                        .build();
            }
        }
    }

    /** Every module {@code name} requires, directly or by way of another. */
    private static Set<String> reach(String name, Map<String, Template.Module> byName) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(byName.get(name).requires());
        while (!todo.isEmpty()) {
            String next = todo.removeLast();
            if (seen.add(next) && byName.containsKey(next)) todo.addAll(byName.get(next).requires());
        }
        return seen;
    }

    private static TemplateError unknownModule(String id, String what, String module,
                                               Map<String, Template.Module> byName) {
        return TemplateError.of(id + " puts " + what + " in the " + module + " module, and declares no such module")
                .hint(byName.isEmpty()
                        ? "declare it: module " + module
                        : "its modules are: " + String.join(", ", byName.keySet()))
                .build();
    }

    // ---- odds and ends -------------------------------------------------------------------

    /**
     * What a template that did not say gets called.
     *
     * <p>The first slot that is not a folder, rather than simply the first. Almost
     * every template opens by asking where the project goes, so "the first slot" is
     * usually the destination -- and a folder named after the folder it is being
     * created in is not a near miss, it is nonsense, refused later by
     * {@code Scaffold.folder} with a message about the wrong thing. Guessing the
     * first slot that could plausibly be a name is right far more often, and wrong
     * in a way somebody can see.
     */
    private static String firstSlotName(List<Template.Slot> slots) {
        for (Template.Slot slot : slots) {
            if (slot.kind() != Template.Kind.FOLDER) return slot.name();
        }
        return slots.isEmpty() ? "name" : slots.getFirst().name();
    }

    private static String firstWord(String line) {
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
        return line.substring(0, end);
    }

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.strip().split("\\s+")) if (!word.isEmpty()) words.add(word);
        return words;
    }

    private static String one(String rest, String where, int line, String key, String wanted) {
        if (rest.isBlank()) throw at(where, line, key + " needs " + wanted).build();
        return rest;
    }

    private static String quoted(String text) { return "\"" + text + "\""; }

    private static TemplateError.Builder at(String where, int line, String message) {
        return TemplateError.of(where + " line " + line + ": " + message);
    }
}

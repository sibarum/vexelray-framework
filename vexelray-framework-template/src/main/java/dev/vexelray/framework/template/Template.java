package dev.vexelray.framework.template;

import java.util.ArrayList;
import java.util.List;

/**
 * A template: what to ask for, and what to write once it has been answered.
 *
 * <p>Data, not code. A template is read out of a manifest and is inert until
 * something hands it a set of answers -- which is what lets the same definition
 * drive the form a person fills in, the check a script runs over a record it
 * built itself, and the listing {@code templates} prints. There is no second
 * description of a template anywhere.
 *
 * <p>The engine knows nothing about any particular template, and that is the
 * point rather than a nicety: the day a second one is added, the only new thing
 * in the world should be a folder of files and a manifest beside them.
 */
/**
 * @param next    what to tell somebody to do once it is written, or null. Comes from
 *                the template because only the template knows: a Maven project ends
 *                with {@code mvn compile exec:exec} and a folder of notes ends with
 *                nothing at all, and a shell that guessed would be confidently wrong
 *                for every template but the one it was written against.
 * @param modules the parts somebody may take or leave, in the order they are listed
 *                (see {@link Module}); empty for a template that is all of a piece
 * @param parts   what each module adds to a file it shares (see {@link Part})
 */
public record Template(
        String id,
        String title,
        String summary,
        String folder,
        String next,
        List<Slot> slots,
        List<Fill> fills,
        List<Item> items,
        List<Need> needs,
        List<Module> modules,
        List<Part> parts) {

    /**
     * The slot a template with modules is answered by: {@code all}, {@code none}, or the
     * modules' names. One name for every template, because the engine reads it, and a
     * manifest that declares a module and not this slot is refused.
     */
    public static final String MODULES = "modules";

    /** What a slot's answer will be read as. Deliberately fewer than a form has. */
    public enum Kind {
        /** Text. */
        TEXT("string"),
        /** A whole number. */
        NUMBER("int"),
        /** A yes-or-no. An item may be conditional on one of these and nothing else. */
        FLAG("bool"),
        /** A folder that is already there, offered with a chooser. */
        FOLDER("path");

        private final String written;

        Kind(String written) { this.written = written; }

        /** How the shell's form vocabulary spells this. */
        public String written() { return written; }

        static Kind of(String word) {
            for (Kind kind : values()) if (kind.name().toLowerCase().equals(word)) return kind;
            return null;
        }

        static List<String> names() {
            List<String> names = new ArrayList<>();
            for (Kind kind : values()) names.add(kind.name().toLowerCase());
            return List.copyOf(names);
        }
    }

    /**
     * One thing to ask for.
     *
     * @param match   a regular expression the answer has to match whole, or null
     * @param preset  what the field starts filled in with; may name other slots
     * @param rules   the extra checks by name -- see {@link Checks}
     */
    public record Slot(
            String name,
            String label,
            Kind kind,
            boolean required,
            String preset,
            String match,
            String help,
            Long least,
            Long most,
            List<String> choices,
            List<String> rules) {}

    /**
     * A value worked out rather than asked for.
     *
     * <p>Two reasons a template needs these. One is a placeholder nobody should be
     * asked about -- {@code packagePath} is {@code package} with the dots turned
     * into slashes, and asking twice is asking to be told two different things.
     * The other is a slot somebody may leave blank: {@code when BLANK} fills it in
     * from what they did type, so "leave it empty for the obvious answer" is a real
     * offer rather than a comment in a README.
     *
     * @param when   {@link When#ALWAYS}, or {@link When#BLANK} to fill in a slot
     *               only when its answer came back empty
     * @param how    the transform applied to {@code from} once it is substituted
     * @param from   the text, which may name slots and earlier fills
     */
    public record Fill(String name, When when, Transform how, String from) {

        public enum When { ALWAYS, BLANK }
    }

    /**
     * One file the template writes, when every one of its {@code conditions} holds and its module, if it has one,
     * was chosen: an example's test is written {@code in later when tests}, only if both hold.
     *
     * @param module the module it belongs to, or null for the base every project gets
     */
    public record Item(String path, String source, String module, List<Condition> conditions) {

        public Item {
            conditions = List.copyOf(conditions);
        }

        /** True when this file is written only if a flag says so. */
        public boolean conditional() { return !conditions.isEmpty(); }
    }

    /**
     * A feature somebody may take or leave: a set of whole files, and what it adds to the files it shares.
     *
     * <p>Whole files, and nothing inside one that depends on it. A file with a section for each module would be a
     * different program for every combination of them, and the combinations are what a template that grows rots by;
     * with whole files a module can only break the base or itself, which is what lets a few builds stand for all of
     * them ({@code docs/plans/template-modules.md}). Where a module must say something in a file it shares, it
     * brings a {@link Part}.
     *
     * @param requires the modules it does not work without, taken with it when it is chosen
     */
    public record Module(String name, String about, List<String> requires) {

        public Module {
            requires = List.copyOf(requires);
        }
    }

    /**
     * What a module adds to a file it shares: the text of {@code source}, put in place of the line
     * {@code <!-- part: marker -->} in {@code file}, after every other part for that marker, in the order the
     * manifest lists them. A marker no chosen module fills is removed. That is the whole of it: a place in a file,
     * named, and filled from whole files, so nothing in a template's text is an expression or a condition.
     *
     * @param file the path of the {@code file} line it adds to, as the manifest writes it
     */
    public record Part(String file, String marker, String source, String module, List<Condition> conditions) {

        public Part {
            conditions = List.copyOf(conditions);
        }

        /** The line in {@code file} this goes in place of. */
        public String line() { return marker(marker); }

        /** The line a marker is written as. */
        public static String marker(String name) { return "<!-- part: " + name + " -->"; }
    }

    /**
     * Which modules an answer takes.
     *
     * @param names    the modules taken, in the manifest's order, each with what it requires
     * @param notes    what was taken that the answer did not name, and why
     * @param problems what is wrong with the answer; nothing is written while there are any
     */
    public record Choice(List<String> names, List<String> notes, List<String> problems) {

        public boolean has(String module) { return module == null || names.contains(module); }
    }

    /** A flag a file waits on: written when it is yes, or, {@code unless}, when it is no. */
    public record Condition(String flag, boolean unless) {

        public boolean holds(boolean on) { return on != unless; }
    }

    /**
     * A Maven artifact the generated project will not build without.
     *
     * <p>Declared so the answer to "will this actually run" is looked up rather
     * than hoped for. See {@link Checks#missingArtifacts}.
     */
    public record Need(String group, String artifact, String version) {

        public String coordinate() { return group + ":" + artifact + ":" + version; }

        /** Where this would be in a local Maven repository. */
        public String repoPath() {
            return group.replace('.', '/') + "/" + artifact + "/" + version;
        }

        /** The jar's name inside that folder. */
        public String jarName() { return artifact + "-" + version + ".jar"; }
    }

    public Slot slot(String name) {
        for (Slot slot : slots) if (slot.name().equals(name)) return slot;
        return null;
    }

    public Module module(String name) {
        for (Module module : modules) if (module.name().equals(name)) return module;
        return null;
    }

    /**
     * The modules {@code answer} takes: {@code all}, {@code none} (or nothing), or names, separated by spaces or
     * commas. A module comes with the ones it requires, and is said to.
     */
    public Choice choose(String answer) {
        List<String> words = new ArrayList<>();
        if (answer != null) {
            for (String word : answer.strip().split("[\\s,]+")) if (!word.isEmpty()) words.add(word);
        }
        List<String> problems = new ArrayList<>();
        if (words.contains("all") || words.contains("none")) {
            if (words.size() > 1) {
                problems.add(MODULES + " is all, none, or names of modules, not a mix: " + String.join(" ", words));
                return new Choice(List.of(), List.of(), List.copyOf(problems));
            }
            List<String> names = new ArrayList<>();
            if (words.contains("all")) for (Module module : modules) names.add(module.name());
            return new Choice(List.copyOf(names), List.of(), List.of());
        }
        List<String> names = new ArrayList<>();
        for (String word : words) {
            if (module(word) == null) {
                String closest = Answers.closest(word, moduleNames());
                problems.add("there is no " + word + " module"
                        + (closest == null ? "" : " (did you mean " + closest + "?)")
                        + "; there is: " + String.join(", ", moduleNames()));
            } else if (!names.contains(word)) {
                names.add(word);
            }
        }
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            for (String required : module(names.get(i)).requires()) {
                if (names.contains(required)) continue;
                names.add(required);
                notes.add(names.get(i) + " requires " + required + ", so it comes too");
            }
        }
        List<String> ordered = new ArrayList<>();
        for (Module module : modules) if (names.contains(module.name())) ordered.add(module.name());
        return new Choice(List.copyOf(ordered), List.copyOf(notes), List.copyOf(problems));
    }

    /**
     * The answers that stand for every combination of modules: none, each module alone (with what it requires), and
     * all, each once. With whole files, and no module naming one it does not require, a module can break only the base
     * or itself, so a template that builds each of these builds every combination: n + 2 builds, not 2<sup>n</sup>
     * ({@code docs/plans/template-modules.md}, rule 4). A module whose requirements take every module is all.
     */
    public List<String> matrix() {
        List<String> answers = new ArrayList<>();
        answers.add("none");
        List<String> every = moduleNames();
        for (String module : every) if (!choose(module).names().equals(every)) answers.add(module);
        answers.add("all");
        return List.copyOf(answers);
    }

    /** The modules' names, in the manifest's order. */
    public List<String> moduleNames() {
        List<String> names = new ArrayList<>(modules.size());
        for (Module module : modules) names.add(module.name());
        return List.copyOf(names);
    }

    /** Every placeholder a caller is expected to supply or have filled in. */
    public List<String> placeholders() {
        List<String> names = new ArrayList<>(slots.size() + fills.size());
        for (Slot slot : slots) names.add(slot.name());
        for (Fill fill : fills) if (!names.contains(fill.name())) names.add(fill.name());
        return List.copyOf(names);
    }
}

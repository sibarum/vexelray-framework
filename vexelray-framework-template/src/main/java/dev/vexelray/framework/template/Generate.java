package dev.vexelray.framework.template;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@code vexel-desktop} project from the command line, with nothing installed but this module.
 *
 * <p>The same calls the terminal adapter and the acceptance test make -- {@link Catalogue}, {@link Answers},
 * {@link Checks}, {@link Scaffold}, {@link Blueprint.Writing} -- so this is one more way to run the builder,
 * not a second builder. It exists because the README had no route to a project that did not go through
 * another repository.
 *
 * <pre>
 * java -cp vexelray-framework-template.jar dev.vexelray.framework.template.Generate \
 *      --in C:/src --name my-app --group dev.example
 * </pre>
 *
 * <p>Any other slot of the template can be given as {@code --<slot> value}; {@code --help} lists them.
 */
public final class Generate {

    private static final String TEMPLATE = "vexel-desktop";

    /** Flags that name a slot differently from the slot itself. */
    private static final Map<String, String> ALIASES = Map.of("in", "where", "name", "artifactId", "group", "groupId");

    private Generate() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** The whole of {@link #main}, returning the exit code, so it can be tested without ending the JVM. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Catalogue catalogue = Catalogue.bundled();
            Template template = catalogue.get(TEMPLATE);
            if (args.length == 0 || List.of(args).contains("--help")) {
                usage(template, out);
                return args.length == 0 ? 2 : 0;
            }

            Map<String, String> given = new LinkedHashMap<>(Answers.presets(template).values());
            for (int i = 0; i < args.length; i += 2) {
                String flag = args[i];
                if (!flag.startsWith("--") || i + 1 >= args.length) {
                    err.println("expected --<slot> <value>, got: " + flag);
                    return 2;
                }
                String slot = flag.substring(2);
                given.put(ALIASES.getOrDefault(slot, slot), args[i + 1]);
            }
            if (!given.containsKey("where") || given.get("where").isBlank()) {
                err.println("--in <folder> is required: the folder the project folder goes inside");
                return 2;
            }

            Answers answers = Answers.of(given).filled(template);
            List<String> missing = answers.missing(template);
            if (!missing.isEmpty()) {
                err.println("missing: " + String.join(", ", missing));
                return 2;
            }
            List<String> problems = Checks.problems(template, answers);
            if (!problems.isEmpty()) {
                problems.forEach(p -> err.println("problem: " + p));
                return 1;
            }

            Blueprint blueprint = Scaffold.of(template, answers, catalogue);
            Path dir = Scaffold.folder(template, answers, Path.of(answers.get("where")));
            Blueprint.Writing writing = new Blueprint.Writing(dir);
            try {
                writing.begin();
                for (Blueprint.Entry entry : blueprint.entries()) {
                    writing.write(entry);
                }
            } catch (IOException | RuntimeException e) {
                writing.undo();
                throw e;
            }
            out.println("wrote " + blueprint.size() + " files to " + dir);
            blueprint.notes().forEach(n -> out.println("note: " + n));
            out.println("next: cd " + dir.getFileName() + " && mvn compile exec:exec");
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println(e.getMessage() == null ? e.toString() : e.getMessage());
            return 1;
        }
    }

    private static void usage(Template template, PrintStream out) {
        out.println("usage: Generate --in <folder> --name <project> --group <groupId> [--<slot> <value> ...]");
        out.println();
        out.println("  --in     the folder the project folder goes inside");
        out.println("  --name   the project name: a folder name and a Maven artifactId");
        out.println("  --group  the Maven groupId");
        out.println();
        out.println("other slots, with their defaults:");
        Map<String, String> presets = Answers.presets(template).values();
        template.slots().forEach(s -> {
            if (ALIASES.containsValue(s.name())) return;
            String preset = presets.get(s.name());
            out.println("  --" + s.name() + (preset == null ? "" : "  (" + preset + ")"));
        });
    }
}

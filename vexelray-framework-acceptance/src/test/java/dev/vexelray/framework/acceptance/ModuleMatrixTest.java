package dev.vexelray.framework.acceptance;

import dev.vexelray.framework.template.Answers;
import dev.vexelray.framework.template.Blueprint;
import dev.vexelray.framework.template.Catalogue;
import dev.vexelray.framework.template.Checks;
import dev.vexelray.framework.template.Scaffold;
import dev.vexelray.framework.template.Template;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dev.vexelray.framework.acceptance.Support.BUILD_MINUTES;
import static dev.vexelray.framework.acceptance.Support.fresh;
import static dev.vexelray.framework.acceptance.Support.maven;
import static dev.vexelray.framework.acceptance.Support.stop;
import static dev.vexelray.framework.acceptance.Support.tail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The template's modules, built: every set of them that stands for all the others, generated and built with its own
 * tests, as {@link GeneratedProjectTest} builds the default.
 *
 * <p>Which sets: the base alone, each module alone with what it requires, and all of them ({@code Template.matrix}).
 * A module is whole files and names only the base and what it requires ({@code VexelDesktopTest} holds that), so a
 * module can break the base or itself and nothing else, and these builds stand for every combination: n + 2 of them,
 * not 2<sup>n</sup> ({@code docs/plans/template-modules.md}). <i>All</i> is the default answer, which
 * {@link GeneratedProjectTest} builds and drives, so it is left out here rather than built twice.
 *
 * <p>Each build's time is written to {@code target/acceptance/modules/times.txt}: the plan's cost of keeping the
 * template modular is these builds, and it is measured rather than guessed.
 */
class ModuleMatrixTest {

    @Test
    void theBaseAndEachModuleAloneBuildWithTheirOwnTests() throws Exception {
        Path root = fresh("modules");
        Template template = Catalogue.bundled().get("vexel-desktop");
        List<String> times = new ArrayList<>();
        for (String modules : template.matrix()) {
            if (modules.equals("all")) continue;
            String artifact = "vexel-modules-" + modules;
            Map<String, String> given = new LinkedHashMap<>(Answers.presets(template).values());
            given.put("where", root.toString());
            given.put("artifactId", artifact);
            given.put("groupId", "dev.vexelray.acceptance");
            given.put(Template.MODULES, modules);
            Answers answers = Answers.of(given).filled(template);
            assertEquals(List.of(), Checks.problems(template, answers), modules);

            Blueprint blueprint = Scaffold.of(template, answers, Catalogue.bundled());
            Path project = Scaffold.folder(template, answers, root);
            Blueprint.Writing writing = new Blueprint.Writing(project);
            writing.begin();
            for (Blueprint.Entry entry : blueprint.entries()) writing.write(entry);

            Path log = root.resolve(modules + ".log");
            long start = System.nanoTime();
            Process build = maven(project, log, "package").start();
            if (!build.waitFor(BUILD_MINUTES, TimeUnit.MINUTES)) {
                stop(build);
                fail(modules + ": the build took longer than " + BUILD_MINUTES + " minutes\n" + tail(log));
            }
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            assertEquals(0, build.exitValue(), () -> "modules " + modules + " did not build\n" + tail(log));
            times.add(modules + "\t" + blueprint.size() + " files\t" + seconds + " s");
            Files.write(root.resolve("times.txt"), times);
        }
    }
}

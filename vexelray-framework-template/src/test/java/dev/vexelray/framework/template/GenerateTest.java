package dev.vexelray.framework.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command-line route to a project: the same tree the builder writes, reached with no other repository. */
class GenerateTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Generate.run(args, new PrintStream(out), new PrintStream(err));
    }

    @Test
    void itWritesAProjectFromThreeFlags(@TempDir Path temp) throws IOException {
        assertEquals(0, run("--in", temp.toString(), "--name", "demo-app", "--group", "dev.acme"), err.toString());
        Path project = temp.resolve("demo-app");
        assertTrue(Files.isRegularFile(project.resolve("pom.xml")));
        assertNotNull(find(project, "Recipes.java"), "the main package should hold Recipes.java");
        assertTrue(out.toString().contains("next: cd demo-app"), out.toString());
    }

    @Test
    void anyOtherSlotCanBeGivenByName(@TempDir Path temp) throws IOException {
        assertEquals(0, run("--in", temp.toString(), "--name", "demo-app", "--title", "Hello There"), err.toString());
        Path recipes = find(temp.resolve("demo-app"), "Recipes.java");
        assertNotNull(recipes);
        boolean named;
        try (Stream<Path> files = Files.walk(temp.resolve("demo-app/src/main/java"))) {
            named = files.filter(Files::isRegularFile).anyMatch(f -> read(f).contains("Hello There"));
        }
        assertTrue(named, "the window title should land in the source");
    }

    @Test
    void aProblemWritesNothingAndSaysWhy(@TempDir Path temp) throws IOException {
        assertEquals(1, run("--in", temp.toString(), "--name", "Not A Valid Name"));
        assertTrue(err.toString().contains("problem:"), err.toString());
        try (Stream<Path> entries = Files.list(temp)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void withoutAFolderItAsksForOne() {
        assertEquals(2, run("--name", "demo-app"));
        assertTrue(err.toString().contains("--in"), err.toString());
    }

    @Test
    void helpListsTheSlotsAndSucceeds() {
        assertEquals(0, run("--help"));
        assertTrue(out.toString().contains("--packageName"), out.toString());
    }

    private static Path find(Path project, String name) throws IOException {
        try (Stream<Path> walk = Files.walk(project)) {
            return walk.filter(p -> p.getFileName().toString().equals(name)).findFirst().orElse(null);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}

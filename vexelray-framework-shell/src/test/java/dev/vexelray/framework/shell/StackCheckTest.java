package dev.vexelray.framework.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the stack check finds, over a class path of jars-as-folders that each carry a stamp and a bill, the way
 * {@code vexelray-build} writes them.
 */
class StackCheckTest {

    @TempDir
    Path tmp;

    /** A jar, as a folder: its stamp, and its own bill naming what it was built against. */
    private Path jar(String name, String group, String artifact, String source, String... builtAgainst)
            throws IOException {
        Path root = tmp.resolve(name);
        Path stamp = root.resolve(StackCheck.STAMPS + group + "/" + artifact + ".properties");
        Files.createDirectories(stamp.getParent());
        Files.writeString(stamp, "group=" + group + "\nartifact=" + artifact + "\nsource=" + source + "\n");
        StringBuilder bill = new StringBuilder("group=" + group + "\nartifact=" + artifact + "\nsource=" + source + "\n"
                + "stack.count=" + builtAgainst.length + "\n");
        for (int i = 0; i < builtAgainst.length; i++) {
            bill.append("stack.").append(i + 1).append('=').append(builtAgainst[i]).append('\n');
        }
        Files.writeString(root.resolve(StackCheck.BILL), bill.toString());
        return root;
    }

    private static ClassLoader loader(Path... jars) throws IOException {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    private static BuildInfo app(String... stack) {
        return new BuildInfo("dev.app", "app", "1", "a1", List.of(stack), "", "", "", Map.of());
    }

    @Test
    void aStackBuiltTogetherSaysNothing() throws IOException {
        ClassLoader l = loader(
                jar("text", "dev.vexelray", "vexelray-text", "aaa"),
                jar("core", "dev.vexelray.gui", "vexelray-gui-core", "bbb", "dev.vexelray:vexelray-text:1@aaa"));
        List<StackCheck.Mismatch> found = StackCheck.find(
                app("dev.vexelray:vexelray-text:1@aaa", "dev.vexelray.gui:vexelray-gui-core:1@bbb"), l);
        assertEquals(List.of(), found);
    }

    /** An older vexelray-gui-core installed over the one the application compiled against. */
    @Test
    void aPartReplacedAfterTheApplicationCompiledIsNamed() throws IOException {
        ClassLoader l = loader(jar("core", "dev.vexelray.gui", "vexelray-gui-core", "old"));
        List<StackCheck.Mismatch> found = StackCheck.find(app("dev.vexelray.gui:vexelray-gui-core:1@new"), l);
        assertEquals(List.of(new StackCheck.Mismatch("dev.vexelray.gui:vexelray-gui-core", "new", "old", "dev.app:app")),
                found);
    }

    /** vexelray-gui-core built against a vexelray-text that is not the one installed: the afternoon's crash. */
    @Test
    void aPartBuiltAgainstAnotherVersionOfAPartIsNamed() throws IOException {
        ClassLoader l = loader(
                jar("text", "dev.vexelray", "vexelray-text", "stale"),
                jar("core", "dev.vexelray.gui", "vexelray-gui-core", "bbb", "dev.vexelray:vexelray-text:1@fresh"));
        List<StackCheck.Mismatch> found = StackCheck.find(
                app("dev.vexelray:vexelray-text:1@stale", "dev.vexelray.gui:vexelray-gui-core:1@bbb"), l);
        assertEquals(List.of(new StackCheck.Mismatch("dev.vexelray:vexelray-text", "fresh", "stale",
                "dev.vexelray.gui:vexelray-gui-core")), found);
        String said = StackCheck.describe(found);
        assertTrue(said.contains("vexelray-text is stale, but vexelray-gui-core was built against fresh"), said);
        assertTrue(said.contains("NoSuchMethodError") && said.contains("./bootstrap.sh --from"), said);
    }

    /** One repository reinstalled moves every module of it: said once, as the pair of commits. */
    @Test
    void aRepositoryReinstalledIsSaidOnceForAllItsParts() {
        List<StackCheck.Mismatch> found = List.of(
                new StackCheck.Mismatch("dev.vexelray:vexelray-core", "old", "new", "dev.vexelray.framework:shell"),
                new StackCheck.Mismatch("dev.vexelray:vexelray-canvas", "old", "new", "dev.vexelray.framework:shell"),
                new StackCheck.Mismatch("dev.vexelray:vexelray-text", "old", "new", "dev.vexelray.framework:automation"),
                new StackCheck.Mismatch("dev.vexelray:vexelray-ir", "old", "new", "dev.vexelray.framework:shell"));
        String said = StackCheck.describe(found);
        assertTrue(said.contains("vexelray-core, vexelray-canvas and 2 more are new, but shell and automation were built"
                + " against old."), said);
    }

    @Test
    void aBuildWithoutStampsHasNothingToCompare() throws IOException {
        Path bare = Files.createDirectories(tmp.resolve("bare"));
        assertEquals(List.of(), StackCheck.find(app("dev.vexelray:vexelray-text:1@aaa"), loader(bare)));
        assertEquals(List.of(), StackCheck.find(BuildInfo.UNKNOWN, loader(bare)));
    }
}

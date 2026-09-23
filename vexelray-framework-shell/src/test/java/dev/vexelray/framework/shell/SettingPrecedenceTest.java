package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code @Setting}'s precedence, as {@code Shell.setting} resolves it for generated wiring: a flag beats a property
 * beats the settings file beats the default, and a malformed value at any level falls through to the next rather
 * than failing the launch.
 */
class SettingPrecedenceTest {

    private static final String KEY = "precedence.size";

    @TempDir
    Path home;

    @AfterEach
    void clearTheProperty() {
        System.clearProperty(KEY);
    }

    private Shell shell(String... argv) {
        Shell shell = new Shell(Launch.parse(argv, "demo", Set.of(KEY)), new AppInfo("demo", "Demo", 10, 10));
        shell.phase(Phase.CONFIG);
        Settings settings = Settings.at(home.resolve("settings.properties"));
        settings.putInt(KEY, 3);
        shell.settings(settings);
        return shell;
    }

    @Test
    void theFlagWinsOverEverything() {
        System.setProperty(KEY, "2");
        assertEquals(1, shell("--" + KEY + "=1").setting(KEY, 9));
    }

    @Test
    void thePropertyWinsOverTheFile() {
        System.setProperty(KEY, "2");
        assertEquals(2, shell().setting(KEY, 9));
    }

    @Test
    void theFileWinsOverTheDefault() {
        assertEquals(3, shell().setting(KEY, 9));
    }

    @Test
    void theDefaultIsTheLastWord() {
        Shell shell = shell();
        shell.settings().remove(KEY);
        assertEquals(9, shell.setting(KEY, 9));
    }

    @Test
    void aMalformedFlagFallsThroughRatherThanFailing() {
        System.setProperty(KEY, "2");
        assertEquals(2, shell("--" + KEY + "=many").setting(KEY, 9));
    }

    @Test
    void aBooleanIsTrueOrFalseAndAYesFallsThrough() {
        Shell shell = shell("--" + KEY + "=yes");
        shell.settings().remove(KEY);
        assertEquals(true, shell.setting(KEY, true), "yes falls through to the default rather than reading false");
        assertFalse(shell("--" + KEY + "=false").setting(KEY, true));
    }

    @Test
    void aListFromTheCommandLineIsSplitOnCommas() {
        assertEquals(List.of("a", "b"), shell("--" + KEY + "=a,b").settingList(KEY));
    }
}

package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The light/dark choice: offered only for two looks, remembered, read back before the first tree, and shown in the app
 * menu as a radio pair. No window and no GPU: a {@code Gui} with no device is what a choice acts on.
 */
final class ThemeChoiceTest {

    /** Two looks of the application's own, not the stack's, so nothing here passes by matching a default. */
    private static final Theme MINE_LIGHT = Theme.of(Palette.LIGHT, Shading.ON_LIGHT, Relief.STANDARD, false, false);
    private static final Theme MINE_DARK = Theme.of(Palette.DARK, Shading.ON_DARK, Relief.STANDARD, true, false);

    @TempDir
    Path home;

    private Shell shell(Appearance appearance) {
        Shell shell = new Shell(Launch.parse(new String[0], "demo", Set.of()),
                new AppInfo("demo", "Demo", 800, 600));
        shell.phase(Phase.CONFIG);
        shell.settings(Settings.at(home.resolve("settings.properties")));
        shell.appearance(appearance);
        shell.phase(Phase.GUI);
        return shell;
    }

    private static Gui gui() {
        return new Gui(Atchung.create());
    }

    // ---- the value -------------------------------------------------------------------------------------------

    @Test
    void oneLookOffersNoChoice() {
        assertFalse(Appearance.DEFAULT.choosesTheme());
        assertFalse(Appearance.of(MINE_LIGHT).choosesTheme());
    }

    @Test
    void aPairOpensDarkUnlessTheLookInForceIsItsLightOne() {
        assertSame(MINE_DARK, Appearance.DEFAULT.themes(MINE_LIGHT, MINE_DARK).theme());
        assertSame(MINE_LIGHT, Appearance.of(MINE_LIGHT).themes(MINE_LIGHT, MINE_DARK).theme());
        assertTrue(Appearance.of(MINE_LIGHT).themes(MINE_LIGHT, MINE_DARK).isLight());
    }

    /** The framework derives neither half, so half a pair is a startup error, not a menu with one line. */
    @Test
    void halfAPairIsRefusedNamingTheHalfGiven() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Appearance.DEFAULT.themes(MINE_LIGHT, null));
        assertTrue(e.getMessage().contains("only the light one"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Appearance.DEFAULT.themes(MINE_DARK, MINE_DARK));
    }

    @Test
    void theWithersKeepThePair() {
        Appearance a = Appearance.DEFAULT.themes(MINE_LIGHT, MINE_DARK).instruments(List.of()).zoom(0.5f, 2f, 1.1f);
        assertSame(MINE_LIGHT, a.light());
        assertSame(MINE_DARK, a.dark());
    }

    // ---- the choice ------------------------------------------------------------------------------------------

    @Test
    void anApplicationWithOneLookHasNoChoice() {
        assertNull(ThemeChoice.open(shell(Appearance.DEFAULT)));
    }

    @Test
    void aChoiceRethemesEveryFollowingTreeAndTheAppearanceLaterWindowsTake() {
        Shell shell = shell(Appearance.DEFAULT.themes(MINE_LIGHT, MINE_DARK));
        ThemeChoice choice = ThemeChoice.open(shell);
        Gui main = gui();
        shell.appearance().applyTo(main);
        choice.follow(main);
        Gui own = gui().theme(Theme.DARK);   // a window with a look of its own: never followed

        choice.choose(true);

        assertSame(MINE_LIGHT, main.theme());
        assertSame(Theme.DARK, own.theme());
        assertSame(MINE_LIGHT, shell.appearance().theme());
        Gui later = gui();
        shell.appearance().applyTo(later);
        assertSame(MINE_LIGHT, later.theme());
    }

    @Test
    void theChoiceIsRememberedAndReadBackBeforeTheFirstTree() {
        Shell first = shell(Appearance.DEFAULT.themes(MINE_LIGHT, MINE_DARK));
        ThemeChoice.open(first).choose(true);

        Shell next = shell(Appearance.DEFAULT.themes(MINE_LIGHT, MINE_DARK));
        ThemeChoice.open(next);
        assertSame(MINE_LIGHT, next.appearance().theme());
    }

    /**
     * The application's control over a shift: the look in force is said at start, and again on each choice, after
     * every tree was asked for it. A choice of the look already in force says nothing.
     */
    @Test
    void theLookInForceIsSaidAtStartAndOnEveryShift() {
        Shell shell = shell(Appearance.DEFAULT.themes(MINE_LIGHT, MINE_DARK));
        List<Boolean> said = new ArrayList<>();
        shell.bus().subscribe(dev.vexelray.framework.core.ThemeShifted.TOPIC, m -> said.add(m.light()));
        ThemeChoice choice = ThemeChoice.open(shell);
        choice.choose(true);
        choice.choose(true);
        choice.choose(false);
        assertEquals(List.of(false, true, false), said);
    }

    @Test
    void anUnknownRememberedWordOpensInTheApplicationsOwnLook() {
        Settings.at(home.resolve("settings.properties")).putString(ThemeChoice.KEY, "sepia").save();
        Shell shell = shell(Appearance.DEFAULT.themes(MINE_LIGHT, MINE_DARK));
        ThemeChoice.open(shell);
        assertSame(MINE_DARK, shell.appearance().theme());
    }

    // ---- the menu --------------------------------------------------------------------------------------------

    /** Lines in the app menu as the other settings in menus are: a radio pair, the dot on the look in force. */
    @Test
    void theSectionIsARadioPairUnderAHeadingAmongTheApplicationsLines() {
        ThemeChoice choice = ThemeChoice.open(shell(Appearance.DEFAULT.themes(MINE_LIGHT, MINE_DARK)));
        WindowInstrument tool = choice.instrument();
        assertEquals(WindowInstrument.Place.APPLICATION, tool.place());
        assertFalse(tool.badged());

        List<MenuItem> lines = lines(tool);
        assertEquals(List.of("Theme", "Light", "Dark"),
                lines.stream().filter(l -> !l.separator()).map(MenuItem::label).toList());
        MenuItem light = lines.get(lines.size() - 2);
        MenuItem dark = lines.getLast();
        assertTrue(light.choice().radio() && dark.choice().radio());
        assertEquals(light.choice().group(), dark.choice().group());
        assertFalse(light.choice().on());
        assertTrue(dark.choice().on());

        light.action().run();   // as the menu runs a chosen line

        List<MenuItem> after = lines(tool);
        assertTrue(after.get(after.size() - 2).choice().on());
        assertFalse(after.getLast().choice().on());
    }

    private static List<MenuItem> lines(WindowInstrument tool) {
        List<MenuItem> lines = new ArrayList<>();
        tool.section(null, new MenuSink() {
            @Override
            public ClickEvent event() {
                return null;
            }

            @Override
            public MenuSink item(MenuItem item) {
                lines.add(item);
                return this;
            }

            @Override
            public MenuSink separator() {
                lines.add(MenuItem.SEPARATOR);
                return this;
            }
        });
        return lines;
    }
}

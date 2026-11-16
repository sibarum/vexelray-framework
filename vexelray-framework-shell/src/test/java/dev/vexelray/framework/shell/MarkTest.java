package dev.vexelray.framework.shell;

import dev.vexelray.os.Icon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Which mark an application wears: named, or the framework's, and never none because one failed. */
class MarkTest {

    private static final AppInfo BARE = new AppInfo("demo", "Demo", 800, 600);

    private static Wiring naming(String icon) {
        return new Wiring() {
            @Override
            public AppInfo info() {
                return BARE;
            }

            @Override
            public String icon() {
                return icon;
            }
        };
    }

    @Test
    void theFrameworksMarkDecodesAtEverySizeItWasDrawnAt() {
        Icon mark = Mark.load(Mark.class, Mark.FRAMEWORK, "the framework's icon");
        assertNotNull(mark, "new-app.ico is beside Mark and decodes without AWT");
        assertEquals(List.of(16, 20, 24, 32, 40, 48, 64, 96, 128, 256),
                mark.images().stream().map(Icon.Image::width).toList(),
                "every size tools/Ico.java renders, each from the vector");
    }

    @Test
    void anApplicationNamingNoneWearsTheFrameworks() {
        Icon mark = Mark.of(naming(null));
        assertNotNull(mark);
        assertEquals(10, mark.images().size());
    }

    @Test
    void aNamedMarkIsFoundBesideTheWiring() {
        // The wiring is in this package, so the framework's file is found by its bare name, as an application's is.
        assertEquals(10, Mark.of(naming(Mark.FRAMEWORK)).images().size());
    }

    @Test
    void aMarkThatIsMissingOrDoesNotDecodeFallsToTheFrameworks() {
        assertEquals(10, Mark.of(naming("not-there.ico")).images().size(), "missing");
        assertEquals(10, Mark.of(naming("MarkTest.class")).images().size(), "there, and not an .ico");
    }

    /** The suite canvas exports {@code pix-window.svg} beside {@code pix.svg}; the icons keep the same pairing. */
    @Test
    void theWindowVariantIsNamedBesideTheMark() {
        assertEquals("/pix-window.ico", Mark.windowVariant("/pix.ico"));
        assertEquals("icons/a.b-window.png", Mark.windowVariant("icons/a.b.png"));
        assertNull(Mark.windowVariant("/.ico"), "no name to put the suffix on");
        assertNull(Mark.windowVariant("icon"), "no extension");
    }

    @Test
    void otherWindowsWearTheWindowVariantWhenThereIsOne() {
        // suite-mark-window.ico is a test resource beside this class; suite-mark.ico itself need not be.
        Icon primary = Icon.of(1, 1, new int[] {0xFF00FF00});
        Icon window = Mark.window(naming("suite-mark.ico"), primary);
        assertNotSame(primary, window);
        assertEquals(10, window.images().size());
    }

    @Test
    void otherWindowsWearTheMarkItselfWithoutAVariant() {
        Icon primary = Icon.of(1, 1, new int[] {0xFF00FF00});
        assertSame(primary, Mark.window(naming(Mark.FRAMEWORK), primary), "no new-app-window.ico");
        assertSame(primary, Mark.window(naming(null), primary), "named none");
    }

    @Test
    void theBackgroundVariantIsNamedBesideTheMark() {
        assertEquals("/pix-background.ico", Mark.variant("/pix.ico", "-background"));
        assertNull(Mark.variant("icon", "-background"), "no extension");
    }

    @Test
    void aHiddenApplicationWearsItsBackgroundVariant() {
        // suite-mark-background.ico is a test resource beside this class, and new-app-background.ico the framework's.
        assertEquals(10, Mark.background(naming("suite-mark.ico")).images().size());
        assertEquals(10, Mark.background(naming(null)).images().size(), "named none: the framework's");
    }

    @Test
    void withoutABackgroundVariantAHiddenWindowKeepsItsMark() {
        assertNull(Mark.background(naming("not-there.ico")), "no variant: none, and not reported");
    }
}

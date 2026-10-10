package dev.vexelray.framework.shell;

import dev.vexelray.os.Icon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Which mark an application wears: handed back, named, or the framework's, and never none because one failed. */
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
        Icon mark = Mark.of(naming(null), BARE);
        assertNotNull(mark);
        assertEquals(10, mark.images().size());
    }

    @Test
    void aNamedMarkIsFoundBesideTheWiring() {
        // The wiring is in this package, so the framework's file is found by its bare name, as an application's is.
        assertEquals(10, Mark.of(naming(Mark.FRAMEWORK), BARE).images().size());
    }

    @Test
    void aMarkThatIsMissingOrDoesNotDecodeFallsToTheFrameworks() {
        assertEquals(10, Mark.of(naming("not-there.ico"), BARE).images().size(), "missing");
        assertEquals(10, Mark.of(naming("MarkTest.class"), BARE).images().size(), "there, and not an .ico");
    }

    @Test
    void aMarkHandedBackInCodeWins() {
        Icon own = Icon.of(1, 1, new int[] {0xFF00FF00});
        assertSame(own, Mark.of(naming(Mark.FRAMEWORK), BARE.withIcon(own)));
    }
}

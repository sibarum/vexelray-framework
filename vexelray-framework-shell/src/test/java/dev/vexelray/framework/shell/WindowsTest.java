package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowOpened;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.os.Decorations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A second window, declared by name: its tree is the framework's to build, and its window the framework's to claim.
 *
 * <p><b>No GPU.</b> Everything asserted here is true by the end of {@code TREE}, which is the point of declaring a
 * window with the trees: what the window will show is built, themed and readable before there is a device, and the
 * claim at {@code ATTACH} adds only what needs one. The claim itself runs in the acceptance witness, which opens the
 * windows for real.
 */
final class WindowsTest {

    private static final AppInfo DEMO = new AppInfo("windowstest", "Windows", 800, 600);

    private Shell shell;

    @BeforeEach
    void ownHome(@TempDir Path home) {
        System.setProperty(DEMO.name() + ".home", home.toString());
    }

    @AfterEach
    void close() {
        if (shell != null) {
            shell.disposer().close();
        }
    }

    /** A wiring that hands back {@code look} in CONFIG and runs {@code tree} in TREE. */
    private static Wiring wiring(Appearance look, Consumer<Shell> tree) {
        return new Wiring() {
            @Override
            public AppInfo info() {
                return DEMO;
            }

            @Override
            public void config(Shell shell) {
                shell.appearance(look);
            }

            @Override
            public void tree(Shell shell) {
                tree.accept(shell);
            }
        };
    }

    private Window declare(Appearance look, NamedWindow window) {
        Window[] out = new Window[1];
        shell = VexelApplication.tree(wiring(look, s -> out[0] = s.windows().named(window)), new String[0]);
        return out[0];
    }

    @Test
    void everyWindowsTreeIsOnTheApplicationsBusNamedByItsKey() {
        Window w = declare(Appearance.DEFAULT, NamedWindow.of("tool", "Tool", 300, 200));

        // One bus, so a view hears every window's widgets and the application's messages alike; each tree named by
        // its window, so its widgets' addresses are its own and its own topics are apart from the others' (T4.6).
        assertSame(shell.bus(), w.gui().bus(), "a second window's tree is on a bus of its own");
        assertSame(shell.bus(), shell.windows().main().gui().bus());
        assertEquals("tool.ok", w.gui().address("ok"));
        assertEquals("main.ok", shell.windows().main().gui().address("ok"));
    }

    @Test
    void aClosedWindowsTreeHoldsNothingOfAnotherWindowsWidgets() {
        Fatal previous = Atchung.fatal();
        Atchung.onFatal(Fatal.THROW);
        try {
            Window w = declare(Appearance.DEFAULT, NamedWindow.of("tool", "Tool", 300, 200));
            Button ok = new Button(w.gui(), "OK").name("ok");
            int[] heard = {0};
            w.gui().mailbox(Button.PRESSED, ok.address(), p -> heard[0]++);

            // The main window's presses, far more than a widget topic holds between two frames, while the tool
            // window is closed and runs none. Before the widgets' topics were scoped by tree, the tool tree was
            // offered every one, and its next frame failed the application.
            for (int i = 0; i < 5_000; i++) {
                shell.bus().publish(Button.PRESSED, new Button.Pressed("main.go"));
            }
            shell.bus().publish(Button.PRESSED, new Button.Pressed(ok.address()));
            Seams.of(w.gui()).frame(300, 200, TextFaces.standard());
            assertEquals(1, heard[0], "the tool window heard its own press, and only that");
        } finally {
            Atchung.onFatal(previous);
        }
    }

    @Test
    void itWearsTheApplicationsLookUnlessItWasDeclaredWithItsOwn() {
        Appearance light = Appearance.of(Theme.LIGHT).zoom(0.75f, 2f, 1.1f);
        Window plain = declare(light, NamedWindow.of("plain", "Plain", 300, 200));
        assertSame(Theme.LIGHT, plain.gui().theme(), "a second window did not get the application's look");
        close();

        Window own = declare(light, NamedWindow.of("own", "Own", 300, 200).theme(Theme.DARK));
        assertSame(Theme.DARK, own.gui().theme(), "a window declared with a look of its own did not keep it");
    }

    @Test
    void itHasAFrameworkTitleBarUnlessTheApplicationAskedForTheOsFrame() {
        Window drawn = declare(Appearance.DEFAULT, NamedWindow.of("drawn", "Drawn", 300, 200));
        assertNotSame(shell.titleBar(), drawn.titleBar(), "a second window was handed the main window's bar");
        close();

        Window system = declare(Appearance.DEFAULT.decorations(Decorations.SYSTEM),
                NamedWindow.of("system", "System", 300, 200));
        IllegalStateException e = assertThrows(IllegalStateException.class, system::titleBar);
        assertTrue(e.getMessage().contains("SYSTEM"), e.getMessage());
    }

    @Test
    void itRunsOnTheApplicationsClock() {
        Window w = declare(Appearance.DEFAULT, NamedWindow.of("tool", "Tool", 300, 200));
        // Without a clock a Tween lands at once: the About window's intro was one frame at its end.
        assertNotNull(Seams.of(shell.gui()).clock());
        assertSame(Seams.of(shell.gui()).clock(), Seams.of(w.gui()).clock(),
                "a second window's tree has no clock, so every Tween and Timing in it lands at once");
    }

    @Test
    void aCommandIsAMessageCarriedOutInTheFrameAndHeldUntilTheWindowExists() {
        Window w = declare(Appearance.DEFAULT, NamedWindow.of("early", "Early", 300, 200));
        List<Object> said = new ArrayList<>();
        shell.bus().subscribe(WindowOpened.TOPIC, said::add);
        assertFalse(w.shown(), "a named window opens when asked, not with the application");

        // Sent from anywhere; carried out on the main thread, in the main window's frame. Before ATTACH there is no
        // window to open, so the command is remembered for the claim, and nothing is said to have opened.
        shell.bus().publish(WindowCommand.TOPIC, WindowCommand.show("early"));
        assertFalse(w.shown(), "a command was carried out outside the frame");
        VexelApplication.frame(shell);
        assertTrue(w.shown());
        shell.bus().publish(WindowCommand.TOPIC, WindowCommand.toggle("early"));
        shell.bus().publish(WindowCommand.TOPIC, WindowCommand.show("nobody"));
        VexelApplication.frame(shell);
        assertFalse(w.shown(), "a toggle before the claim flips what the claim will do");
        assertTrue(said.isEmpty(), "a window was said to open before there was a device to open it on");
    }

    @Test
    void fullScreenBeforeTheWindowExistsIsWhatTheClaimDoes() {
        Window w = declare(Appearance.DEFAULT, NamedWindow.of("screen", "Screen", 300, 200));
        assertNull(w.fullScreen(), "with nothing asked the window opens as it was remembered");
        shell.bus().publish(WindowCommand.TOPIC, WindowCommand.toggleFullScreen("screen"));
        VexelApplication.frame(shell);
        assertEquals(Boolean.TRUE, w.fullScreen(), "a toggle from nothing asked is full screen");
        shell.bus().publish(WindowCommand.TOPIC, WindowCommand.toggleFullScreen("screen"));
        VexelApplication.frame(shell);
        assertEquals(Boolean.FALSE, w.fullScreen());
        shell.bus().publish(WindowCommand.TOPIC, WindowCommand.fullScreen("screen"));
        shell.bus().publish(WindowCommand.TOPIC, WindowCommand.windowed("screen"));
        VexelApplication.frame(shell);
        assertEquals(Boolean.FALSE, w.fullScreen(), "the last command is the one the claim carries out");
    }

    @Test
    void theMainWindowIsAWindowOverTheFrameworksOwnTreeAndBar() {
        shell = VexelApplication.tree(wiring(Appearance.DEFAULT, s -> { }), new String[0]);
        Window main = shell.windows().main();

        assertEquals("main", main.key());
        assertSame(shell.gui(), main.gui(), "the main window's handle is not over the framework's tree");
        assertSame(shell.titleBar(), main.titleBar(), "the main window's handle has a bar of its own");
        // The main window opens with the application, under the key a command names it by.
        assertTrue(main.shown());
        assertEquals(dev.vexelray.framework.core.CloseRequested.MAIN, main.key());
        shell.bus().publish(WindowCommand.TOPIC, WindowCommand.hide("main"));
        VexelApplication.frame(shell);
        assertFalse(main.shown(), "a hide before the claim is what the claim does");
        assertTrue(shell.windows().views().isEmpty(), "views before ATTACH should be empty");
    }

    @Test
    void aCloseGoesStraightThroughWhenNothingHearsTheQuestion() {
        Window w = declare(Appearance.DEFAULT, NamedWindow.of("plain", "Plain", 300, 200));
        List<CloseRequested> asked = new ArrayList<>();
        shell.bus().subscribe(CloseRequested.TOPIC, asked::add);
        List<String> heard = new ArrayList<>();

        // The default is that closing closes: an application with nothing to lose need not say so.
        w.requested(() -> heard.add("proceeded"), () -> heard.add("cancelled"));
        assertEquals(List.of("proceeded"), heard);
        assertTrue(asked.isEmpty(), "a close was asked of an application that hears no CloseRequested");
    }

    @Test
    void aCloseIsAskedAndItsAnswerDecidesIt() {
        shell = VexelApplication.tree(answering(s -> { }), new String[0]);
        Window main = shell.windows().main();
        List<CloseRequested> asked = new ArrayList<>();
        shell.bus().subscribe(CloseRequested.TOPIC, asked::add);
        List<String> heard = new ArrayList<>();

        main.requested(() -> heard.add("proceeded"), () -> heard.add("cancelled"));
        assertEquals(1, asked.size());
        assertEquals("main", asked.get(0).window());
        assertTrue(heard.isEmpty(), "the close was decided before it was answered");

        // A second press while the question is up is the same request: refused, and nothing asked again.
        main.requested(() -> heard.add("proceeded again"), () -> heard.add("pressed again"));
        assertEquals(List.of("pressed again"), heard);
        assertEquals(1, asked.size());

        // Answered from any lane, heard in the main window's frame.
        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(0).ticket(), false));
        assertEquals(List.of("pressed again"), heard, "an answer was acted on outside the frame");
        VexelApplication.frame(shell);
        assertEquals(List.of("pressed again", "cancelled"), heard);

        // The window is asked afresh, under a new ticket, and an answer to the old one changes nothing.
        main.requested(() -> heard.add("proceeded"), () -> heard.add("cancelled"));
        assertEquals(2, asked.size());
        assertNotEquals(asked.get(0).ticket(), asked.get(1).ticket());
        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(0).ticket(), true));
        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(1).ticket(), true));
        VexelApplication.frame(shell);
        assertEquals(List.of("pressed again", "cancelled", "proceeded"), heard);
    }

    /**
     * regions.md ruling 3: each part that hears the question is asked in turn, by a request addressed to it under a
     * ticket of its own. A yes asks the next, the last yes closes the window, and the first no keeps it open and asks
     * no one after.
     */
    @Test
    void eachHearerIsAskedInTurnAndTheFirstNoEndsTheRound() {
        shell = VexelApplication.tree(answering(List.of("a.First", "b.Second"), s -> { }), new String[0]);
        Window main = shell.windows().main();
        List<CloseRequested> asked = new ArrayList<>();
        shell.bus().subscribe(CloseRequested.TOPIC, asked::add);
        List<String> heard = new ArrayList<>();

        main.requested(() -> heard.add("proceeded"), () -> heard.add("cancelled"));
        assertEquals(List.of("a.First"), asked.stream().map(CloseRequested::to).toList(), "one asked at a time");
        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(0).ticket(), true));
        VexelApplication.frame(shell);
        assertEquals(List.of("a.First", "b.Second"), asked.stream().map(CloseRequested::to).toList());
        assertNotEquals(asked.get(0).ticket(), asked.get(1).ticket(), "each turn has a ticket of its own");
        assertTrue(heard.isEmpty(), "closed before the last part said yes");
        // A second answer to the first turn's ticket is not the second part's.
        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(0).ticket(), true));
        VexelApplication.frame(shell);
        assertTrue(heard.isEmpty(), "a late answer to an earlier turn decided the close");
        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(1).ticket(), true));
        VexelApplication.frame(shell);
        assertEquals(List.of("proceeded"), heard);

        // The first no keeps the window open, and no one after it is asked.
        asked.clear();
        main.requested(() -> heard.add("proceeded"), () -> heard.add("cancelled"));
        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(0).ticket(), false));
        VexelApplication.frame(shell);
        assertEquals(List.of("proceeded", "cancelled"), heard);
        assertEquals(List.of("a.First"), asked.stream().map(CloseRequested::to).toList());

        // And the window can be asked again: the refusal ended the round.
        asked.clear();
        main.requested(() -> heard.add("proceeded"), () -> heard.add("cancelled"));
        assertEquals(List.of("a.First"), asked.stream().map(CloseRequested::to).toList());
    }

    @Test
    void eachWindowsCloseIsAQuestionOfItsOwn() {
        shell = VexelApplication.tree(answering(s -> s.windows().named("tool", "Tool", 300, 200)), new String[0]);
        Window tool = shell.windows().named("other", "Other", 300, 200);
        List<CloseRequested> asked = new ArrayList<>();
        shell.bus().subscribe(CloseRequested.TOPIC, asked::add);
        List<String> heard = new ArrayList<>();

        shell.windows().main().requested(() -> heard.add("main closed"), () -> heard.add("main kept"));
        tool.requested(() -> heard.add("other closed"), () -> heard.add("other kept"));
        assertEquals(List.of("main", "other"), asked.stream().map(CloseRequested::window).toList());

        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(1).ticket(), true));
        shell.bus().publish(CloseAnswer.TOPIC, new CloseAnswer(asked.get(0).ticket(), false));
        VexelApplication.frame(shell);
        assertEquals(List.of("other closed", "main kept"), heard);
    }

    /** {@link #wiring}, with the default look, for an application with a part that hears {@code CloseRequested}. */
    private static Wiring answering(Consumer<Shell> tree) {
        return answering(List.of("demo.Guard"), tree);
    }

    /** {@link #answering(Consumer)}, with {@code hearers} hearing {@code CloseRequested}, asked in that order. */
    private static Wiring answering(List<String> hearers, Consumer<Shell> tree) {
        return new Wiring() {
            @Override
            public AppInfo info() {
                return DEMO;
            }

            @Override
            public List<String> closeHearers() {
                return hearers;
            }

            @Override
            public void tree(Shell shell) {
                tree.accept(shell);
            }
        };
    }

    @Test
    void aNameIsDeclaredOnce() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> VexelApplication.tree(
                wiring(Appearance.DEFAULT, s -> {
                    s.windows().named("twice", "One", 300, 200);
                    s.windows().named("twice", "Two", 300, 200);
                }), new String[0]));
        assertTrue(e.getMessage().contains("twice"), e.getMessage());
    }

    @Test
    void theMainWindowsNameIsTaken() {
        assertThrows(IllegalArgumentException.class, () -> VexelApplication.tree(
                wiring(Appearance.DEFAULT, s -> s.windows().named("main", "Main", 300, 200)), new String[0]));
    }

    @Test
    void aWindowIsDeclaredByTreeBecauseItIsClaimedAtAttach() {
        Shell bare = new Shell(Launch.parse(new String[0], DEMO.name(), Set.of()), DEMO);
        Windows windows = new Windows(bare, null);
        bare.phase(Phase.WINDOW);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> windows.named("late", "Late", 300, 200));
        assertTrue(e.getMessage().contains("ATTACH") && e.getMessage().contains("WINDOW"), e.getMessage());
    }

    @Test
    void theWindowsDoNotExistBeforeTheGuiPhase() {
        Shell bare = new Shell(Launch.parse(new String[0], DEMO.name(), Set.of()), DEMO);
        IllegalStateException e = assertThrows(IllegalStateException.class, bare::windows);
        assertTrue(e.getMessage().contains("GUI"), e.getMessage());
    }

    @Test
    void aDeclarationNeedsANameAndASize() {
        assertThrows(IllegalArgumentException.class, () -> NamedWindow.of(" ", "Blank", 300, 200));
        assertThrows(IllegalArgumentException.class, () -> NamedWindow.of("flat", "Flat", 300, 0));
        assertEquals("", NamedWindow.of("untitled", null, 300, 200).title());
    }
}

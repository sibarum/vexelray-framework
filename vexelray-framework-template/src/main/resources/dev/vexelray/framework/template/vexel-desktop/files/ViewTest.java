package ${packageName};

import ${packageName}.core.Editor;
import ${packageName}.core.Messages;
import ${packageName}.core.Messages.Caption;
import ${packageName}.core.Messages.Go;
import ${packageName}.core.Messages.Open;
import ${packageName}.core.Messages.Status;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Frames;
import dev.vexelray.framework.shell.Heard;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Cuts;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Action;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Tabs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole application, built headless by the generated wiring (no window, no GPU) and driven through the same
 * messages a user's gestures send.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and starts the components, so the editor and the
 * jobs run on their own lanes here, exactly as in the window. The view runs in frames, and there is no loop to run
 * them, so this test runs them ({@link Frames}): this thread is the GUI thread. No window opens, so the framework never
 * says {@code WindowOpened} for the main one: no session is restored and no settings are written, and this test leaves
 * the user's own settings alone.
 *
 * <p>The test hears what the editor told the view as one more listener ({@code frames.hear}), in the frames it runs, on
 * its own thread. Every part answers in its own time, so the test waits for what it asked for ({@code frames.until}),
 * which runs frames until it holds and fails saying what never came. So nothing here sleeps, locks or needs a
 * concurrent list: the test touches no thread.
 */
class ViewTest {

    @TempDir
    Path dir;

    private Shell shell;
    private ${className}AppWiring wiring;
    private Workspace ws;
    private Frames frames;
    /** Every caption the editor sent, in order. */
    private Heard<Caption> captions;
    /** Every status line the editor sent, in order. */
    private Heard<Status> statuses;
    /** Every answer to a close request, the editor's and the framework's own. */
    private Heard<CloseAnswer> closeAnswers;
    /** How this test answers a question: by default, it fails the test, since nothing should be asked unexpectedly. */
    private Consumer<Modal> answer = modal -> {
        throw new AssertionError("asked unexpectedly: " + modal.title());
    };

    @BeforeEach
    void build() {
        wiring = new ${className}AppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
        // This test's dialogs in place of the framework's, which would hold every question for a window that never
        // comes. Taken back as late as WINDOW, so handing them over after the tree is built is in time.
        shell.dialogs(new Answering());
        frames = VexelApplication.frames(shell);
        captions = frames.hear(Messages.CAPTION_TOPIC);
        statuses = frames.hear(Messages.STATUS_TOPIC);
        closeAnswers = frames.hear(CloseAnswer.TOPIC);
        ws = wiring.ui().workspace();
        cuts = Cuts.watch(shell.gui());
    }

    /**
     * What changed on screen in one frame while a test ran, which every test here then fails on: nothing this
     * application shows changes in one frame. The framework fades it all (a colour, an opacity, words, an image, a node
     * arriving, leaving, hidden or shown) as long as the view keeps its nodes and changes them. A cut found here means
     * a node was rebuilt where it should have been changed, or something stepped round the framework. Fix that; do not
     * take this watch out. If the framework cannot do it, that is a framework note (docs/framework-notes.md).
     */
    private Cuts cuts;

    /**
     * Dialogs that answer a question with {@link #answer}, and cancel every file picker. Asked in a frame, so on this
     * thread, like everything else here.
     */
    private final class Answering implements Dialogs {

        @Override
        public void ask(Modal modal) {
            answer.accept(modal);
        }

        @Override
        public void pick(PickFile request, Sender<Picked> picked) {
            picked.send(Picked.cancelled(request.id()));
        }
    }

    @AfterEach
    void dispose() {
        List<Cuts.Cut> found = cuts.found();
        cuts.close();
        shell.disposer().close();
        assertEquals(List.of(), found, "changed on screen in one frame; keep the node and change it (see cuts)");
    }

    /**
     * Answer every question as pressing the button labelled {@code label} would: send the message the button declares,
     * which a dialog carries as data. Remember what was asked.
     */
    private Consumer<Modal> pressing(String label, List<String> asked) {
        return modal -> {
            asked.add(modal.title());
            Object message = modal.buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow()
                    .message();
            shell.bus().publish(Answer.TOPIC, (Answer) message);
        };
    }

    /** Far from the framework's own tickets, which start at 1, so its gate ignores these answers. */
    private long tickets = 1_000_000;

    /**
     * Ask the editor what the framework asks it when the main window's close button is pressed, and wait for its
     * answer: whether to go ahead, which the framework would follow by closing the window. The request is addressed to
     * the editor, as the framework addresses each part that hears it, in turn.
     */
    private CloseAnswer quit() {
        long ticket = ++tickets;
        shell.bus().publish(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, ticket, Editor.class.getName()));
        frames.until("the editor to answer the quit", () -> answerTo(ticket) != null);
        return answerTo(ticket);
    }

    private CloseAnswer answerTo(long ticket) {
        return closeAnswers.all().stream().filter(a -> a.ticket() == ticket).findFirst().orElse(null);
    }

    private void tell(Intent.Command command) {
        shell.bus().publish(Intent.INTENTS, new Intent(command));
    }

    private Buffer opened(Path file) {
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(file, true));
        frames.until("the file to open", () -> ws.find(file) != null);
        return ws.find(file);
    }

    @Test
    void itStartsWithNothingOpen() {
        assertTrue(ws.all().isEmpty());
    }

    /**
     * The commands are on the framework's keymap, under their ids and with their default chords, which is what puts
     * the key button in the title bar and what the person's changes are stored against.
     */
    @Test
    void theCommandsAreOnTheKeymapWithTheirDefaults() {
        Keymap keys = shell.keymap();
        Action save = keys.action("file.save").orElseThrow();
        assertEquals(List.of(Shortcut.of(Key.S, Modifier.CONTROL)), keys.chords(save));
        Action next = keys.action("tab.next").orElseThrow();
        assertEquals(List.of(Shortcut.of(Key.TAB, Modifier.CONTROL), Shortcut.of(Key.PAGE_DOWN, Modifier.CONTROL)),
                keys.chords(next));
    }

    @Test
    void aFileOpensThroughTheEditorAndTheReadingJob() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "hello"));
        assertEquals("hello", b.field.text());
        frames.untilHeard(captions, new Caption("a.txt"));
    }

    @Test
    void typingMakesAFileUnsavedAndTheCaptionSaysSo() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "hello"));
        assertFalse(b.dirty());
        b.field.insert("!");
        frames.untilHeard(captions, new Caption("• a.txt"));
    }

    @Test
    void savingWritesTheFileAndKeepsItsLineEndings() throws Exception {
        Path file = dir.resolve("crlf.txt");
        Files.write(file, "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        Buffer b = opened(file);
        b.field.caret(b.field.text().length());
        b.field.insert("three\n");
        frames.until("unsaved", b::dirty);
        tell(Intent.Command.SAVE_FRONT);
        // Clean the moment the text is taken, written a moment later: wait for the write to say it landed.
        frames.untilHeard(statuses, new Status("Saved crlf.txt", false));
        assertFalse(b.dirty());
        assertEquals("one\r\ntwo\r\nthree\r\n", Files.readString(file));
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanOpeningItTwice() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        opened(a);
        opened(Files.writeString(dir.resolve("b.txt"), "b"));
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(a, true));
        frames.until("a in front", () -> ws.front() != null && ws.front().path.equals(a.toAbsolutePath().normalize()));
        assertEquals(2, ws.all().size());
    }

    @Test
    void aMarkdownFileIsGivenSpansAndATextFileIsNot() throws Exception {
        Buffer md = opened(Files.writeString(dir.resolve("notes.md"), "# Title\n\nSome *emphasis*."));
        Buffer txt = opened(Files.writeString(dir.resolve("notes.txt"), "# Title\n\nSome *emphasis*."));
        frames.until("the markdown to be marked", () -> !md.field.spans().isEmpty());
        assertTrue(txt.field.spans().isEmpty());
    }

    @Test
    void thePathBarCreatesAFileThatIsNotThereYet() {
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        frames.until("the folder", () -> dir.toAbsolutePath().equals(wiring.ui().navigator().folder()));
        shell.bus().publish(Messages.GO_TOPIC, new Go("new.txt"));
        Path created = dir.resolve("new.txt").toAbsolutePath().normalize();
        frames.until("the file to be created and opened", () -> ws.find(created) != null);
        assertTrue(Files.isRegularFile(created));
    }

    /** The tree asks for the folder shown, a job reads it, and the view answers the tree with its rows. */
    @Test
    void showingAFolderListsItInTheTree() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "a").toAbsolutePath().normalize();
        Files.createDirectory(dir.resolve("sub"));
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        Navigator nav = wiring.ui().navigator();
        frames.until("a row for the file", () -> nav.hasRow(file));
        assertTrue(nav.isFolder(dir.resolve("sub").toAbsolutePath().normalize()), "the listing said which is a folder");
    }

    /**
     * More tabs than a mailbox holds, all unsaved, closed at once with Save all: one {@code Flush} and one
     * {@code CloseTabs} for the lot, and a retitle per tab that is the tab's last word rather than a backlog. Before
     * those were lists and state, this needed the view's mailboxes 4,096 deep.
     */
    @Test
    void closingMoreUnsavedTabsThanAMailboxHoldsSavesAndClosesThemAll() throws Exception {
        List<Buffer> tabs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tabs.add(opened(Files.writeString(dir.resolve("f" + i + ".txt"), "")));
        }
        for (Buffer b : tabs) {
            b.field.insert("x");
        }
        frames.until("every tab unsaved", () -> tabs.stream().allMatch(Buffer::dirty));
        List<String> asked = new ArrayList<>();
        answer = pressing("Save all", asked);
        shell.bus().publish(Messages.CLOSE_TOPIC, new Messages.Close(tabs.stream().map(b -> b.id).toList()));
        frames.until("every tab closed", () -> ws.all().isEmpty());
        assertEquals(List.of("Unsaved changes"), asked);
        for (int i = 0; i < 100; i++) {
            assertEquals("x", Files.readString(dir.resolve("f" + i + ".txt")));
        }
    }

    @Test
    void aBinaryFileIsRefusedOnTheStatusLine() throws Exception {
        Path binary = dir.resolve("blob.bin");
        Files.write(binary, new byte[] {0, 1, 2, 3});
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(binary, true));
        frames.until("the refusal", () -> statuses.all().stream()
                .anyMatch(s -> s.alert() && s.message().startsWith("Could not open blob.bin")));
        assertTrue(ws.all().isEmpty());
    }

    @Test
    void closingAnUnsavedTabAsksAndDontSaveCloses() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "a"));
        b.field.insert("x");
        frames.until("unsaved", b::dirty);
        List<String> asked = new ArrayList<>();
        answer = pressing("Don't save", asked);
        shell.bus().publish(Tabs.CHOSEN, new Tabs.Chosen(ws.address(), Workspace.CLOSE, ws.all().indexOf(b)));
        frames.until("the tab to close", () -> ws.byId(b.id) == null);
        assertEquals(List.of("Unsaved changes"), asked);
        assertEquals("a", Files.readString(dir.resolve("a.txt")), "Don't save does not save");
    }

    @Test
    void quittingACleanSessionGoesWithoutAsking() {
        List<String> asked = new ArrayList<>();
        answer = pressing("Cancel", asked);
        assertTrue(quit().proceed());
        assertTrue(asked.isEmpty());
    }

    @Test
    void quittingAsksAndCancelKeepsTheWindow() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "a"));
        b.field.insert("x");
        frames.until("unsaved", b::dirty);
        List<String> asked = new ArrayList<>();
        answer = pressing("Cancel", asked);
        assertFalse(quit().proceed(), "cancelled");
        assertEquals(List.of("Quit with unsaved changes?"), asked);
    }

    @Test
    void saveAllThenQuitProceedsOnceEverythingIsWritten() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        Buffer first = opened(a);
        Buffer second = opened(c);
        first.field.insert("1");
        second.field.insert("2");
        frames.until("both unsaved", () -> first.dirty() && second.dirty());
        answer = pressing("Save all", new ArrayList<>());
        assertTrue(quit().proceed());
        assertEquals("1a", Files.readString(a));
        assertEquals("2c", Files.readString(c));
    }
}

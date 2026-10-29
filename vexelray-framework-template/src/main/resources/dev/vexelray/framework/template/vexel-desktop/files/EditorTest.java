package ${packageName}.core;

import ${packageName}.core.Messages.AddTab;
import ${packageName}.core.Messages.Answer;
import ${packageName}.core.Messages.Ask;
import ${packageName}.core.Messages.Begin;
import ${packageName}.core.Messages.Caption;
import ${packageName}.core.Messages.Choice;
import ${packageName}.core.Messages.CloseTab;
import ${packageName}.core.Messages.Closing;
import ${packageName}.core.Messages.Create;
import ${packageName}.core.Messages.Created;
import ${packageName}.core.Messages.Edited;
import ${packageName}.core.Messages.FileRead;
import ${packageName}.core.Messages.Find;
import ${packageName}.core.Messages.Flush;
import ${packageName}.core.Messages.Found;
import ${packageName}.core.Messages.FrontTab;
import ${packageName}.core.Messages.Go;
import ${packageName}.core.Messages.Kind;
import ${packageName}.core.Messages.Open;
import ${packageName}.core.Messages.Purpose;
import ${packageName}.core.Messages.QuitAnswer;
import ${packageName}.core.Messages.Read;
import ${packageName}.core.Messages.Save;
import ${packageName}.core.Messages.SaveFailed;
import ${packageName}.core.Messages.ShowFolder;
import ${packageName}.core.Messages.Status;
import ${packageName}.core.Messages.Why;
import ${packageName}.core.Messages.Write;
import ${packageName}.core.Messages.Written;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller, alone: no window, no lanes, no jobs. Each test hands the {@link Editor} a message the way its lane
 * would, one at a time on this thread, and reads what it sent back. The jobs' replies are handed in by hand, which is
 * what makes the interesting orders (a write that fails, a set of closes that arrives out of order) as easy to test as
 * the common one.
 */
class EditorTest {

    @TempDir
    Path dir;

    private Atchung bus;
    private Settings settings;
    private Editor editor;
    /** Everything the editor published, in order. */
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        bus = Atchung.create();
        settings = Settings.at(dir.resolve("settings.properties"));
        editor = new Editor(bus, settings, Launch.parse(new String[0], "test", Set.of()));
        for (Topic<?> t : List.of(Messages.SHOW_FOLDER_TOPIC, Messages.ADD_TAB_TOPIC, Messages.FRONT_TAB_TOPIC,
                Messages.CLOSE_TAB_TOPIC, Messages.RETITLE_TOPIC, Messages.CAPTION_TOPIC, Messages.STATUS_TOPIC,
                Messages.ASK_TOPIC, Messages.FLUSH_TOPIC, Messages.SAVE_FAILED_TOPIC, Messages.QUIT_ANSWER_TOPIC,
                Messages.READ_TOPIC, Messages.WRITE_TOPIC, Messages.FIND_TOPIC, Messages.CREATE_TOPIC)) {
            listen(t);
        }
    }

    private <T> void listen(Topic<T> topic) {
        bus.subscribe(topic, sent::add);
    }

    /** What was sent of one kind, in order, and forget everything sent so far. */
    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    /** Open {@code name} as the reader would answer it, and return the new tab's id. */
    private long opened(String name, String text) {
        Path file = dir.resolve(name).toAbsolutePath().normalize();
        editor.open(new Open(file, true));
        assertEquals(List.of(new Read(file, Why.OPEN, true)), took(Read.class));
        editor.read(new FileRead(file, Why.OPEN, true, text, false, "", ""));
        List<AddTab> added = took(AddTab.class);
        assertEquals(1, added.size());
        return added.getFirst().id();
    }

    @Test
    void openingReadsTheFileThenAddsATabInFront() {
        Path file = dir.resolve("a.txt").toAbsolutePath().normalize();
        editor.open(new Open(file, true));
        assertEquals(List.of(new Read(file, Why.OPEN, true)), sent.stream().filter(Read.class::isInstance).toList());
        sent.clear();
        editor.read(new FileRead(file, Why.OPEN, true, "hello", false, "", ""));
        assertEquals(List.of(new AddTab(1, file, "hello", true)), sent.stream().filter(AddTab.class::isInstance).toList());
        assertTrue(sent.contains(new Caption("a.txt")));
        assertTrue(sent.contains(new Status("Opened a.txt", false)));
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanReadingItAgain() {
        long a = opened("a.txt", "a");
        opened("b.txt", "b");
        editor.open(new Open(dir.resolve("a.txt"), true));
        assertEquals(List.of(new FrontTab(a, true)), took(FrontTab.class));
        assertTrue(took(Read.class).isEmpty());
    }

    @Test
    void aFileThatCannotBeOpenedIsRefusedOnTheStatusLine() {
        Path blob = dir.resolve("blob.bin").toAbsolutePath().normalize();
        editor.open(new Open(blob, true));
        editor.read(new FileRead(blob, Why.OPEN, true, "", false, "", "it is binary"));
        assertTrue(took(AddTab.class).isEmpty());
        editor.open(new Open(blob, true));
        assertEquals(1, took(Read.class).size(), "a refusal does not leave the file marked as being read");
    }

    @Test
    void goingUnsavedRetitlesTheTabAndTheCaption() {
        long a = opened("a.txt", "a");
        editor.edited(new Edited(a, true));
        assertTrue(sent.contains(new Messages.Retitle(a, "• a.txt")));
        assertTrue(sent.contains(new Caption("• a.txt")));
    }

    @Test
    void closingASavedTabClosesIt() {
        long a = opened("a.txt", "a");
        editor.closing(new Closing(1, 1, a, false, Purpose.TABS));
        assertEquals(List.of(new CloseTab(a)), took(CloseTab.class));
    }

    /** The view's word on the tab decides, because the Edited saying so may still be on its way. */
    @Test
    void closingAnUnsavedTabAsksAndDontSaveCloses() {
        long a = opened("a.txt", "a");
        editor.closing(new Closing(1, 1, a, true, Purpose.TABS));
        List<Ask> asked = took(Ask.class);
        assertEquals("Unsaved changes", asked.getFirst().title());
        editor.answer(new Answer(asked.getFirst().question(), Choice.OTHER));
        assertEquals(List.of(new CloseTab(a)), took(CloseTab.class));
        assertTrue(took(Write.class).isEmpty(), "Don't save does not save");
    }

    @Test
    void aSetOfClosesIsDecidedWhenTheLastArrivesInWhateverOrder() {
        long a = opened("a.txt", "a");
        long b = opened("b.txt", "b");
        editor.closing(new Closing(5, 2, b, false, Purpose.TABS));
        assertTrue(took(CloseTab.class).isEmpty(), "half a set decides nothing");
        editor.closing(new Closing(5, 2, a, false, Purpose.TABS));
        assertEquals(List.of(new CloseTab(a), new CloseTab(b)).size(), took(CloseTab.class).size());
    }

    @Test
    void quittingWithNothingUnsavedProceedsWithoutAsking() {
        editor.closing(new Closing(7, 1, Messages.NO_TAB, false, Purpose.QUIT));
        assertEquals(List.of(new QuitAnswer(7, true)), took(QuitAnswer.class));
        assertTrue(took(Ask.class).isEmpty());
    }

    @Test
    void saveAllThenQuitProceedsOnceEveryWriteHasLanded() {
        long a = opened("a.txt", "a");
        long c = opened("c.txt", "c");
        editor.closing(new Closing(9, 2, a, true, Purpose.QUIT));
        editor.closing(new Closing(9, 2, c, true, Purpose.QUIT));
        Ask ask = took(Ask.class).getFirst();
        assertEquals("Quit with unsaved changes?", ask.title());
        editor.answer(new Answer(ask.question(), Choice.CONFIRM));
        assertEquals(List.of(new Flush(a), new Flush(c)), took(Flush.class));

        editor.save(new Save(a, "1a", true));
        editor.save(new Save(c, "2c", true));
        List<Write> writes = took(Write.class);
        assertEquals(List.of("1a", "2c"), writes.stream().map(Write::text).toList());
        editor.written(new Written(a, writes.get(0).path(), ""));
        assertTrue(took(QuitAnswer.class).isEmpty(), "one of two has landed");
        editor.written(new Written(c, writes.get(1).path(), ""));
        assertEquals(List.of(new QuitAnswer(9, true)), took(QuitAnswer.class));
    }

    @Test
    void aWriteThatFailsCancelsTheQuitAndLeavesTheTabUnsaved() {
        long a = opened("a.txt", "a");
        editor.closing(new Closing(3, 1, a, true, Purpose.QUIT));
        editor.answer(new Answer(took(Ask.class).getFirst().question(), Choice.CONFIRM));
        editor.save(new Save(a, "x", true));
        Write w = took(Write.class).getFirst();
        editor.written(new Written(a, w.path(), "disk full"));
        assertTrue(sent.contains(new SaveFailed(a)));
        assertTrue(sent.contains(new Status("Could not save a.txt: disk full", true)));
        assertEquals(List.of(new QuitAnswer(3, false)), took(QuitAnswer.class));
    }

    @Test
    void thePathBarCreatesANameThatIsNotThereYetInTheFolderShown() {
        editor.found(new Found(dir, Why.RESTORE_FOLDER, Kind.FOLDER));
        sent.clear();
        editor.go(new Go("new.txt"));
        Path wanted = dir.resolve("new.txt").toAbsolutePath().normalize();
        assertEquals(List.of(new Find(wanted, Why.GO)), took(Find.class));
        editor.found(new Found(wanted, Why.GO, Kind.NEW));
        assertEquals(List.of(new Create(wanted)), took(Create.class));
        editor.created(new Created(wanted, ""));
        assertTrue(sent.contains(new ShowFolder(dir)), "the folder is listed again");
        assertEquals(1, took(AddTab.class).size());
    }

    /** The tabs come back as they were: one file asked for at a time, the next when the last is answered. */
    @Test
    void theSessionComesBackInOrderAndIsRememberedOnlyAfterwards() {
        Path a = dir.resolve("a.txt").toAbsolutePath().normalize();
        Path b = dir.resolve("b.txt").toAbsolutePath().normalize();
        settings.putString(Editor.FOLDER, dir.toString()).putList(Editor.FILES, List.of(a.toString(), b.toString()))
                .putString(Editor.FRONT, a.toString()).save();

        editor.begin(new Begin());
        assertEquals(List.of(new Find(dir.toAbsolutePath().normalize(), Why.RESTORE_FOLDER)), took(Find.class));
        editor.found(new Found(dir, Why.RESTORE_FOLDER, Kind.FOLDER));
        assertEquals(List.of(new Read(a, Why.RESTORE_FILE, true)), took(Read.class), "only the first file");
        editor.read(new FileRead(a, Why.RESTORE_FILE, true, "a", false, "", ""));
        assertEquals(List.of(new Read(b, Why.RESTORE_FILE, true)), took(Read.class));
        editor.read(new FileRead(b, Why.RESTORE_FILE, true, "b", false, "", ""));
        assertEquals(1, took(FrontTab.class).size(), "the remembered front comes back to the front");
        assertEquals(List.of(a.toString(), b.toString()), Settings.at(dir.resolve("settings.properties"))
                .getList(Editor.FILES));
    }
}

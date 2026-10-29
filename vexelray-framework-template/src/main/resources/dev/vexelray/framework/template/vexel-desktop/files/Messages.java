package ${packageName}.core;

import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Every message the application's parts send each other: what each one says, and the topic it travels on.
 *
 * <h2>Message passing is how the parts talk</h2>
 *
 * <p>The application is three kinds of part, and none of them holds another. The {@link Editor} decides; it owns the
 * session, and changes it only by running its own declared steps. The view, in the {@code -gui} module, shows; it
 * owns the widgets. The jobs ({@link ReadFile}, {@link WriteFile}, {@link FindPath}, {@link CreateFile},
 * {@link ReadSession}, {@link WriteSession}, {@link ListFolder}, {@link MarkText}) touch the disk or do slow work, on
 * the offload lane. Each sends a record on a topic and the others hear it on their own lane, so no state is shared
 * and nothing needs a lock.
 *
 * <p>A record here is what crossed, and it crosses as itself: the processor refuses a payload that is not deeply
 * immutable (T2.4). A list may cross when what it holds may, and the list itself must not change after it is sent,
 * which no type can show: so each record that holds one copies it in its constructor, and no sender can get that
 * wrong.
 *
 * <h2>Reading this file</h2>
 *
 * <p>Grouped by direction, each record beside the name of its topic. A topic name is a string the processor checks
 * against every sender and receiver it can see (T4.1, T4.4): two topics with one spelling and two types are a compile
 * error. The {@code Topic} constants are what a sender publishes on.
 */
public final class Messages {

    private Messages() {
    }

    /** The id of no tab: nothing is in front. */
    public static final long NO_TAB = -1;

    // ------------------------------------------------------------------ the view asks the editor

    /** The path bar was submitted, or the navigator asked to show a folder: what the text names is the editor's call. */
    public static final String GO = "editor.go";
    public static final Topic<Go> GO_TOPIC = Topic.of(GO, Go.class);

    public record Go(String text) {
    }

    /** A file was chosen in the navigator. {@code focus}: whether its tab takes the keyboard. */
    public static final String OPEN = "editor.open";
    public static final Topic<Open> OPEN_TOPIC = Topic.of(OPEN, Open.class);

    public record Open(Path path, boolean focus) {
    }

    /** A different tab is in front. */
    public static final String FRONTED = "editor.fronted";
    public static final Topic<Fronted> FRONTED_TOPIC = Topic.of(FRONTED, Fronted.class);

    public record Fronted(long id) {
    }

    /** A tab's text went unsaved, or came back to what is on disk. */
    public static final String EDITED = "editor.edited";
    public static final Topic<Edited> EDITED_TOPIC = Topic.of(EDITED, Edited.class);

    public record Edited(long id, boolean dirty) {
    }

    /**
     * Close these tabs: one, the others, or all of them. The editor asks first if any is unsaved. Whether one is
     * unsaved is the editor's to know, from the {@link Edited}s the view sent before this, which arrive first because
     * one sender's messages to one receiver keep their order.
     */
    public static final String CLOSE = "editor.close";
    public static final Topic<Close> CLOSE_TOPIC = Topic.of(CLOSE, Close.class);

    public record Close(List<Long> tabs) {
        public Close {
            tabs = List.copyOf(tabs);
        }
    }

    /**
     * The window is being asked to close: answer the close gate with this ticket, after asking if anything is
     * unsaved.
     */
    public static final String QUIT = "editor.quit";
    public static final Topic<Quit> QUIT_TOPIC = Topic.of(QUIT, Quit.class);

    public record Quit(long gate) {
    }

    /**
     * A tab's text, to be written: after Ctrl+S, or because the editor asked with {@link Flush}. The view has already
     * marked the text saved at this point, so typing that lands while it is written stays unsaved. {@code open} is
     * false when the tab had gone by the time it was asked for.
     */
    public static final String SAVE = "editor.save";
    public static final Topic<Save> SAVE_TOPIC = Topic.of(SAVE, Save.class);

    public record Save(long id, String text, boolean open) {
    }

    /**
     * How a question was answered: its first button, its second, or cancel. Each carries what the question's run goes
     * on with, so the editor turns an answer into the next step without a switch.
     */
    public enum Choice {
        CONFIRM(new Editor.Confirmed()), OTHER(new Editor.Declined()), CANCEL(new Editor.Cancelled());

        final Object outcome;

        Choice(Object outcome) {
            this.outcome = outcome;
        }
    }

    /** The answer to an {@link Ask}. */
    public static final String ANSWER = "editor.answer";
    public static final Topic<Answer> ANSWER_TOPIC = Topic.of(ANSWER, Answer.class);

    public record Answer(long question, Choice choice) {
    }

    /** The window exists: bring back last time's session and open what the command line named. */
    public static final String BEGIN = "editor.begin";
    public static final Topic<Begin> BEGIN_TOPIC = Topic.of(BEGIN, Begin.class);

    public record Begin() {
    }

    // ------------------------------------------------------------------ the editor tells the view

    /** Point the navigator at a folder, or list it again. */
    public static final String SHOW_FOLDER = "view.show-folder";
    public static final Topic<ShowFolder> SHOW_FOLDER_TOPIC = Topic.of(SHOW_FOLDER, ShowFolder.class);

    public record ShowFolder(Path path) {
    }

    /** Open a tab for a file the editor has read, in front. */
    public static final String ADD_TAB = "view.add-tab";
    public static final Topic<AddTab> ADD_TAB_TOPIC = Topic.of(ADD_TAB, AddTab.class);

    public record AddTab(long id, Path path, String text, boolean focus) {
    }

    /** Bring a tab that is already open to the front, and ring it so the eye finds it. */
    public static final String FRONT_TAB = "view.front-tab";
    public static final Topic<FrontTab> FRONT_TAB_TOPIC = Topic.of(FRONT_TAB, FrontTab.class);

    public record FrontTab(long id, boolean focus) {
    }

    /** Close a tab; the editor has asked whatever had to be asked. */
    public static final String CLOSE_TAB = "view.close-tab";
    public static final Topic<CloseTab> CLOSE_TAB_TOPIC = Topic.of(CLOSE_TAB, CloseTab.class);

    public record CloseTab(long id) {
    }

    /** What a tab's header says: its file's name, with a bullet while it is unsaved. */
    public static final String RETITLE = "view.retitle";
    public static final Topic<Retitle> RETITLE_TOPIC = Topic.of(RETITLE, Retitle.class);

    public record Retitle(long id, String title) {
    }

    /** The front tab's title, for the title bar and the status line; empty when nothing is open. */
    public static final String CAPTION = "view.caption";
    public static final Topic<Caption> CAPTION_TOPIC = Topic.of(CAPTION, Caption.class);

    public record Caption(String file) {
    }

    /** Say something on the status line. {@code alert}: something was refused or failed, so draw the eye to it. */
    public static final String STATUS = "view.status";
    public static final Topic<Status> STATUS_TOPIC = Topic.of(STATUS, Status.class);

    public record Status(String message, boolean alert) {
    }

    /** Ask the person: two buttons and Cancel. The answer comes back as an {@link Answer} with this question. */
    public static final String ASK = "view.ask";
    public static final Topic<Ask> ASK_TOPIC = Topic.of(ASK, Ask.class);

    public record Ask(long question, String title, String message, String confirm, String other) {
    }

    /** Send the text of a tab back as a {@link Save}: the editor is saving it before closing or quitting. */
    public static final String FLUSH = "view.flush";
    public static final Topic<Flush> FLUSH_TOPIC = Topic.of(FLUSH, Flush.class);

    public record Flush(long id) {
    }

    /** The write a {@link Save} asked for did not land, so the tab is unsaved whatever its history says. */
    public static final String SAVE_FAILED = "view.save-failed";
    public static final Topic<SaveFailed> SAVE_FAILED_TOPIC = Topic.of(SAVE_FAILED, SaveFailed.class);

    public record SaveFailed(long id) {
    }

    /** What the close gate with this ticket answers: let the window close, or keep it. */
    public static final String QUIT_ANSWER = "view.quit-answer";
    public static final Topic<QuitAnswer> QUIT_ANSWER_TOPIC = Topic.of(QUIT_ANSWER, QuitAnswer.class);

    public record QuitAnswer(long gate, boolean proceed) {
    }

    // ------------------------------------------------------------------ the editor and the jobs

    /** Why a file is being read or a path looked at, carried through the job so its answer can be acted on. */
    public enum Why {
        /** Chosen in the navigator, or found already open. */
        OPEN,
        /** Typed into the path bar. */
        GO,
        /** The folder the last session showed. */
        RESTORE_FOLDER,
        /** A file the last session had open, or one the command line named: one at a time, in order. */
        RESTORE_FILE,
        /** A path on the command line, which may be a folder or a file. */
        ARGUMENT
    }

    /** Read a file and decide whether it can be opened ({@link ReadFile}). */
    public static final String READ = "file.read";
    public static final Topic<Read> READ_TOPIC = Topic.of(READ, Read.class);

    public record Read(Path path, Why why, boolean focus) {
    }

    /** What {@link Read} found: the text, or why not ({@code problem}, empty when there is none). */
    public static final String FILE_READ = "file.read.done";
    public static final Topic<FileRead> FILE_READ_TOPIC = Topic.of(FILE_READ, FileRead.class);

    public record FileRead(Path path, Why why, boolean focus, String text, boolean crlf, String notes, String problem) {
    }

    /** Write a file, with its own line endings ({@link WriteFile}). */
    public static final String WRITE = "file.write";
    public static final Topic<Write> WRITE_TOPIC = Topic.of(WRITE, Write.class);

    public record Write(long id, Path path, String text, boolean crlf) {
    }

    /** What {@link Write} did: {@code problem} is empty when it landed. */
    public static final String WRITTEN = "file.written";
    public static final Topic<Written> WRITTEN_TOPIC = Topic.of(WRITTEN, Written.class);

    public record Written(long id, Path path, String problem) {
    }

    /** What is at a path, as far as opening it goes ({@link FindPath}). */
    public enum Kind {
        FOLDER, FILE,
        /** Nothing, in a folder that is there: a name a file can be created under. */
        NEW,
        /** Nothing, and no folder to make it in. */
        NOWHERE
    }

    /** Look at what is at a path. */
    public static final String FIND = "path.find";
    public static final Topic<Find> FIND_TOPIC = Topic.of(FIND, Find.class);

    public record Find(Path path, Why why) {
    }

    public static final String FOUND = "path.found";
    public static final Topic<Found> FOUND_TOPIC = Topic.of(FOUND, Found.class);

    public record Found(Path path, Why why, Kind kind) {
    }

    /** Make an empty file ({@link CreateFile}). */
    public static final String CREATE = "file.create";
    public static final Topic<Create> CREATE_TOPIC = Topic.of(CREATE, Create.class);

    public record Create(Path path) {
    }

    /** What {@link Create} did, and the line endings a new file gets here. */
    public static final String CREATED = "file.created";
    public static final Topic<Created> CREATED_TOPIC = Topic.of(CREATED, Created.class);

    public record Created(Path path, boolean crlf, String problem) {
    }

    /** Read back what the last session remembered, and what the command line named ({@link ReadSession}). */
    public static final String RECALL = "session.recall";
    public static final Topic<Recall> RECALL_TOPIC = Topic.of(RECALL, Recall.class);

    public record Recall() {
    }

    /**
     * What there is to bring back, every path absolute and normalised: the folder shown, the files open in tab order,
     * the one in front, the command line's paths, and the folder the application started in, for when there is none.
     */
    public static final String RECALLED = "session.recalled";
    public static final Topic<Recalled> RECALLED_TOPIC = Topic.of(RECALLED, Recalled.class);

    public record Recalled(Optional<Path> folder, List<Path> files, Optional<Path> front, List<Path> arguments,
                           Path started) {
        public Recalled {
            files = List.copyOf(files);
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * Keep the session for next time ({@link WriteSession}): the folder shown, the files open, the one in front. A
     * value, so the editor tells whether it changed with {@code equals}.
     */
    public static final String REMEMBER = "session.remember";
    public static final Topic<Remember> REMEMBER_TOPIC = Topic.of(REMEMBER, Remember.class);

    public record Remember(Optional<Path> folder, List<Path> files, Optional<Path> front) {
        public Remember {
            files = List.copyOf(files);
        }
    }

    // ------------------------------------------------------------------ the view and its jobs

    /** List one level of the navigator's tree ({@link ListFolder}): the folder shown's, or a folder in it. */
    public static final String LISTING = "view.listing";
    public static final Topic<Listing> LISTING_TOPIC = Topic.of(LISTING, Listing.class);

    /** {@code roots}: the level is the tree's top, the folder shown, rather than a folder opened in it. */
    public record Listing(String tree, Path dir, boolean roots) {
    }

    /** One entry of a level: where it is, what its row says, and whether it opens. */
    public record Item(Path path, String name, boolean folder) {
    }

    public static final String LISTED = "view.listed";
    public static final Topic<Listed> LISTED_TOPIC = Topic.of(LISTED, Listed.class);

    public record Listed(String tree, Path dir, boolean roots, List<Item> items) {
        public Listed {
            items = List.copyOf(items);
        }
    }

    /** Colour a Markdown document ({@link MarkText}): its text as of {@code version}, and the colours to use. */
    public static final String MARK = "view.mark";
    public static final Topic<Mark> MARK_TOPIC = Topic.of(MARK, Mark.class);

    public record Mark(String field, String text, long version, Markdown.Style style) {
    }

    public static final String MARKED = "view.marked";
    public static final Topic<Marked> MARKED_TOPIC = Topic.of(MARKED, Marked.class);

    public record Marked(String field, long version, List<dev.vexelray.gui.core.text.Span> spans) {
        public Marked {
            spans = List.copyOf(spans);
        }
    }
}

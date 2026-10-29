package ${packageName}.core;

import ${packageName}.core.Messages.AddTab;
import ${packageName}.core.Messages.Answer;
import ${packageName}.core.Messages.Ask;
import ${packageName}.core.Messages.Begin;
import ${packageName}.core.Messages.Caption;
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
import ${packageName}.core.Messages.Fronted;
import ${packageName}.core.Messages.Go;
import ${packageName}.core.Messages.Kind;
import ${packageName}.core.Messages.Open;
import ${packageName}.core.Messages.Purpose;
import ${packageName}.core.Messages.QuitAnswer;
import ${packageName}.core.Messages.Read;
import ${packageName}.core.Messages.Retitle;
import ${packageName}.core.Messages.Save;
import ${packageName}.core.Messages.SaveFailed;
import ${packageName}.core.Messages.ShowFolder;
import ${packageName}.core.Messages.Status;
import ${packageName}.core.Messages.Why;
import ${packageName}.core.Messages.Write;
import ${packageName}.core.Messages.Written;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.gui.core.app.Settings;
import sibarum.atchung.Atchung;
import sibarum.probe.Log;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The controller: every decision the editor makes, on a lane of its own, reached only by messages.
 *
 * <h2>One thread, so no locks</h2>
 *
 * <p>Everything here runs on the {@code editor} lane, one message at a time, in the order each mailbox received them.
 * The session ({@link Doc}) is a field nothing else can reach, the questions waiting for an answer and the saves
 * waiting to land are maps nothing else can reach, and so none of them needs a lock. Reaching across lanes is a
 * compile error (T2.3); a message is the only way in, and what goes out is a message too ({@link Messages}).
 *
 * <h2>What it decides</h2>
 *
 * <ul>
 *   <li>What a typed path names: a folder to show, a file to open, a new file to create, or nothing.</li>
 *   <li>Whether a file is already open, and which id a new tab gets.</li>
 *   <li>Nothing is lost without being asked: closing a tab with unsaved work, closing several, and quitting all go
 *       through one question, and a save that fails cancels whatever was waiting on it, quitting included.</li>
 *   <li>What the status line says.</li>
 *   <li>What comes back next time (the session), kept in the framework's one {@link Settings}.</li>
 * </ul>
 *
 * <p>The disk is the jobs' ({@link ReadFile}, {@link WriteFile}, {@link FindPath}, {@link CreateFile}): this sends a
 * request and acts on the reply, which comes back here like any other message. A slow disk is a slow answer, never a
 * stalled lane.
 *
 * <h2>Order</h2>
 *
 * <p>Each mailbox is in order, and two mailboxes are not in order with each other. So nothing here depends on one
 * kind of message arriving before another: a set of {@link Closing}s says how many make it and is decided when the
 * last arrives, and each carries the view's own word on whether its tab is unsaved. Jobs are not in order at all, so
 * the restored session asks for one file at a time and asks for the next when the last is answered.
 */
@Component(lane = "editor")
@Publishes({Messages.SHOW_FOLDER, Messages.ADD_TAB, Messages.FRONT_TAB, Messages.CLOSE_TAB, Messages.RETITLE,
        Messages.CAPTION, Messages.STATUS, Messages.ASK, Messages.FLUSH, Messages.SAVE_FAILED, Messages.QUIT_ANSWER,
        Messages.READ, Messages.WRITE, Messages.FIND, Messages.CREATE})
public final class Editor {

    private static final Log LOG = Log.of("${appName}.editor");

    static final String FOLDER = "session.folder";
    static final String FILES = "session.files";
    static final String FRONT = "session.front";

    /**
     * Per-item mailboxes take a whole set at once: closing every tab is a message per tab, and a full mailbox ends the
     * application (components.md, ruling 5). Far more than a person keeps open, so a burst never meets it.
     */
    static final int SET = 4096;

    private final Atchung bus;
    private final Settings settings;
    private final List<String> arguments;

    private Doc doc = Doc.initial();
    private long ids;
    private long questions;
    /** Files asked for and not yet answered, so a second click does not open a second tab. */
    private final Set<Path> reading = new HashSet<>();
    /** The questions on screen, by number: what each answer does. */
    private final Map<Long, Choices> asked = new HashMap<>();
    /** Each tab whose text has been asked for to save, and what is waiting for it to land. */
    private final Map<Long, Saving> saving = new HashMap<>();
    /** The sets of {@link Closing}s still arriving. */
    private final Map<Batch, List<Closing>> closing = new HashMap<>();
    /** The restore's steps still to take, one at a time. */
    private final Queue<Runnable> restoring = new ArrayDeque<>();
    /** Off until the restore has finished: before that, a write would overwrite what is being restored. */
    private boolean armed;
    private Remembered last;

    public Editor(Atchung bus, Settings settings, Launch launch) {
        this.bus = bus;
        this.settings = settings;
        this.arguments = List.copyOf(launch.rest());
    }

    // ------------------------------------------------------------------ the session, brought back

    /**
     * The window exists: bring back the folder, then the files that were open, then what the command line named, in
     * that order and one at a time, so the tabs come back as they were. A remembered file that has gone is skipped and
     * a folder that has gone is forgotten; the user moved them, and an editor should not complain about it.
     */
    @Subscribe(topic = Messages.BEGIN)
    public void begin(Begin begin) {
        String folder = settings.getString(FOLDER, "");
        Path remembered = path(folder);
        if (remembered != null) {
            restoring.add(() -> bus.publish(Messages.FIND_TOPIC, new Find(remembered, Why.RESTORE_FOLDER)));
        } else {
            restoring.add(() -> {
                showFolder(Path.of("").toAbsolutePath());
                next();
            });
        }
        for (String f : settings.getList(FILES)) {
            Path p = path(f);
            if (p != null) {
                restoring.add(() -> read(p, Why.RESTORE_FILE, true));
            }
        }
        for (String a : arguments) {
            Path p = path(a);
            if (p != null) {
                restoring.add(() -> bus.publish(Messages.FIND_TOPIC, new Find(p, Why.ARGUMENT)));
            }
        }
        Path front = path(settings.getString(FRONT, ""));
        restoring.add(() -> {
            if (arguments.isEmpty() && front != null && doc.entry(front) != null) {
                frontTab(doc.entry(front).id(), true);
            }
            armed = true;
            remember();
        });
        next();
    }

    private void next() {
        Runnable step = restoring.poll();
        if (step != null) {
            step.run();
        }
    }

    /** A path from a remembered or typed string, absolute and normalised; null for none or one that is not a path. */
    private static Path path(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Write the session if it differs from what was last written. Unsaved work is not part of it, so this runs when
     * the set of files, the one in front or the folder changes, not on every keystroke.
     */
    private void remember() {
        if (!armed) {
            return;
        }
        Doc.Entry f = doc.front();
        Remembered now = new Remembered(doc.folder() == null ? "" : doc.folder().toString(),
                doc.files().stream().map(Path::toString).toList(), f == null ? "" : f.path().toString());
        if (now.equals(last)) {
            return;
        }
        last = now;
        settings.putString(FOLDER, now.folder()).putList(FILES, now.files()).putString(FRONT, now.front()).save();
    }

    /** What is remembered, as a value, so "did it change" is an equals. */
    private record Remembered(String folder, List<String> files, String front) {
    }

    // ------------------------------------------------------------------ opening

    /**
     * What the path bar was given: a folder to show, a file to open, or a name that is not there yet, to create. A
     * relative path is relative to the folder shown.
     */
    @Subscribe(topic = Messages.GO)
    public void go(Go go) {
        String text = go.text().strip();
        if (text.isEmpty()) {
            return;
        }
        Path typed;
        try {
            typed = Path.of(text);
        } catch (InvalidPathException e) {
            refuse("Not a path: " + text);
            return;
        }
        Path base = doc.folder();
        Path target = (typed.isAbsolute() || base == null ? typed : base.resolve(typed)).toAbsolutePath().normalize();
        bus.publish(Messages.FIND_TOPIC, new Find(target, Why.GO));
    }

    @Subscribe(topic = Messages.OPEN)
    public void open(Open open) {
        Path file = open.path().toAbsolutePath().normalize();
        Doc.Entry already = doc.entry(file);
        if (already != null) {
            frontTab(already.id(), open.focus());
        } else {
            read(file, Why.OPEN, open.focus());
        }
    }

    private void read(Path file, Why why, boolean focus) {
        if (doc.entry(file) != null || !reading.add(file)) {
            if (why == Why.RESTORE_FILE) {
                next();
            }
            return;
        }
        bus.publish(Messages.READ_TOPIC, new Read(file, why, focus));
    }

    /** What {@link ReadFile} made of a file: a new tab, or a reason on the status line. */
    @Subscribe(topic = Messages.FILE_READ)
    public void read(FileRead r) {
        reading.remove(r.path());
        boolean restoring = r.why() == Why.RESTORE_FILE;
        String name = String.valueOf(r.path().getFileName());
        if (!r.problem().isEmpty()) {
            if (restoring) {
                LOG.info("not restoring {}: {}", r.path(), r.problem());
            } else {
                refuse("Could not open " + name + ": " + r.problem());
            }
        } else if (doc.entry(r.path()) == null) {
            addTab(r.path(), r.text(), r.crlf(), r.focus());
            say(r.notes().isEmpty() ? "Opened " + name : "Opened " + name + " (" + r.notes() + ")");
        }
        if (restoring) {
            next();
        }
    }

    /** What {@link FindPath} found at a path, acted on for whoever asked. */
    @Subscribe(topic = Messages.FOUND)
    public void found(Found f) {
        if (f.why() == Why.RESTORE_FOLDER) {
            showFolder(f.kind() == Kind.FOLDER ? f.path() : Path.of("").toAbsolutePath());
            next();
        } else if (f.why() == Why.ARGUMENT) {
            if (f.kind() == Kind.FILE) {
                read(f.path(), Why.RESTORE_FILE, true);
                return;
            }
            if (f.kind() == Kind.FOLDER) {
                showFolder(f.path());
            }
            next();
        } else if (f.kind() == Kind.FOLDER) {
            showFolder(f.path());
        } else if (f.kind() == Kind.FILE) {
            open(new Open(f.path(), true));
        } else if (f.kind() == Kind.NEW) {
            bus.publish(Messages.CREATE_TOPIC, new Create(f.path()));
        } else {
            refuse("No such folder: " + f.path().getParent());
        }
    }

    /** A file the path bar named into being: list the folder again, and open it empty. */
    @Subscribe(topic = Messages.CREATED)
    public void created(Created c) {
        String name = String.valueOf(c.path().getFileName());
        if (!c.problem().isEmpty()) {
            refuse("Could not create " + name + ": " + c.problem());
            return;
        }
        if (doc.folder() != null) {
            bus.publish(Messages.SHOW_FOLDER_TOPIC, new ShowFolder(doc.folder()));
        }
        addTab(c.path(), "", System.lineSeparator().equals("\r\n"), true);
        say("Created " + name);
    }

    private void addTab(Path path, String text, boolean crlf, boolean focus) {
        long id = ++ids;
        doc = doc.withTab(new Doc.Entry(id, path, false, crlf)).withActive(id);
        bus.publish(Messages.ADD_TAB_TOPIC, new AddTab(id, path, text, focus));
        caption();
        remember();
    }

    private void frontTab(long id, boolean focus) {
        doc = doc.withActive(id);
        bus.publish(Messages.FRONT_TAB_TOPIC, new FrontTab(id, focus));
        caption();
        remember();
    }

    private void showFolder(Path folder) {
        doc = doc.withFolder(folder);
        bus.publish(Messages.SHOW_FOLDER_TOPIC, new ShowFolder(folder));
        remember();
    }

    // ------------------------------------------------------------------ what the view reports

    @Subscribe(topic = Messages.FRONTED)
    public void fronted(Fronted f) {
        if (doc.entry(f.id()) != null && doc.active() != f.id()) {
            doc = doc.withActive(f.id());
            caption();
            remember();
        }
    }

    @Subscribe(topic = Messages.EDITED)
    public void edited(Edited e) {
        Doc.Entry entry = doc.entry(e.id());
        if (entry == null || entry.dirty() == e.dirty()) {
            return;
        }
        doc = doc.withEntry(e.id(), x -> x.withDirty(e.dirty()));
        bus.publish(Messages.RETITLE_TOPIC, new Retitle(e.id(), doc.entry(e.id()).title()));
        if (doc.active() == e.id()) {
            caption();
        }
    }

    // ------------------------------------------------------------------ saving

    /** Write a tab's text, whether the view sent it of its own accord (Ctrl+S) or because a close asked for it. */
    @Subscribe(topic = Messages.SAVE)
    public void save(Save s) {
        Doc.Entry entry = doc.entry(s.id());
        if (!s.open() || entry == null) {
            // The tab went while its text was asked for: there is nothing left to write, and nothing lost by not.
            landed(s.id());
            return;
        }
        bus.publish(Messages.WRITE_TOPIC, new Write(s.id(), entry.path(), s.text(), entry.crlf()));
    }

    @Subscribe(topic = Messages.WRITTEN)
    public void written(Written w) {
        String name = String.valueOf(w.path().getFileName());
        if (!w.problem().isEmpty()) {
            bus.publish(Messages.SAVE_FAILED_TOPIC, new SaveFailed(w.id()));
            refuse("Could not save " + name + ": " + w.problem());
            Saving run = saving.remove(w.id());
            if (run != null) {
                run.failed();
            }
            return;
        }
        say("Saved " + name);
        landed(w.id());
    }

    private void landed(long id) {
        Saving run = saving.remove(id);
        if (run != null) {
            run.landed(id);
        }
    }

    /** Save each of {@code ids}: {@code done} once all have landed, {@code failed} on the first that does not. */
    private void saveAll(List<Long> ids, Runnable done, Runnable failed) {
        Saving run = new Saving(new LinkedHashSet<>(ids), done, failed);
        for (long id : ids) {
            saving.put(id, run);
            bus.publish(Messages.FLUSH_TOPIC, new Flush(id));
        }
    }

    /** One "save these, then" in flight: which have still to land, and exactly one of its two endings, once. */
    private final class Saving {
        private final Set<Long> waiting;
        private final Runnable done;
        private final Runnable failed;
        private boolean over;

        Saving(Set<Long> waiting, Runnable done, Runnable failed) {
            this.waiting = waiting;
            this.done = done;
            this.failed = failed;
        }

        void landed(long id) {
            waiting.remove(id);
            if (waiting.isEmpty() && !over) {
                over = true;
                done.run();
            }
        }

        void failed() {
            if (over) {
                return;
            }
            over = true;
            for (long id : waiting) {
                saving.remove(id, this);
            }
            failed.run();
        }
    }

    // ------------------------------------------------------------------ closing and quitting

    private record Batch(long batch, Purpose purpose) {
    }

    /**
     * One tab of a set being closed or quit. Nothing is decided until the whole set is here, because they come one
     * message each and in no promised order; then the set is decided at once, with one question for all its unsaved
     * tabs.
     */
    @Subscribe(topic = Messages.CLOSING, capacity = SET)
    public void closing(Closing c) {
        Batch key = new Batch(c.batch(), c.purpose());
        List<Closing> set = closing.computeIfAbsent(key, k -> new ArrayList<>());
        set.add(c);
        if (set.size() < c.of()) {
            return;
        }
        closing.remove(key);
        List<Long> all = set.stream().map(Closing::id).filter(id -> id != Doc.NONE).toList();
        List<Long> unsaved = set.stream().filter(x -> x.dirty() && x.id() != Doc.NONE).map(Closing::id).toList();
        if (c.purpose() == Purpose.QUIT) {
            quit(c.batch(), unsaved);
        } else {
            closeTabs(all, unsaved);
        }
    }

    private void closeTabs(List<Long> all, List<Long> unsaved) {
        Runnable closeThem = () -> all.forEach(this::closeTab);
        if (unsaved.isEmpty()) {
            closeThem.run();
            return;
        }
        if (all.size() == 1) {
            ask("Unsaved changes", "Save the changes to " + names(unsaved) + " before closing it?",
                    "Save", "Don't save",
                    () -> saveAll(unsaved, closeThem, () -> { }), closeThem, () -> { });
        } else {
            ask("Unsaved changes", unsavedMessage(unsaved), "Save all", "Discard",
                    () -> saveAll(unsaved, closeThem, () -> { }), closeThem, () -> { });
        }
    }

    /**
     * The close gate's question. The window is still open and drawing while it is unanswered, and every path through
     * it answers the gate exactly once.
     */
    private void quit(long gate, List<Long> unsaved) {
        Runnable proceed = () -> bus.publish(Messages.QUIT_ANSWER_TOPIC, new QuitAnswer(gate, true));
        Runnable cancel = () -> bus.publish(Messages.QUIT_ANSWER_TOPIC, new QuitAnswer(gate, false));
        if (unsaved.isEmpty()) {
            proceed.run();
            return;
        }
        ask("Quit with unsaved changes?", unsavedMessage(unsaved), "Save all", "Discard",
                () -> saveAll(unsaved, proceed, cancel), proceed, cancel);
    }

    private void closeTab(long id) {
        if (doc.entry(id) == null) {
            return;
        }
        doc = doc.without(id);
        bus.publish(Messages.CLOSE_TAB_TOPIC, new CloseTab(id));
        caption();
        remember();
    }

    private String names(List<Long> ids) {
        return ids.stream().map(doc::entry).filter(Objects::nonNull).map(Doc.Entry::name)
                .collect(Collectors.joining(", "));
    }

    private String unsavedMessage(List<Long> unsaved) {
        return unsaved.size() == 1
                ? names(unsaved) + " has unsaved changes."
                : unsaved.size() + " files have unsaved changes: " + names(unsaved) + ".";
    }

    // ------------------------------------------------------------------ questions

    /** What each of a question's three answers does. */
    private record Choices(Runnable confirm, Runnable other, Runnable cancel) {
    }

    private void ask(String title, String message, String confirm, String other,
                     Runnable onConfirm, Runnable onOther, Runnable onCancel) {
        long q = ++questions;
        asked.put(q, new Choices(onConfirm, onOther, onCancel));
        bus.publish(Messages.ASK_TOPIC, new Ask(q, title, message, confirm, other));
    }

    @Subscribe(topic = Messages.ANSWER)
    public void answer(Answer a) {
        Choices c = asked.remove(a.question());
        if (c == null) {
            return;
        }
        if (a.choice() == Messages.Choice.CONFIRM) {
            c.confirm().run();
        } else if (a.choice() == Messages.Choice.OTHER) {
            c.other().run();
        } else {
            c.cancel().run();
        }
    }

    // ------------------------------------------------------------------ the status line and the caption

    private void say(String message) {
        bus.publish(Messages.STATUS_TOPIC, new Status(message, false));
    }

    /** Say why on the status line, and draw the eye to it. A refusal is not worth a dialog. */
    private void refuse(String why) {
        bus.publish(Messages.STATUS_TOPIC, new Status(why, true));
    }

    private void caption() {
        Doc.Entry f = doc.front();
        bus.publish(Messages.CAPTION_TOPIC, new Caption(f == null ? "" : f.title()));
    }
}

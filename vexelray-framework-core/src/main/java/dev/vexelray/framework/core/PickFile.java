package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

import java.util.List;

/**
 * <b>Ask the person for a file or a folder</b>, through the operating system's own picker, parented to the main
 * window. The framework publishes the {@link Picked} with the same {@code id}: the path, or none when the person
 * cancelled or the picker could not be shown, so whoever asked is never left waiting.
 *
 * <p>A path is a string here, not a {@code Path}, because a message crosses lanes and has to be a value that shares
 * nothing; {@code Path.of} takes it back.
 *
 * @param id      what the answer carries; the asker's, as an {@link Ask}'s is
 * @param kind    a file to open, a folder, or a place to save
 * @param start   the folder the picker starts in, or empty for wherever the OS likes
 * @param name    the file name filled in, for {@link Kind#SAVE}; empty otherwise
 * @param filters what it offers, empty for any file; ignored for {@link Kind#FOLDER}
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record PickFile(long id, Kind kind, String start, String name, List<Filter> filters) {

    public static final Topic<PickFile> TOPIC = Topic.edge(PickFile.class);

    public PickFile {
        filters = List.copyOf(filters);
    }

    /** A file to open, starting in {@code start} (empty for the OS's choice), offering {@code filters}. */
    public static PickFile open(long id, String start, List<Filter> filters) {
        return new PickFile(id, Kind.OPEN, start, "", filters);
    }

    /** A folder, starting in {@code start}. */
    public static PickFile folder(long id, String start) {
        return new PickFile(id, Kind.FOLDER, start, "", List.of());
    }

    /** Where to save, starting in {@code start} with {@code name} filled in, offering {@code filters}. */
    public static PickFile save(long id, String start, String name, List<Filter> filters) {
        return new PickFile(id, Kind.SAVE, start, name, filters);
    }

    /** Hand this request to the {@code sink} method for its kind. */
    public <R> R to(Sink<R> sink) {
        return kind.to(sink, this);
    }

    public enum Kind {
        OPEN {
            @Override
            <R> R to(Sink<R> sink, PickFile p) {
                return sink.open(p);
            }
        },
        FOLDER {
            @Override
            <R> R to(Sink<R> sink, PickFile p) {
                return sink.folder(p);
            }
        },
        SAVE {
            @Override
            <R> R to(Sink<R> sink, PickFile p) {
                return sink.save(p);
            }
        };

        abstract <R> R to(Sink<R> sink, PickFile p);
    }

    /** What runs a picker: one method per {@link Kind}, so a new kind is a compile error in every one. */
    public interface Sink<R> {

        R open(PickFile request);

        R folder(PickFile request);

        R save(PickFile request);
    }

    /**
     * One entry of a picker's type list.
     *
     * @param description what the person reads, "Text files"
     * @param extensions  without the dot, {@code ["txt", "md"]}
     */
    public record Filter(String description, List<String> extensions) {

        public Filter {
            extensions = List.copyOf(extensions);
        }
    }
}

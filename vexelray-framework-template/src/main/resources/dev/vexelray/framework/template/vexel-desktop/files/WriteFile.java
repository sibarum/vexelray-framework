package ${packageName}.core;

import ${packageName}.core.Messages.Write;
import ${packageName}.core.Messages.Written;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/**
 * Write a file with its own line endings, on the offload lane. A write that does not land is an answer
 * ({@code problem}), and the {@link Editor} cancels whatever was waiting on it.
 */
@Job
public final class WriteFile {

    private static final Log LOG = Log.of("${appName}.files");

    public Written run(Write w) {
        try {
            TextFile.write(w.path(), TextFile.encode(w.text(), w.crlf()));
            return new Written(w.id(), w.path(), "");
        } catch (IOException e) {
            LOG.warn("could not save {}", w.path(), e);
            return new Written(w.id(), w.path(), String.valueOf(e.getMessage()));
        }
    }
}

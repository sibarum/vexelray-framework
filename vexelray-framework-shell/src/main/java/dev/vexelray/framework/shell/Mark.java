package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.os.Icon;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Which mark the application wears, decoded: the one it handed back, else the one it named, else the framework's.
 *
 * <p><b>Never nothing, unless something failed.</b> An application that names no mark wears {@code new-app.ico}, the
 * template's — the same file a generated project's executable links — rather than the OS default. A window under the
 * OS default is indistinguishable from every other program that forgot, and on Windows the default is not even the
 * executable's own icon: the window class is registered without one. Each failure is reported once and falls to the
 * next, because a mark is cosmetic and must never take a launch down with it.
 *
 * <p>Decoded with {@link Icon#fromIco} and {@link Icon#fromPng}, which read the formats with {@code java.base} alone,
 * so a native image of the application carries no AWT for its icon.
 */
final class Mark {

    /** The framework's own mark, beside this class. Registered for native-image in this module's metadata. */
    static final String FRAMEWORK = "new-app.ico";

    private Mark() {
    }

    /** The mark to wear, or {@code null} if even the framework's could not be read. */
    static Icon of(Wiring wiring, AppInfo info) {
        if (info.icon() != null) {
            return info.icon();
        }
        String named = wiring.icon();
        if (named != null) {
            Icon icon = load(wiring.getClass(), named, "the application's icon " + named);
            if (icon != null) {
                return icon;
            }
        }
        return load(Mark.class, FRAMEWORK, "the framework's icon");
    }

    /** {@code name} beside {@code anchor}, decoded by its extension; {@code null}, reported, if it cannot be. */
    static Icon load(Class<?> anchor, String name, String what) {
        try (InputStream in = anchor.getResourceAsStream(name)) {
            if (in == null) {
                Diagnostics.dropped("Mark.load", what, "no resource " + name + " beside " + anchor.getName()
                        + "; its windows wear the next mark instead");
                return null;
            }
            byte[] bytes = in.readAllBytes();
            return name.toLowerCase(Locale.ROOT).endsWith(".png") ? Icon.fromPng(bytes) : Icon.fromIco(bytes);
        } catch (IOException | IllegalArgumentException e) {
            Diagnostics.dropped("Mark.load", what, e + "; its windows wear the next mark instead");
            return null;
        }
    }
}

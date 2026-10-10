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

    /**
     * The mark the application's <em>other</em> windows wear: the suite's window icon, a band of the application's
     * hue across the top and its glyph shrunk under it, so a viewer or a settings window is told apart from the
     * application at a glance and still reads as its own.
     *
     * <p>Found by name rather than declared: the variant of {@code pix.ico} is {@code pix-window.ico} beside it, as
     * the suite canvas exports {@code pix-window.svg} beside {@code pix.svg}. An application without one — every
     * application that is not a suite one, and every mark built in code — wears {@code primary} in every window,
     * which is not a failure and so is not reported. A variant that is there and cannot be read is.
     */
    static Icon window(Wiring wiring, AppInfo info, Icon primary) {
        String named = wiring.icon();
        if (info.icon() != null || named == null) {
            return primary;
        }
        String variant = windowVariant(named);
        if (variant == null || wiring.getClass().getResource(variant) == null) {
            return primary;
        }
        Icon icon = load(wiring.getClass(), variant, "the application's window icon " + variant);
        return icon != null ? icon : primary;
    }

    /** {@code pix.ico} to {@code pix-window.ico}, keeping any path; {@code null} for a name with no extension. */
    static String windowVariant(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= name.lastIndexOf('/') + 1 ? null : name.substring(0, dot) + "-window" + name.substring(dot);
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

package dev.vexelray.framework.shell;

import dev.vexelray.os.Icon;

import java.util.Set;

/**
 * The facts from {@code @VexelApp}, carried as values rather than read from an annotation.
 *
 * <p>The generated wiring returns one of these from a constant, so nothing reads the annotation at runtime.
 * That is the point: the annotation is a compile-time instruction, and this record is what it compiles to.
 *
 * @param name        the settings directory, {@code $HOME/.{name}/} — stable across releases, or the user loses
 *                    their window placement
 * @param title       the main window's title
 * @param width       first-run width, in the engine's logical coordinates
 * @param height      first-run height
 * @param settingKeys every {@code @Setting} key the application declares, so an unknown {@code --flag} can be
 *                    refused by name with the alternatives listed. A generated wiring fills this in from every
 *                    {@code @Setting} it binds, so it cannot fall out of date with the code that reads them;
 *                    see {@link #settingKeys()} for what that costs a hand-written one
 * @param icon        the mark this application wears, or {@code null} to leave every window under the OS
 *                    default. See {@link #icon()} for why identity belongs here and not in {@link Appearance}
 */
public record AppInfo(String name, String title, int width, int height, Set<String> settingKeys, Icon icon) {

    public AppInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("@VexelApp needs a name: it is the settings directory");
        }
        settingKeys = Set.copyOf(settingKeys == null ? Set.of() : settingKeys);
    }

    /** For an application that declares no settings of its own. */
    public AppInfo(String name, String title, int width, int height) {
        this(name, title, width, height, Set.of(), null);
    }

    /** For an application with settings and no mark of its own. */
    public AppInfo(String name, String title, int width, int height, Set<String> settingKeys) {
        this(name, title, width, height, settingKeys, null);
    }

    /**
     * The same facts, wearing {@code icon}.
     *
     * <p>Separate from the constructors because decoding a mark reads resources, and an application that wants
     * its {@code INFO} to be a constant should not have to do that in a static initialiser it cannot report a
     * failure from.
     */
    public AppInfo withIcon(Icon icon) {
        return new AppInfo(name, title, width, height, settingKeys, icon);
    }

    /**
     * Every {@code @Setting} key this application declares.
     *
     * <p><b>Generated, or hand-written, and the difference is the one thing worth knowing about it.</b>
     * {@code Launch} says of this list that it <i>"cannot fall out of date, because it is not written by
     * anybody"</i> — which is true of a generated wiring, where the processor lists every {@code @Setting} key
     * it binds, and not of a hand-written one, which types the set out. {@code --terminal} worked in
     * {@code text-editor-vexel-demo} because somebody remembered to put {@code "terminal"} in it.
     *
     * <p>So in a hand-written wiring the failure the mechanism exists to make impossible is only unlikely — and
     * it is quiet in both directions. A key left out means a real flag refused as a typo. A key left behind after the
     * {@code @Setting} that justified it is gone means a flag accepted and then ignored, which is the exact
     * failure {@link dev.vexelray.framework.core.Launch#FRAMEWORK_KEYS} is careful about. Neither shows up in
     * a test that did not think to look, which is why this is the sharpest single argument for the processor
     * and the first thing that will rot in a hand-written wiring.
     */
    @Override
    public Set<String> settingKeys() {
        return settingKeys;
    }

    /**
     * The mark, or {@code null}.
     *
     * <p><b>Identity, not appearance</b>, which is why it sits beside the title rather than in
     * {@link Appearance}. {@code vexelray-gui/docs/automation.md} §7 draws that line: an application
     * contributes <i>"identity — its title, an icon as identity"</i> — and the framework, which owns the
     * window, is what puts both on it.
     *
     * <p><b>Said twice, deliberately.</b> The framework hands this to
     * {@code NativePlatform.setApplicationIcon} before the first window exists — the mark of the
     * <em>process</em>, which is what any window naming none falls back to — and <em>also</em> names it on the
     * main window's own config. That is redundant for exactly as long as the application is the process, and
     * the text editor's {@code AppIcon} records why it stops being so:
     *
     * <blockquote>Run inside MainFrame it is not: the process is MainFrame, so the fallback is MainFrame's
     * mark, and a window relying on it would be indistinguishable from the shell wherever windows are
     * listed.</blockquote>
     *
     * <p>Every application on this stack that wears a mark had that paragraph in it. Now the framework does.
     */
    @Override
    public Icon icon() {
        return icon;
    }
}

package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The application's About window</b>, opened from the mark at the left of every title bar: the application's own
 * mark, its proper name and coordinates, its version, the commit it was built from and when, its third-party notices
 * and the framework's issues; and, across the foot, the VexelRay mark and the framework it was built on.
 *
 * <p><b>Everything on it is a fact of the build</b>, not a string an application keeps in step by hand: the
 * {@code vexelray-build-maven-plugin}'s bill, baked into the wiring as {@link BuildInfo}. What the bill does not know,
 * the window says it does not know, rather than the framework guessing; the one fallback is the name, which is the
 * {@code @VexelApp} title when the bill names none.
 *
 * <p><b>The two marks condense as it opens</b> ({@link AboutIntro}): the orb on the VexelRay mark lands, and both
 * marks precipitate out of their distance fields behind a front spreading from where it landed, the words arriving as
 * the front passes them. The marks are art pairs baked at build time (the icon plugin's {@code pair} goal): the
 * framework's from {@code src/main/rc/vexelray-logo.svg}, the application's from the drawing its icon was made from,
 * found by its icon's name. An application without one shows its icon instead.
 *
 * <p><b>Every application has one, and one only.</b> The framework declares it as a named window, {@value #KEY}, at the
 * end of {@code TREE}, after the application's own declarations, so an application that declared an {@value #KEY} window
 * of its own keeps it, and the mark opens that one instead. An application asking for the OS frame has no title bar to
 * click, and gets none.
 */
final class About {

    /** The window's name: in the settings file, where its placement is remembered, and on the automation socket. */
    static final String KEY = "about";

    /** The application's mark, which no zoom changes, so the art is drawn at the pixels its pair was baked for. */
    static final Length MARK = Length.dp(104);

    /** The VexelRay mark in the foot. */
    static final Length SIGNATURE = Length.dp(52);

    /** Each button's side. */
    private static final Length TILE = Length.rem(4.25f);

    /**
     * The narrowest the page is laid out: the three buttons side by side, the page's padding, and room for a scrollbar
     * down the side when the window is short as well.
     * Narrower, the window shows part of the page and scrolls as a whole, rather than one row scrolling sideways on its own.
     */
    private static final Length MIN_WIDTH = Length.em(21f);

    /**
     * Where an installed application's notices lie: beside its executable, as {@code vexelray-dist}'s installer puts
     * them, under the name the bill's {@code notices} address ends with ({@code vexelray-build}'s
     * {@code InstallerJson.NOTICES}). Opened before the address, so they read without a network.
     */
    static final String NOTICES_FILE = "THIRD-PARTY-NOTICES.txt";

    private static final DateTimeFormatter BUILT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    /**
     * What {@link #content} lays out, by the parts the intro moves: the page, which scrolls, and the foot under it, which
     * does not; the two marks; everything that arrives as the front passes it; and how to put the page back as it was
     * before it was used, for the next opening.
     */
    record Page(Node body, Node foot, Node mark, Node signature, List<Node> arrivals, Runnable reset) {
    }

    private final Window window;
    /** The intro; null when the window is the application's own. */
    private final AboutIntro intro;

    private About(Window window, AboutIntro intro) {
        this.window = window;
        this.intro = intro;
    }

    /** The window the mark opens. */
    Window window() {
        return window;
    }

    /**
     * Declare the About window and build its tree, or take up the one the application declared under {@value #KEY}.
     * At the end of {@code TREE}, when the application's declarations are all in; null without a framework title bar.
     * {@code icon} is the resource the application's mark is, if it names one: its art pair is found beside it.
     */
    static About declare(Shell shell, AppInfo info, BuildInfo build, Class<?> anchor, String icon) {
        if (!shell.appearance().drawsOwnFrame()) {
            return null;
        }
        Windows windows = shell.windows();
        for (Window w : windows.declared()) {
            if (w.key().equals(KEY)) {
                return new About(w, null);
            }
        }
        String name = name(info, build);
        Window window = windows.named(NamedWindow.of(KEY, "About " + name, 440, 560).satellite());
        Gui g = window.gui();
        minimum(g);

        Node mark = g.box().role("about-mark").size(MARK, MARK).scroll(false, false);
        Page page = content(g, name, build, mark);
        TitleBar bar = window.titleBar();
        g.root().background(Role.PAGE).children(bar.node(), page.body(), page.foot());
        // Esc puts it away, as it does a dialog: there is nothing on it to lose.
        g.claim(g.root(), Shortcut.of(Key.ESCAPE), ClaimScope.VISIBLE, window::close);

        AboutIntro intro = new AboutIntro(g, shell.krono(), page, anchor, icon);
        window.onOpened(intro::play);
        return new About(window, intro);
    }

    /** The narrowest {@code g} is laid out; see {@link #MIN_WIDTH}. */
    static void minimum(Gui g) {
        g.minSize(MIN_WIDTH, Length.ZERO);
    }

    /**
     * Everything under the title bar, in {@code g}, round the application's {@code mark}: separate from
     * {@link #declare} so a test can lay it out with a bill of its own choosing, which is the only way to see it with
     * values longer than any real build has yet made.
     */
    static Page content(Gui g, String name, BuildInfo build, Node mark) {
        List<Node> arrivals = new ArrayList<>();
        // Every text here is as wide as the window and wraps inside it, because none of it has a width of its own: a
        // name, a version and a source are the build's, as long as the build makes them, and a line that can only
        // widen is a horizontal scrollbar the first time one is long ("+ uncommitted changes" was).
        Node title = line(g, name).role("about-name").textSize(Length.rem(1.5f)).fontWeight(600).textColor(Role.INK);
        Node coordinates = line(g, build.coordinates()).role("about-coordinates").textColor(Role.DIM)
                .font("mono").textSize(Length.rem(0.8f));
        arrivals.add(title);
        arrivals.add(coordinates);

        Tooltip tips = new Tooltip(g);
        Node facts = g.column().role("about-facts").width(Length.FILL).gap(Length.rem(0.2f))
                .padding(Length.rem(0.4f), Length.ZERO);
        for (Map.Entry<String, String> fact : facts(build).entrySet()) {
            boolean dirtyBuild = fact.getKey().equals("Build") && build.known() && build.dirty();
            Node value = g.text(dirtyBuild ? shortCommit(build) : fact.getValue())
                    .role("about-" + fact.getKey().toLowerCase(java.util.Locale.ROOT))
                    .textColor(Role.INK);
            if (fact.getKey().equals("Build") && build.known()) {
                // The short commit reads; the exact source, digest and all, is what a bug report wants.
                tips.attach(value, build.source());
                value.font("mono");
            }
            Node cell = value.width(Length.grow(1f));
            if (dirtyBuild) {
                // That the build was not all committed is said under the commit, in a word that stands out, rather
                // than as a tail on it: a dirty build is the first thing a bug report wants to know. Under rather than
                // beside, so a long commit and the word never need more than the column's width between them.
                value.width(Length.FILL);
                Node chip = g.text("uncommitted").role("about-uncommitted").textSize(Length.rem(0.6875f))
                        .fontWeight(600).textColor(Role.CAUTION).border(Length.dp(1), Role.CAUTION)
                        .corner(Length.rem(0.6f)).padding(Length.ZERO, Length.rem(0.45f));
                cell = g.column().width(Length.grow(1f)).gap(Length.rem(0.25f)).alignItems(AlignItems.START)
                        .children(value, chip);
            }
            // The labels take a share of the row rather than a width, so the gutter between label and value stays
            // near the middle of the window however wide it is.
            Node row = g.row().width(Length.FILL).gap(Length.rem(0.8f)).children(
                    g.text(fact.getKey()).width(Length.percent(38f)).textColor(Role.DIM)
                            .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.TOP),
                    cell);
            facts.append(row);
            arrivals.add(row);
        }

        String notices = notices(build.notices());
        IconButton licenses = tile(g, Icon.FILE, "Licenses", "Third-party licenses", "about-licenses")
                .enabled(!notices.isEmpty()).onPress(() -> Links.open(notices));
        String issues = build.frameworkIssues().orElse("");
        IconButton report = tile(g, Icon.CAUTION, "Issues", "Framework issues", "about-issues")
                .enabled(!issues.isEmpty()).onPress(() -> Links.open(issues));
        IconButton copy = tile(g, Icon.COPY, "Copy info", "Copy the version information, for a bug report",
                "about-copy");
        // It says it did it in its own word, until the window is next opened: no line of its own that would be empty
        // the rest of the time.
        copy.onPress(() -> {
            g.clipboard().set(summary(name, build));
            copy.name("Copied");
        });
        Node tiles = g.row().role("about-actions").gap(Length.rem(0.75f)).justify(Justify.CENTER)
                .padding(Length.rem(0.3f), Length.ZERO)
                .children(licenses.node(), report.node(), copy.node());
        arrivals.add(tiles);

        Node hero = g.column().width(Length.FILL).alignItems(AlignItems.CENTER)
                .padding(Length.rem(0.6f), Length.rem(1.5f))
                .gap(Length.rem(0.25f))
                .children(mark, title, coordinates, facts, tiles);

        // The foot: the framework's mark and name, signing the page as a maker's mark does, on a band of its own that
        // stays at the bottom of the window while the page above it scrolls, so the mark the orb lands on is always in view.
        Node signature = g.box().role("about-signature").size(SIGNATURE, SIGNATURE).scroll(false, false);
        String framework = frameworkVersion(build);
        // The words take the rest of the band and wrap in it, so a long version never widens the page; the lockup sits
        // at the band's left, under the page as a maker's mark sits under a label.
        Node words = g.column().width(Length.grow(1f)).gap(Length.rem(0.15f)).children(
                g.text("VEXELRAY").role("about-wordmark").width(Length.FILL).font("mono").fontWeight(600)
                        .textSize(Length.rem(1.05f)).textColor(Role.INK));
        if (framework != null) {
            words.append(g.text("Framework " + framework).role("about-framework").width(Length.FILL).font("mono")
                    .textSize(Length.rem(0.7f)).textColor(Role.FAINT));
        }
        // It never scrolls, so it does not clip: the rings round its mark rise over the page as the mark condenses.
        Node foot = g.row().role("about-foot").width(Length.FILL).background(Role.WELL).scroll(false, false)
                .alignItems(AlignItems.CENTER).gap(Length.rem(0.9f)).padding(Length.rem(0.6f), Length.rem(1.5f))
                .children(signature, words);
        arrivals.add(words);

        Node body = g.column().role("about-page").width(Length.FILL).height(Length.grow(1f))
                .alignItems(AlignItems.CENTER).scroll(false, true).children(hero);
        return new Page(body, foot, mark, signature, List.copyOf(arrivals), () -> copy.name("Copy info"));
    }

    /**
     * Read the art now there is a device: {@code ATTACH}, on the main thread. Nothing is read or uploaded until the
     * window first opens; {@code mark} is the application's icon, shown when it has no art pair.
     */
    void attach(GuiApp app, dev.vexelray.os.Icon mark) {
        if (intro != null) {
            intro.attach(app, mark);
        }
    }

    /** What the window calls the application: the bill's proper name, else its {@code @VexelApp} title. */
    static String name(AppInfo info, BuildInfo build) {
        if (!build.name().isBlank()) {
            return build.name();
        }
        return info.title() == null || info.title().isBlank() ? info.name() : info.title();
    }

    /** The rows under the name, in order, each saying {@code unknown} rather than nothing when the bill did not know. */
    static Map<String, String> facts(BuildInfo build) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("Version", "-".equals(build.version()) ? "unknown" : build.version());
        facts.put("Build", !build.known() ? "unknown"
                : shortCommit(build) + (build.dirty() ? " + uncommitted changes" : ""));
        facts.put("Built", build.builtAt().map(t -> BUILT.format(t.atZone(ZoneId.systemDefault()))).orElse("unknown"));
        return facts;
    }

    /** The commit, to its first twelve characters. */
    private static String shortCommit(BuildInfo build) {
        String commit = build.commit();
        return commit.substring(0, Math.min(12, commit.length()));
    }

    /** What Copy info puts on the clipboard: everything the window says, as lines a bug report can take whole. */
    static String summary(String name, BuildInfo build) {
        StringBuilder s = new StringBuilder(name).append(" (").append(build.coordinates()).append(")\n");
        for (Map.Entry<String, String> fact : facts(build).entrySet()) {
            s.append(fact.getKey()).append(": ").append(fact.getValue()).append('\n');
        }
        if (build.known()) {
            s.append("Source: ").append(build.source()).append('\n');
        }
        String framework = frameworkVersion(build);
        if (framework != null) {
            s.append("VexelRay Framework ").append(framework).append('\n');
        }
        return s.toString();
    }

    /** The framework's version, from its line in the stack; null when it is not stamped. */
    static String frameworkVersion(BuildInfo build) {
        String prefix = BuildInfo.FRAMEWORK + ":";
        for (String part : build.stack()) {
            if (part.startsWith(prefix)) {
                int at = part.indexOf('@', prefix.length());
                return part.substring(prefix.length(), at < 0 ? part.length() : at);
            }
        }
        return null;
    }

    /** The notices to open: the installed copy beside the executable when there is one, else {@code address}. */
    static String notices(String address) {
        Path beside = ProcessHandle.current().info().command()
                .map(c -> Path.of(c).resolveSibling(NOTICES_FILE)).orElse(null);
        return beside != null && Files.isRegularFile(beside) ? beside.toString() : address;
    }

    /** One of the three square buttons: an icon over its word, the tip saying it in full. */
    private static IconButton tile(Gui g, Icon icon, String word, String tip, String landmark) {
        IconButton b = new IconButton(g, icon, word).tile(TILE, Length.rem(1.4f)).ink(Role.INK).tip(tip);
        b.node().role(landmark);
        g.landmark(landmark, b.node());
        return b;
    }

    /** A centred line as wide as the body, wrapping when what it says is wider. */
    private static Node line(Gui g, String s) {
        return g.text(s).width(Length.FILL).align(TextLayout.HAlign.CENTER, TextLayout.VAlign.TOP);
    }
}

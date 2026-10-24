package dev.vexelray.framework.shell;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.os.Icon;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.SampledImage;
import sibarum.tactroller.api.Key;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The application's About window</b>, opened from the mark at the left of every title bar: the VexelRay logo, the
 * application's proper name and coordinates, its version, the commit it was built from and when, and links to its
 * third-party notices and to the framework's issues.
 *
 * <p><b>Everything on it is a fact of the build</b>, not a string an application keeps in step by hand: the
 * {@code vexelray-build-maven-plugin}'s bill, baked into the wiring as {@link BuildInfo}. What the bill does not know,
 * the window says it does not know, rather than the framework guessing; the one fallback is the name, which is the
 * {@code @VexelApp} title when the bill names none.
 *
 * <p><b>Every application has one, and one only.</b> The framework declares it as a named window, {@value #KEY}, at the
 * end of {@code TREE}, after the application's own declarations, so an application that declared an {@value #KEY} window
 * of its own keeps it, and the mark opens that one instead. An application asking for the OS frame has no title bar to
 * click, and gets none.
 */
final class About {

    /** The window's name: in the settings file, where its placement is remembered, and on the automation socket. */
    static final String KEY = "about";

    /** The logo's side, and the sides it was rendered at for 100 % to 400 % ({@code vex-suite-common/tools/Png.java}). */
    private static final Length LOGO = Length.dp(96);
    private static final int[] LOGO_SIDES = {96, 192, 288, 384};

    /**
     * Where an installed application's notices lie: beside its executable, as {@code vexelray-dist}'s installer puts
     * them, under the name the bill's {@code notices} address ends with ({@code vexelray-build}'s
     * {@code InstallerJson.NOTICES}). Opened before the address, so they read without a network.
     */
    static final String NOTICES_FILE = "THIRD-PARTY-NOTICES.txt";

    private static final DateTimeFormatter BUILT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final Window window;
    /** Where the logo is drawn; null when the window is the application's own. */
    private final Node logo;
    /** The logo's box as the layout last settled it, for choosing the image nearest its pixels. */
    private volatile Rect logoBox;
    /** The logo at each side, uploaded at {@link #attach}; null until then, or if it could not be read. */
    private volatile Map<Icon.Image, SampledImage> logoSizes;
    private volatile Icon logoIcon;

    private About(Window window, Node logo) {
        this.window = window;
        this.logo = logo;
    }

    /** The window the mark opens. */
    Window window() {
        return window;
    }

    /**
     * Declare the About window and build its tree, or take up the one the application declared under {@value #KEY}.
     * At the end of {@code TREE}, when the application's declarations are all in; null without a framework title bar.
     */
    static About declare(Shell shell, AppInfo info, BuildInfo build) {
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
        Window window = windows.named(NamedWindow.of(KEY, "About " + name, 440, 420).satellite());
        Gui g = window.gui();

        Node logo = g.box().role("about-logo").size(LOGO, LOGO).scroll(false, false);
        About about = new About(window, logo);
        g.onResizeUi(logo, computed -> {
            about.logoBox = computed.rect();
            about.showLogo();
        });

        Node title = text(g, name).role("about-name").textSize(Length.rem(1.5f)).fontWeight(600).textColor(Role.INK);
        Node coordinates = text(g, build.coordinates()).role("about-coordinates").textColor(Role.DIM);

        Tooltip tips = new Tooltip(g);
        Node facts = g.column().role("about-facts").gap(Length.rem(0.3f)).margin(Length.rem(0.6f));
        for (Map.Entry<String, String> fact : facts(build).entrySet()) {
            Node value = text(g, fact.getValue()).role("about-" + fact.getKey().toLowerCase(java.util.Locale.ROOT))
                    .textColor(Role.INK);
            if (fact.getKey().equals("Build") && build.known()) {
                // The short commit reads; the exact source, digest and all, is what a bug report wants.
                tips.attach(value, build.source());
            }
            facts.append(g.row().gap(Length.rem(0.8f)).alignItems(AlignItems.CENTER).children(
                    text(g, fact.getKey()).width(Length.rem(4.5f)).textColor(Role.DIM)
                            .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE),
                    value));
        }

        String notices = notices(build.notices());
        Button licenses = new Button(g, "Third-party licenses").enabled(!notices.isEmpty())
                .onPress(() -> Links.open(notices));
        licenses.node().role("about-licenses");
        g.landmark("about-licenses", licenses.node());
        String issues = build.frameworkIssues().orElse("");
        Button report = new Button(g, "Framework issues").enabled(!issues.isEmpty())
                .onPress(() -> Links.open(issues));
        report.node().role("about-issues");
        g.landmark("about-issues", report.node());
        Node links = g.row().gap(Length.rem(0.6f)).children(licenses.node(), report.node());

        Node body = g.column().width(Length.FILL).height(Length.FILL).alignItems(AlignItems.CENTER)
                .padding(Length.rem(1.5f), Length.rem(1.5f)).gap(Length.rem(0.35f))
                .children(logo, title, coordinates, facts, links);
        String framework = frameworkVersion(build);
        if (framework != null) {
            body.append(text(g, "Built on the VexelRay Framework " + framework).role("about-framework")
                    .textSize(Length.rem(0.75f)).textColor(Role.FAINT).margin(Length.rem(0.6f)));
        }
        TitleBar bar = window.titleBar();
        g.root().background(Role.PAGE).children(bar.node(), body);
        // Esc puts it away, as it does a dialog: there is nothing on it to lose.
        g.claim(g.root(), Shortcut.of(Key.ESCAPE), ClaimScope.VISIBLE, window::close);
        return about;
    }

    /**
     * Upload the logo, now there is a device: {@code ATTACH}, on the main thread. Each side is its own texture, chosen
     * against the pixels the box has, as the title bar's mark is, so the logo is drawn at its size on every display.
     * A logo that cannot be read leaves an empty box and a report; it is never worth an application's launch.
     */
    void attach(GuiApp app) {
        if (logo == null) {
            return;
        }
        List<byte[]> pngs = new ArrayList<>();
        for (int side : LOGO_SIDES) {
            String name = "vexelray-logo-" + side + ".png";
            try (InputStream in = About.class.getResourceAsStream(name)) {
                if (in == null) {
                    Diagnostics.dropped("About.attach", "the VexelRay logo", "no resource " + name);
                    return;
                }
                pngs.add(in.readAllBytes());
            } catch (IOException e) {
                Diagnostics.dropped("About.attach", "the VexelRay logo", e.toString());
                return;
            }
        }
        Icon icon;
        try {
            icon = Icon.fromPng(pngs.toArray(new byte[0][]));
        } catch (IllegalArgumentException e) {
            Diagnostics.dropped("About.attach", "the VexelRay logo", e.toString());
            return;
        }
        Map<Icon.Image, SampledImage> sizes = new LinkedHashMap<>();
        for (Icon.Image image : icon.images()) {
            sizes.put(image, app.texture(rgba(image), image.width(), image.height()));
        }
        logoIcon = icon;
        logoSizes = sizes;
        showLogo();
    }

    /** Draw the logo image nearest the pixels its box has now, once both are known. Any thread. */
    private void showLogo() {
        Map<Icon.Image, SampledImage> sizes = logoSizes;
        Rect box = logoBox;
        if (sizes == null || box == null) {
            return;
        }
        logo.image(sizes.get(logoIcon.bestFor(Math.round(Math.max(box.w(), box.h())))));
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
        String commit = build.commit();
        facts.put("Build", !build.known() ? "unknown"
                : commit.substring(0, Math.min(12, commit.length())) + (build.dirty() ? " + uncommitted changes" : ""));
        facts.put("Built", build.builtAt().map(t -> BUILT.format(t.atZone(ZoneId.systemDefault()))).orElse("unknown"));
        return facts;
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

    private static Node text(Gui g, String s) {
        return g.text(s).wordWrap(false);
    }

    /** {@code image}'s straight {@code 0xAARRGGBB} pixels as the straight RGBA8 bytes a texture takes. */
    private static byte[] rgba(Icon.Image image) {
        int[] argb = image.argb();
        byte[] out = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            out[i * 4] = (byte) (p >>> 16);
            out[i * 4 + 1] = (byte) (p >>> 8);
            out[i * 4 + 2] = (byte) p;
            out[i * 4 + 3] = (byte) (p >>> 24);
        }
        return out;
    }
}

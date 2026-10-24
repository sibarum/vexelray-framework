package dev.vexelray.framework.shell;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.DistanceField;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.icons.ArtPair;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.shadelets.CondenseStyle;
import dev.vexelray.shadelets.CondenseStyleLayout;
import dev.vexelray.shadelets.MtsdfStyle;
import dev.vexelray.shadelets.MtsdfStyleLayout;
import dev.vexelray.shadelets.OrbStyle;
import dev.vexelray.shadelets.OrbStyleLayout;
import dev.vexelray.vulkan.present.SampledImage;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The About window opening: the orb flies in from in front of the glass and lands on the VexelRay mark, and from where
 * it lands a front spreads across the page. Behind it each mark grows out of its distance field from the middle to its
 * edge, with a hot rim on the moving cut and rings closing in round it; the orb, now a light on the page, glints along
 * the marks' bevels; then the field gives way to the art itself and the clock stops, so the window at rest draws
 * nothing. Every word arrives as the front passes it. 2.8 seconds, and each time the window is opened.
 *
 * <p>The marks are art pairs ({@link ArtPair}) drawn by {@code Condense.CONDENSE} over {@code Condense.MTSDF}; the orb
 * is {@code Condense.ORB} on a circle's field. All three are in {@link FrameworkShader}. Every value they read is
 * written here, per frame, from Kronometer's ramp: a frame is a function of the time and the layout, so a capture of
 * one is reproducible and a ramp that is not running costs nothing. With motion off the ramp delivers its end, which
 * is the settled page.
 *
 * <p>The design is in pixels of a 440 by 560 window round a 104 pixel mark ({@code unit} scales it), and is the
 * mockup's: 0 to 0.95 s the orb's flight, an arc with a perspective shrink; from 0.95 s the front, eased out over
 * 1.25 s; the glint from 1.0 to 2.7 s; the settle from 2.1 to 2.6 s.
 *
 * <p><b>Nothing is read before the window first opens</b>: then the pairs are read and inflated off the main thread
 * and uploaded on it, every size at once (as the logo was), and the intro waits for them.
 */
final class AboutIntro {

    static final float END = 2.8f;
    private static final float T_FLY = 0.95f;
    private static final float T_FRONT = 1.25f;
    /** The design's mark, in its own pixels. */
    private static final float DESIGN_MARK = 104f;

    /** The hot colour: the rim, the rings, the light on the page, the orb's halo. */
    static final Color HEAT = Color.rgb(1f, 0.70f, 0.30f);

    /** The circle the orb is drawn on: its square and its margin, which is the halo's room. */
    private static final int ORB_INNER = 64;
    private static final int ORB_SPREAD = 64;

    /** The framework's mark, beside this class. */
    static final String SIGNATURE = "vexelray-logo";

    private final Gui g;
    private final KronoGui krono;
    private final About.Page page;
    private final Node orb;
    private final Class<?> anchor;
    private final String icon;

    private volatile GuiApp app;
    private volatile dev.vexelray.os.Icon appIcon;
    private volatile Art mark;
    private volatile Art signature;
    private volatile SampledImage orbField;
    private volatile Map<dev.vexelray.os.Icon.Image, SampledImage> iconSizes;
    private volatile boolean loading;
    private volatile boolean loaded;
    private final AtomicInteger run = new AtomicInteger();

    AboutIntro(Gui g, KronoGui krono, About.Page page, Class<?> anchor, String icon) {
        this.g = g;
        this.krono = krono;
        this.page = page;
        this.anchor = anchor;
        this.icon = icon;
        this.orb = g.box().role("about-orb").hitInert(true).scroll(false, false).visible(false);
        // Over the whole window, as an overlay is: it flies across the page and lands on the foot.
        g.root().append(orb.floatAt(Length.ZERO, Length.ZERO));
        hide();
    }

    /** The device is there; remember it, and {@code appIcon}, the application's mark, for when there is no pair. */
    void attach(GuiApp app, dev.vexelray.os.Icon appIcon) {
        this.app = app;
        this.appIcon = appIcon;
    }

    /** Play it from the start: on every opening. Any thread. */
    void play() {
        int id = run.incrementAndGet();
        page.reset().run();
        hide();
        if (loaded) {
            start(id);
        } else {
            load(id);
        }
    }

    /** The page before the front: no marks, no words. */
    private void hide() {
        for (Node n : page.arrivals()) {
            n.opacity(0f);
        }
        page.mark().field(null).image((SampledImage) null);
        page.signature().field(null);
        orb.visible(false);
    }

    private void start(int id) {
        krono.ramp(Dur.ms(Math.round(END * 1000)), Ease.LINEAR, p -> frame(id, (float) p * END),
                () -> frame(id, END));
    }

    /** Read the pairs off the main thread, upload them on it, then start. Once; a reopening meanwhile restarts it. */
    private synchronized void load(int id) {
        GuiApp a = app;
        if (a == null) {
            settleWithoutArt();
            return;
        }
        if (loading) {
            return;
        }
        loading = true;
        Thread.ofVirtual().name("about-art").start(() -> {
            Art appPair = read(anchor, icon == null ? null : base(icon), "the application's mark");
            Art framework = read(AboutIntro.class, SIGNATURE, "the VexelRay mark");
            byte[] circle = circle();
            a.post(() -> {
                // Only the size each mark is drawn at now: uploading every size held the GUI thread a quarter second.
                mark = appPair;
                signature = framework;
                if (mark != null) {
                    mark.ensure(a, width(page.mark(), 104f));
                }
                if (signature != null) {
                    signature.ensure(a, width(page.signature(), 52f));
                }
                int side = ORB_INNER + 2 * ORB_SPREAD;
                orbField = a.texture(circle, side, side);
                dev.vexelray.os.Icon i = appIcon;
                if (mark == null && i != null) {
                    Map<dev.vexelray.os.Icon.Image, SampledImage> sizes = new HashMap<>();
                    for (dev.vexelray.os.Icon.Image image : i.images()) {
                        sizes.put(image, a.texture(rgba(image), image.width(), image.height()));
                    }
                    iconSizes = sizes;
                }
                loaded = true;
                if (run.get() == id) {
                    start(id);
                } else {
                    play();
                }
            });
        });
    }

    /** With no device to draw the marks with, the words at least. */
    private void settleWithoutArt() {
        for (Node n : page.arrivals()) {
            n.opacity(1f).translate(0f, 0f);
        }
    }

    // ------------------------------------------------------------------ the frame

    /** Everything at {@code t} seconds, if {@code id} is still the run that is playing. */
    private void frame(int id, float t) {
        if (run.get() != id) {
            return;
        }
        NodeLayout bodyAt = g.root().layout();
        NodeLayout markAt = page.mark().layout();
        NodeLayout sigAt = page.signature().layout();
        if (!bodyAt.present() || !markAt.present() || !sigAt.present() || markAt.rect().w() <= 0f) {
            // Not laid out yet: the page stays hidden, and the next frame asks again. At the end, settle regardless.
            if (t >= END) {
                settleWithoutArt();
            }
            return;
        }
        Rect body = bodyAt.rect();
        Rect markBox = markAt.rect();
        Rect sigBox = sigAt.rect();
        float px = markBox.w() / 104f;          // pixels per dp: the mark is 104 dp
        float unit = markBox.w() / DESIGN_MARK;

        // Where the orb comes to rest: the lifted part of the signature, or the middle of its top.
        ArtPair.Lift lift = signature == null ? null : signature.pair.lift();
        float restX = sigBox.x() + sigBox.w() * (lift == null ? 0.5f : lift.x());
        float restY = sigBox.y() + sigBox.h() * (lift == null ? 0.17f : lift.y());
        float restR = sigBox.w() * (lift == null ? 0.128f : lift.radius());

        Timeline tl = timeline(t, body, restX, restY, restR, unit);
        if (mark != null) {
            mark.draw(app, page.mark(), markBox, tl, unit, t);
        } else {
            showIcon(markBox, tl, restX, restY, unit, t);
        }
        if (signature != null) {
            signature.draw(app, page.signature(), sigBox, tl, sigBox.w() / DESIGN_MARK, t);
        }
        drawOrb(body, tl, px);
        for (Node n : page.arrivals()) {
            NodeLayout at = n.layout();
            float op = t >= END || !at.present() ? 1f
                    : arrival(tl, at.rect().centreX(), at.rect().centreY(), restX, restY, unit);
            n.opacity(op).translate(0f, (1f - op) * 0.35f);
        }
    }

    /** How far the front has brought a point at {@code (x, y)} in: 0 before it, 1 once it is well past. */
    private static float arrival(Timeline tl, float x, float y, float restX, float restY, float unit) {
        float dist = (float) Math.hypot(x - restX, y - restY);
        return clamp((tl.front - dist - 40f * unit) / (80f * unit));
    }

    /** The application's icon, for an application without a pair: no condensing, only its arrival. */
    private void showIcon(Rect box, Timeline tl, float restX, float restY, float unit, float t) {
        Map<dev.vexelray.os.Icon.Image, SampledImage> sizes = iconSizes;
        dev.vexelray.os.Icon i = appIcon;
        if (sizes == null || i == null) {
            return;
        }
        float op = t >= END ? 1f : arrival(tl, box.centreX(), box.centreY(), restX, restY, unit);
        page.mark().image(sizes.get(i.bestFor(Math.round(box.w())))).opacity(op);
    }

    private void drawOrb(Rect body, Timeline tl, float px) {
        SampledImage field = orbField;
        float alpha = tl.orbAlpha * (1f - tl.settle);
        if (field == null || alpha <= 0.002f) {
            orb.visible(false);
            return;
        }
        float r = tl.orbR;
        OrbStyle style = new OrbStyle(HEAT, r, tl.blur, alpha, tl.glow, tl.settle);
        float[] instance = new float[OrbStyleLayout.LAYOUT.instanceFloats()];
        OrbStyleLayout.instance(style, instance, 0);
        int side = ORB_INNER + 2 * ORB_SPREAD;
        orb.visible(true)
                .size(Length.dp(2f * r / px), Length.dp(2f * r / px))
                .floatAt(Length.dp((tl.orbX - r - body.x()) / px), Length.dp((tl.orbY - r - body.y()) / px))
                .field(new ImageField(field, ImageRegion.WHOLE, side, ORB_SPREAD, Color.WHITE, ORB_LOOK, instance));
    }

    private static final Look ORB_LOOK = new Look(Look.Use.of("field"), List.of(),
            List.of(OrbStyleLayout.use("orb", new OrbStyle(HEAT, 0f, 0f, 0f, 0f, 0f))));

    // ------------------------------------------------------------------ the timeline

    /** One frame's values, in window pixels. */
    private record Timeline(float orbX, float orbY, float orbR, float blur, float orbAlpha, float glow,
                            float preview, float front, float ring, float glint, float settle, float frontX,
                            float frontY) {
    }

    /**
     * The mockup's timeline at {@code t}: the orb on a quadratic arc from in front of the page's middle, past its
     * lower right, to its rest, shrinking with a fake depth and coming into focus; then the front, from the rest out
     * to past the farthest corner.
     */
    private static Timeline timeline(float t, Rect body, float restX, float restY, float restR, float unit) {
        float k = easeInOut(clamp(t / T_FLY));
        float p0x = body.x() + body.w() * 0.486f;
        float p0y = body.y() + body.h() * 0.468f;
        float p1x = body.x() + body.w() * 0.845f;
        float p1y = body.y() + body.h() * 0.768f;
        float a = (1 - k) * (1 - k);
        float b = 2 * (1 - k) * k;
        float c = k * k;
        float x = a * p0x + b * p1x + c * restX;
        float y = a * p0y + b * p1y + c * restY;
        float depth = 0.085f + 0.915f * (float) Math.pow(k, 1.6);
        float tf = t - T_FLY;
        float v = clamp(tf / T_FRONT);
        float far = 0f;
        for (float[] corner : new float[][]{{body.x(), body.y()}, {body.x() + body.w(), body.y()},
                {body.x(), body.y() + body.h()}, {body.x() + body.w(), body.y() + body.h()}}) {
            far = Math.max(far, (float) Math.hypot(corner[0] - restX, corner[1] - restY));
        }
        float reach = far + 60f * unit;
        float front = tf < 0 ? -1e4f : reach * (1f - (float) Math.pow(1 - v, 3));
        return new Timeline(x, y, restR / depth, 0.5f * (1 - k) * (1 - k), smooth(0f, 0.18f, t),
                0.7f * (1 - k) + (tf > 0 ? 1.3f * (float) Math.exp(-tf * 7) : 0f),
                smooth(0.08f, 0.3f, t) * (1f - smooth(0.85f, 1.25f, t)),
                front, tf < 0 ? 0f : 1f - smooth(0.55f, 1f, v),
                0.9f * smooth(1.0f, 1.25f, t) * (1f - smooth(1.7f, 2.7f, t)), smooth(2.1f, 2.6f, t),
                restX, restY);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smooth(float a, float b, float x) {
        float t = clamp((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    private static float easeInOut(float k) {
        return k < 0.5f ? 4 * k * k * k : 1 - (float) Math.pow(-2 * k + 2, 3) / 2;
    }

    // ------------------------------------------------------------------ the art

    /**
     * A pair read and inflated, every size, and drawn by the condense look at whichever size is nearest the pixels its
     * box has. A size goes to the device the first time it is drawn at, on the main thread: one atlas is a few
     * milliseconds there, every size of both marks was a quarter of a second.
     */
    private record Art(ArtPair pair, Map<Integer, ArtPair.Atlas> atlases, Map<Integer, SampledImage> uploaded) {

        Art(ArtPair pair, Map<Integer, ArtPair.Atlas> atlases) {
            this(pair, atlases, new java.util.concurrent.ConcurrentHashMap<>());
        }

        /** The size for {@code px} on the device. Main thread. */
        void ensure(GuiApp app, float px) {
            uploaded.computeIfAbsent(pair.sizeFor(px), size -> {
                ArtPair.Atlas a = atlases.get(size);
                return app.texture(a.rgba(), a.width(), a.height());
            });
        }

        /** This frame of the mark; a size not on the device yet is sent for, and the mark waits a frame for it. */
        void draw(GuiApp app, Node node, Rect box, Timeline tl, float unit, float t) {
            int size = pair.sizeFor(box.w());
            SampledImage texture = uploaded.get(size);
            if (texture == null) {
                float px = box.w();
                app.post(() -> ensure(app, px));
                return;
            }
            ArtPair.Atlas atlas = atlases.get(size);
            ImageRegion field = atlas.field();
            ImageRegion colour = atlas.colour();
            float m = pair.margin();
            // Where the fragment falls in the mark's square, from where it falls in the field's cell.
            float mapX = (1f + 2f * m) / (field.u1() - field.u0());
            float mapY = (1f + 2f * m) / (field.v1() - field.v0());
            float cx = box.centreX();
            float cy = box.centreY();
            CondenseStyle style = new CondenseStyle(HEAT, unit,
                    tl.frontX - cx, tl.frontY - cy, tl.front,
                    tl.orbX - cx, tl.orbY - cy, tl.orbR,
                    tl.preview, tl.ring, tl.glint, tl.settle, t,
                    mapX, mapY, -field.u0() * mapX - m, -field.v0() * mapY - m,
                    colour.u0(), colour.v0(), colour.u1(), colour.v1());
            float[] instance = new float[CondenseStyleLayout.LAYOUT.instanceFloats()];
            CondenseStyleLayout.instance(style, instance, 0);
            node.field(new ImageField(texture, field, pair.fieldSide(), pair.spread(), Color.WHITE,
                    look(pair), instance));
        }

        private static Look look(ArtPair pair) {
            return new Look(MtsdfStyleLayout.use("mtsdf", new MtsdfStyle(pair.near())), List.of(),
                    List.of(CondenseStyleLayout.use("condense", new CondenseStyle(HEAT, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))));
        }
    }

    /** The pair {@code name} beside {@code anchor}, every size read; null, said once, if it is not there or is bad. */
    private static Art read(Class<?> anchor, String name, String what) {
        if (name == null) {
            return null;
        }
        try {
            ArtPair pair = ArtPair.read(anchor, name);
            if (pair == null) {
                Diagnostics.dropped("About.art", what + " condensing", "no art pair " + name + ".pair beside "
                        + anchor.getName() + "; its icon is shown instead (the icon plugin's pair goal bakes one)");
                return null;
            }
            Map<Integer, ArtPair.Atlas> atlases = new HashMap<>();
            for (int size : pair.sizes()) {
                atlases.put(size, pair.atlas(size));
            }
            return new Art(pair, atlases);
        } catch (IOException | RuntimeException e) {
            Diagnostics.dropped("About.art", what + " condensing", e.toString());
            return null;
        }
    }

    /** {@code node}'s laid-out width, or {@code otherwise} before it has one. */
    private static float width(Node node, float otherwise) {
        NodeLayout at = node.layout();
        return at.present() && at.rect().w() > 0f ? at.rect().w() : otherwise;
    }

    /** {@code pix.ico} to {@code pix}, keeping any path. */
    static String base(String icon) {
        int dot = icon.lastIndexOf('.');
        int slash = icon.lastIndexOf('/');
        return dot > slash ? icon.substring(0, dot) : icon;
    }

    /** A disc's distance field, RGBA, for the orb to be drawn on. */
    private static byte[] circle() {
        int fine = DistanceField.maskSide(ORB_INNER);
        byte[] mask = new byte[fine * fine * 4];
        float r = fine / 2f;
        for (int y = 0; y < fine; y++) {
            for (int x = 0; x < fine; x++) {
                float dx = x + 0.5f - r;
                float dy = y + 0.5f - r;
                if (dx * dx + dy * dy <= r * r) {
                    mask[(y * fine + x) * 4 + 3] = (byte) 255;
                }
            }
        }
        return DistanceField.of(mask, ORB_INNER, ORB_SPREAD).rgba();
    }

    /** {@code image}'s straight {@code 0xAARRGGBB} pixels as the straight RGBA8 bytes a texture takes. */
    private static byte[] rgba(dev.vexelray.os.Icon.Image image) {
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

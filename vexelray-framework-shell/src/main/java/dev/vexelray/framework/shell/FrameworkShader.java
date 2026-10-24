package dev.vexelray.framework.shell;

import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.shadelets.Effects;
import dev.vexelray.text.FieldType;
import dev.vexelray.text.FontSet;

import java.util.List;
import java.util.Set;

/**
 * The canvas shader every application on the framework draws with: the core set, and the effects the framework's own
 * widgets ask for. Today that is {@link Effects#WEIGHT}, which the theme's {@code IconEffects.STANDARD} thickens an
 * {@code IconButton}'s icon with under the pointer; without it the icon stays still and the first hover says so once.
 *
 * <p>Only what a standard widget names, not {@link Effects#ALL}: every shadelet composed in is a branch in the one
 * fragment stage every pixel runs. An effect only the application draws is the application's to compose.
 *
 * <p>This one draws MSDF fonts, the standard set's; {@link Sdf} is the same for an application whose fonts are SDF,
 * and {@link #of} picks between them.
 *
 * <p>Public with a public no-arg constructor because the supirvast plugin in this module's pom finds it at build time
 * and compiles it to SPIR-V into the jar, which takes lowering it off every application's startup.
 */
public final class FrameworkShader extends CanvasShader.Composed {

    public FrameworkShader() {
        super(withEffects(FieldType.MSDF));
    }

    /** As {@link FrameworkShader}, drawing SDF fonts: text from one channel, with no median in the shader. */
    public static final class Sdf extends CanvasShader.Composed {
        public Sdf() {
            super(withEffects(FieldType.SDF));
        }
    }

    /**
     * The shader for {@code fonts}, the application's own set or null for the standard one: {@link Sdf} for a set of
     * SDF faces, and this one otherwise. A set that mixes types gets this one, and {@code GuiApp} refuses the set.
     */
    static CanvasShader.Composed of(FontSet fonts) {
        return fonts != null && fonts.types().equals(Set.of(FieldType.SDF)) ? new Sdf() : new FrameworkShader();
    }

    private static Composition withEffects(FieldType text) {
        return CoreShadelets.composition(text).with(List.of(Effects.WEIGHT));
    }
}

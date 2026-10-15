package dev.vexelray.framework.shell;

import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.shadelets.Effects;

import java.util.List;

/**
 * The canvas shader every application on the framework draws with: the core set, and the effects the framework's own
 * widgets ask for. Today that is {@link Effects#WEIGHT}, which the theme's {@code IconEffects.STANDARD} thickens an
 * {@code IconButton}'s icon with under the pointer; without it the icon stays still and the first hover says so once.
 *
 * <p>Only what a standard widget names, not {@link Effects#ALL}: every shadelet composed in is a branch in the one
 * fragment stage every pixel runs. An effect only the application draws is the application's to compose.
 *
 * <p>Public with a public no-arg constructor because the supirvast plugin in this module's pom finds it at build time
 * and compiles it to SPIR-V into the jar, which takes lowering it off every application's startup.
 */
public final class FrameworkShader extends CanvasShader.Composed {

    public FrameworkShader() {
        super(CoreShadelets.composition().with(List.of(Effects.WEIGHT)));
    }
}

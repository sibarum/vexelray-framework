package dev.vexelray.framework.shell;

import dev.supirvast.vastir.shader.Shaders;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.LookTable;
import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.style.IconEffects;
import dev.vexelray.shadelets.Effects;
import dev.vexelray.shadelets.WeightStyle;
import dev.vexelray.shadelets.WeightStyleLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shader every application on the framework draws with draws what the framework's own widgets ask for: an
 * {@code IconButton} under the pointer names {@link Effects#WEIGHT} (the theme's {@link IconEffects#STANDARD}), and
 * the core set alone would leave it out and say so on the first hover.
 */
class FrameworkShaderTest {

    @BeforeEach
    @AfterEach
    void forgetWarnings() {
        Diagnostics.reset();
    }

    /** The look an {@code IconView} of side 24 writes when its button is hovered, as IconView builds it. */
    private static Look hoveredIcon() {
        float side = 24f;
        List<Look.Use> weight = List.of(WeightStyleLayout.use(Effects.WEIGHT.name(),
                new WeightStyle(side * IconEffects.STANDARD.hoverWeight())));
        return ImageField.effects(weight, List.of(), 0f, 0f);
    }

    private static List<String> notComposed() {
        return Diagnostics.recorded().stream().filter(m -> m.contains("was not composed with it")).toList();
    }

    @Test
    void aHoveredIconButtonsWeightIsComposed() {
        new LookTable(new FrameworkShader().composition()).record(hoveredIcon());
        assertEquals(List.of(), notComposed());
    }

    /** The case this guards against, so the test above is known to be able to fail. */
    @Test
    void theCoreSetAloneLeavesItOutAndSaysSo() {
        new LookTable(new CanvasShader.Fragment().composition()).record(hoveredIcon());
        assertEquals(1, notComposed().size(), "said once: " + Diagnostics.recorded());
    }

    /** Pre-compiled into this module's jar, so no application lowers it on the main thread at startup. */
    @Test
    void itIsPrecompiled() {
        FrameworkShader shader = new FrameworkShader();
        assertTrue(Shaders.isPrecompiled(shader.name(), shader.getClass().getClassLoader()),
                "the supirvast plugin compiled " + shader.name() + " into this module");
    }
}

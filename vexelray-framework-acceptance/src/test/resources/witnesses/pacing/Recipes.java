package ${packageName};

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Atchung;

/** The pacing measurements' parts: the template's look, the meter that carries the frame hook, and the tree. */
@Configuration
final class Recipes {

    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.em(${className}.MIN_W_EM), Length.em(${className}.MIN_H_EM));
    }

    /** Provided as itself: it carries the frame hook, and the generated wiring adds it to the frame array. */
    @Provides
    Meter meter() {
        return new Meter();
    }

    /** The timer experiment's switch; see {@code TimerResolution}. Built once, before anything draws. */
    @Provides
    TimerResolution timer(@dev.vexelray.framework.api.Setting(value = "pacing.timer", def = "off") String mode) {
        return new TimerResolution(mode);
    }

    @Provides
    Ui ui(Gui gui, KronoGui krono, Meter meter, Atchung bus, TitleBar titleBar) {
        return new Ui(gui, krono, meter, bus, titleBar);
    }
}

package ${packageName};

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Atchung;

/** The long-running witness's parts: the template's look and model, the meter, and a tree that drives it. */
@Configuration
final class Recipes {

    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.em(${className}.MIN_W_EM), Length.em(${className}.MIN_H_EM));
    }

    @Provides
    Model model() {
        return new Model();
    }

    /** Provided as itself: it carries the frame hook, and the generated wiring adds it to the frame array. */
    @Provides
    Meter meter() {
        return new Meter();
    }

    @Provides
    Ui ui(Gui gui, Model model, Meter meter, Atchung bus, TitleBar titleBar) {
        Ui ui = new Ui(gui, model, meter, bus, titleBar);
        model.onChange(ui::show);
        meter.onChange(ui::showTicks);
        ui.show(model.doc());
        return ui;
    }
}

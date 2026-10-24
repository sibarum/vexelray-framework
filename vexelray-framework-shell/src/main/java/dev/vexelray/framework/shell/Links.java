package dev.vexelray.framework.shell;

import dev.vexelray.os.NativePlatform;
import sibarum.probe.Log;

/**
 * A link handed to the desktop: a web address to the browser, a file to whatever opens it. What the About window's
 * links do, and the only place the framework starts another program on the person's behalf.
 *
 * <p>The opener is the platform's ({@link NativePlatform#open}), since which program that is differs by OS and the
 * stack keeps every such difference in an OS module. Blocking only while the opener starts; call it off the frame
 * loop, as a button's handler already is.
 */
final class Links {

    private static final Log LOG = Log.of("framework.links");

    private Links() {
    }

    /** Open {@code target}; false, and logged, if the platform could not start an opener for it. */
    static boolean open(String target) {
        try {
            if (NativePlatform.current().open(target)) {
                return true;
            }
            LOG.warn("could not open {}: this platform has no opener, or it would not start", target);
        } catch (RuntimeException e) {
            LOG.warn("could not open " + target, e);
        }
        return false;
    }
}

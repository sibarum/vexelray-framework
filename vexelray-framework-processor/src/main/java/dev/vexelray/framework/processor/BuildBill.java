package dev.vexelray.framework.processor;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The bill {@code vexelray-build-maven-plugin} writes before anything compiles: the application's coordinates and exact
 * source, and every stamped part of the stack on its class path ({@code vexelray-build/README.md}). Read from the
 * class output, where the plugin put it, so the code generator needs no option and no path.
 */
final class BuildBill {

    /** Where the plugin writes the bill, relative to the class output. */
    static final String RESOURCE = "META-INF/vexelray/build.properties";

    private BuildBill() {
    }

    /** The bill, or {@code null} when the application was not built with the plugin. */
    static Properties read(Filer filer) {
        try {
            FileObject f = filer.getResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE);
            try (InputStream in = f.openInputStream()) {
                Properties p = new Properties();
                p.load(in);
                return p.getProperty("source") == null ? null : p;
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code stack.1} to {@code stack.<count>}, in order. */
    static List<String> stack(Properties bill) {
        List<String> stack = new ArrayList<>();
        int n;
        try {
            n = Integer.parseInt(bill.getProperty("stack.count", "0").trim());
        } catch (NumberFormatException e) {
            n = 0;
        }
        for (int i = 1; i <= n; i++) {
            String s = bill.getProperty("stack." + i);
            if (s != null && !s.isBlank()) {
                stack.add(s);
            }
        }
        return stack;
    }
}

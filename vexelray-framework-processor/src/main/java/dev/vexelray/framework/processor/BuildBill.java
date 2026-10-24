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
            // As UTF-8, which is how the plugin writes it: Properties.load(InputStream) would read it as Latin-1, and a
            // proper name with an accent in it would come out of the About window as two other letters.
            try (InputStream in = f.openInputStream();
                 java.io.Reader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(r);
                return p.getProperty("source") == null ? null : p;
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code stack.1} to {@code stack.<count>}, in order. */
    static List<String> stack(Properties bill) {
        List<String> stack = new ArrayList<>();
        for (int i = 1; i <= count(bill); i++) {
            String s = bill.getProperty("stack." + i);
            if (s != null && !s.isBlank()) {
                stack.add(s);
            }
        }
        return stack;
    }

    /**
     * Where problems are reported, by {@code group:artifact}: the application's own {@code issues}, then each
     * {@code stack.<n>.issues}, keyed by the part its line names. In bill order; parts that name none are left out.
     */
    static java.util.Map<String, String> issues(Properties bill) {
        java.util.Map<String, String> issues = new java.util.LinkedHashMap<>();
        String own = bill.getProperty("issues", "").trim();
        if (!own.isEmpty()) {
            issues.put(bill.getProperty("group", "-") + ":" + bill.getProperty("artifact", "-"), own);
        }
        for (int i = 1; i <= count(bill); i++) {
            String line = bill.getProperty("stack." + i, "");
            String url = bill.getProperty("stack." + i + ".issues", "").trim();
            int second = line.indexOf(':', line.indexOf(':') + 1);
            if (!url.isEmpty() && second > 0) {
                issues.put(line.substring(0, second), url);
            }
        }
        return issues;
    }

    private static int count(Properties bill) {
        try {
            return Integer.parseInt(bill.getProperty("stack.count", "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

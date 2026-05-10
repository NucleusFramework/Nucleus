package dev.nucleusframework.nucleus.graalvm.wmclass;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the real application name for use as X11 WM_CLASS.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>{@code nucleus/nucleus-app.properties} → {@code startup.wm.class} key (matches .desktop StartupWMClass)</li>
 *   <li>{@code nucleus/nucleus-app.properties} → {@code app.id} key</li>
 *   <li>{@code nucleus.app.id} system property (fallback for run task)</li>
 *   <li>Executable name from {@code /proc/self/exe}</li>
 *   <li>Fallback: {@code "NucleusApp"}</li>
 * </ol>
 */
final class AppNameResolver {

    private static volatile String cached;

    static String resolve() {
        String name = cached;
        if (name != null) return name;

        // Match XToolkit.getCorrectXIDString() which replaces '.' with '-'
        name = doResolve().replace('.', '-');
        cached = name;
        return name;
    }

    private static String doResolve() {
        // 1) Classpath resource written by the Nucleus plugin at build time.
        //    Prefer startup.wm.class (matches the .desktop StartupWMClass) over
        //    app.id (which is the raw packageName, not necessarily WM_CLASS-safe).
        try {
            InputStream stream = AppNameResolver.class
                    .getClassLoader()
                    .getResourceAsStream("nucleus/nucleus-app.properties");
            if (stream != null) {
                try (stream) {
                    Properties props = new Properties();
                    props.load(stream);
                    String wmClass = props.getProperty("startup.wm.class");
                    if (wmClass != null && !wmClass.isBlank()) {
                        return wmClass;
                    }
                    String resId = props.getProperty("app.id");
                    if (resId != null && !resId.isBlank()) {
                        return resId;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 2) System property injected by the Nucleus plugin (fallback for run task)
        String appId = System.getProperty("nucleus.app.id");
        if (appId != null && !appId.isBlank()) {
            return appId;
        }

        // 3) Executable name from /proc/self/exe
        try {
            File exe = new File("/proc/self/exe").getCanonicalFile();
            String name = exe.getName();
            if (name != null && !name.isEmpty()) {
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
                return name;
            }
        } catch (Exception ignored) {
        }

        // 4) Fallback
        return "NucleusApp";
    }

    private AppNameResolver() {
    }
}

package dev.nucleusframework.nucleus.graalvm.font;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.io.File;

/**
 * GraalVM native-image substitution for {@code sun.awt.FcFontManager.getFontPath}.
 * <p>
 * On Linux, the native {@code getFontPath} method (inherited from
 * {@code sun.font.SunFontManager}) checks the C-level {@code sun_jnu_encoding}
 * variable, which is set during JVM startup but is not preserved in GraalVM
 * native images. This causes {@code InternalError: platform encoding not initialized}
 * on certain Linux distributions (Alpine, minimal installs without fontconfig).
 * <p>
 * This substitution replaces the native method with a pure-Java implementation
 * that returns standard Linux font directories.
 *
 * @see <a href="https://github.com/bell-sw/Liberica/issues/9">Liberica #9</a>
 */
@TargetClass(className = "sun.awt.FcFontManager", onlyWith = IsLinux.class)
final class Target_sun_awt_FcFontManager {

    @Substitute
    protected synchronized String getFontPath(boolean noType1Fonts) {
        StringBuilder path = new StringBuilder();
        String[] dirs = {
            "/usr/share/fonts",
            "/usr/local/share/fonts",
            "/usr/share/X11/fonts",
            System.getProperty("user.home") + "/.fonts",
        };
        for (String dir : dirs) {
            if (new File(dir).isDirectory()) {
                if (path.length() > 0) path.append(File.pathSeparator);
                path.append(dir);
            }
        }
        // Fallback to /usr/share/fonts if nothing was found
        if (path.length() == 0) {
            path.append("/usr/share/fonts");
        }
        return path.toString();
    }
}

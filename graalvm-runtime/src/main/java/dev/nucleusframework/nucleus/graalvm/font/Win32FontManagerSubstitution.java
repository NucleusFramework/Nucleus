package dev.nucleusframework.nucleus.graalvm.font;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM native-image substitution for {@code sun.awt.Win32FontManager.getFontPath}.
 * <p>
 * The native {@code getFontPath} method checks the C-level {@code sun_jnu_encoding}
 * variable, which is set by {@code SunFontManager.initIDs()} during JVM startup.
 * In GraalVM native image this variable is not preserved from build-time, causing
 * {@code InternalError: platform encoding not initialized}.
 * <p>
 * This substitution replaces the native method with a pure-Java implementation
 * that returns the Windows fonts directory directly.
 */
@TargetClass(className = "sun.awt.Win32FontManager", onlyWith = IsWindows.class)
final class Target_sun_awt_Win32FontManager {

    @Substitute
    protected synchronized String getFontPath(boolean noType1Fonts) {
        String winDir = System.getenv("WINDIR");
        if (winDir == null) winDir = System.getenv("SystemRoot");
        if (winDir == null) winDir = "C:\\Windows";
        return winDir + "\\Fonts";
    }
}

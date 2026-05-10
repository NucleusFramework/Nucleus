package dev.nucleusframework.nucleus.graalvm.font;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * GraalVM native-image substitution for {@link Font#createFont(int, InputStream)}.
 * <p>
 * In GraalVM native image on Windows and Linux, classpath resource InputStreams may not work
 * correctly with the native font parsing code in {@code createFont0()}, causing
 * {@code IOException: Problem reading font data}. This substitution buffers the
 * stream data to a temp file and delegates to the File-based overload.
 */
@TargetClass(value = java.awt.Font.class, onlyWith = IsWindowsOrLinux.class)
final class Target_java_awt_Font {

    @Substitute
    public static Font createFont(int fontFormat, InputStream fontStream)
            throws FontFormatException, IOException {
        if (fontFormat != Font.TRUETYPE_FONT && fontFormat != Font.TYPE1_FONT) {
            throw new IllegalArgumentException("font format not recognized");
        }
        if (fontStream == null) {
            throw new NullPointerException("font stream is null");
        }

        byte[] data = fontStream.readAllBytes();
        fontStream.close();

        File tmpFile = File.createTempFile("graalvm-font-", ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(data);
            }
            return Font.createFont(fontFormat, tmpFile);
        } finally {
            tmpFile.delete();
        }
    }
}

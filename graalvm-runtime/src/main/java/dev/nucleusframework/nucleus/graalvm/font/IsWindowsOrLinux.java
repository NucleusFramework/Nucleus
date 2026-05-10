package dev.nucleusframework.nucleus.graalvm.font;

import org.graalvm.nativeimage.Platform;

import java.util.function.BooleanSupplier;

/** Build-time condition: true when compiling a native image for Windows or Linux. */
final class IsWindowsOrLinux implements BooleanSupplier {
    @Override
    public boolean getAsBoolean() {
        return Platform.includedIn(Platform.WINDOWS.class)
                || Platform.includedIn(Platform.LINUX.class);
    }
}

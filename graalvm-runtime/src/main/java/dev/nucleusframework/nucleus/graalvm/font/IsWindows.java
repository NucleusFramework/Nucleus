package dev.nucleusframework.nucleus.graalvm.font;

import org.graalvm.nativeimage.Platform;

import java.util.function.BooleanSupplier;

/** Build-time condition: true only when compiling a native image for Windows. */
final class IsWindows implements BooleanSupplier {
    @Override
    public boolean getAsBoolean() {
        return Platform.includedIn(Platform.WINDOWS.class);
    }
}

package dev.nucleusframework.graalvm.encoding;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.word.PointerBase;

/**
 * Darwin-only holder for the {@code nucleus_init_platform_encoding} C shim binding.
 *
 * <p>The whole class is annotated {@link Platforms}({@link Platform.DARWIN}) so SubstrateVM includes
 * it — and, crucially, registers the external symbol of its {@link CFunction} native method — only in
 * macOS images. On Windows/Linux the class is not part of the image at all, so no undefined-symbol
 * reference is emitted at link time. (A mere {@code Platform.includedIn} guard on the call site is not
 * enough: SVM registers the {@code @CFunction} symbol for every reachable declaring class, not per call
 * site, so the symbol would still be emitted while the C shim is compiled only on macOS.)
 *
 * @see PlatformEncodingInitializer
 */
@Platforms(Platform.DARWIN.class)
final class DarwinPlatformEncoding {

    private DarwinPlatformEncoding() {
    }

    /**
     * C shim linked into the image (see the macOS stub in {@code configureGraalvmApplication.kt}).
     * Initializes the bundled {@code libjava.dylib}'s platform encoding using the given JNIEnv.
     */
    @CFunction("nucleus_init_platform_encoding")
    private static native void nucleusInitPlatformEncoding(PointerBase env);

    /** Initialize the platform encoding of the bundled libjava.dylib. */
    static void initialize() {
        nucleusInitPlatformEncoding(CurrentIsolate.getCurrentThread());
    }
}

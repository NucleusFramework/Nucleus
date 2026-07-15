package dev.nucleusframework.graalvm.encoding;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.word.PointerBase;

/**
 * Initializes the JDK's C-level platform ("JNU") encoding inside a native image, on macOS.
 *
 * <p>The JDK C code keeps a {@code fastEncoding} global (in libjava's {@code jni_util.c}) that must
 * be initialized before any C&rarr;Java string conversion ({@code JNU_NewStringPlatform}) works. On
 * a normal JVM the launcher calls the exported {@code InitializeEncoding}; BellSoft Liberica NIK
 * calls it from its SVM {@code JNIPlatformNativeLibrarySupport.loadJavaLibrary()}. Oracle GraalVM's
 * mainline SVM does <b>not</b>.
 *
 * <p>On macOS the native image links its <em>own static</em> copy of libjava, but the bundled AWT
 * dylibs ({@code libawt}/{@code libfontmanager}) load {@code @rpath/libjava.dylib} — a <em>separate</em>
 * copy whose {@code fastEncoding} is never initialized. So the first AWT {@code JNI_OnLoad} that does a
 * C&rarr;Java conversion aborts the VM with {@code InternalError: platform encoding not initialized}
 * followed by {@code Fatal error reported via JNI: Could not allocate library name}. Initializing the
 * static copy (a direct {@code @CFunction("InitializeEncoding")}) does not help — it is the wrong copy.
 *
 * <p>Fix: call {@code nucleus_init_platform_encoding}, a tiny C shim compiled into the image (from the
 * macOS cursor stub, linked via {@code -H:NativeLinkerOption}). The shim {@code dlopen}s the same
 * bundled {@code libjava.dylib} — dyld shares it by path with the one the AWT libs load — and calls
 * <em>its</em> {@code InitializeEncoding}. Must run before any AWT/font {@code System.loadLibrary}.
 * Native-image only.
 *
 * @see <a href="https://github.com/oracle/graal/issues/8475">oracle/graal#8475</a>
 */
public final class PlatformEncodingInitializer {

    private PlatformEncodingInitializer() {
    }

    /**
     * C shim linked into the image (see the macOS stub in {@code configureGraalvmApplication.kt}).
     * Initializes the bundled {@code libjava.dylib}'s platform encoding using the given JNIEnv.
     */
    @CFunction("nucleus_init_platform_encoding")
    private static native void nucleusInitPlatformEncoding(PointerBase env);

    /** Initialize the platform encoding of the bundled libjava.dylib. */
    public static void initialize() {
        nucleusInitPlatformEncoding(CurrentIsolate.getCurrentThread());
    }
}

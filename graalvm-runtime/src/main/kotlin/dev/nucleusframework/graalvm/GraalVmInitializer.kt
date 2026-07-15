package dev.nucleusframework.graalvm

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.graalvm.encoding.PlatformEncodingInitializer
import dev.nucleusframework.graalvm.locale.NativeLocaleBridge
import dev.nucleusframework.hidpi.applyLinuxHiDpiScale
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

object GraalVmInitializer {
    val isNativeImage: Boolean =
        System.getProperty("org.graalvm.nativeimage.imagecode") != null

    /** Call once at the very start of main(), before any AWT/Compose usage. */
    fun initialize() {
        if (isNativeImage) {
            // Initialize the JDK platform (JNU) encoding FIRST, before any AWT/font native library
            // loads. Oracle GraalVM's SVM — unlike BellSoft Liberica NIK — never calls the JDK's
            // InitializeEncoding, so libawt's JNI_OnLoad would abort the VM with "platform encoding
            // not initialized" / "Could not allocate library name". Ported from Liberica's
            // JNIPlatformNativeLibrarySupport. Harmless under Liberica (encoding already set).
            // macOS-only in effect: initialize() is gated by a native-image build-time platform
            // fold (Platform.includedIn(DARWIN)), so the `nucleus_init_platform_encoding` C shim —
            // which the plugin compiles into the image only on macOS — is referenced only there.
            // On Windows/Linux SVM eliminates the call, avoiding an undefined-symbol link error.
            PlatformEncodingInitializer.initialize()

            // Metal L&F — avoids platform-specific modules unsupported in native image
            System.setProperty("swing.defaultlaf", "javax.swing.plaf.metal.MetalLookAndFeel")

            // Resolve the executable directory
            val execDir = resolveExecDir()
            System.setProperty("java.home", execDir)

            // java.library.path → execDir + execDir/bin
            // Must be set BEFORE any System.loadLibrary() call (including HiDPI JNI below).
            // Also flush the ClassLoader cache so System.loadLibrary() picks up the new paths.
            val sep = File.pathSeparator
            System.setProperty("java.library.path", "$execDir$sep$execDir${File.separator}bin")
            resetLibraryPathCache()

            // Early charset init
            Charset.defaultCharset()

            // macOS: recover the OS UI language. SubstrateVM never runs HotSpot's
            // java_props_macosx.c, so the default locale stays the POSIX "C"
            // locale (en) regardless of System Settings. Compose Resources is
            // keyed off Locale.getDefault(), so every translation falls back to
            // the default. We restore JBR behaviour by reading CoreFoundation.
            applyMacOsLocale()
        }

        // Linux HiDPI — must come AFTER java.library.path is configured above,
        // because applyLinuxHiDpiScale() triggers HiDpiLinuxBridge JNI loading.
        // Sets GDK_SCALE via setenv (triggers JDK's native scaling for both
        // rendering AND mouse events) + sun.java2d.uiScale as fallback.
        applyLinuxHiDpiScale()

        if (isNativeImage) {
            try {
                System.loadLibrary("fontmanager")
            } catch (_: Throwable) {
                // Ignore — fontmanager may already be loaded or unavailable
            }
        }
    }

    /**
     * Align [Locale.getDefault] with the macOS UI language under native-image.
     *
     * Faithful to JBR: java_props_macosx.c resolves the default locale from
     * CoreFoundation's preferred languages first and only falls back to
     * `LANG`/`LC_*` when that yields nothing. So we read CoreFoundation
     * unconditionally and leave the env-derived locale untouched only when it
     * returns no language.
     */
    private fun applyMacOsLocale() {
        if (Platform.Current != Platform.MacOS) return
        if (!NativeLocaleBridge.isLoaded) return

        val tag =
            runCatching { NativeLocaleBridge.nativePreferredLanguageTag() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return

        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isNullOrBlank()) return

        Locale.setDefault(locale)
        // Keep user.* in sync for any code reading the properties directly.
        System.setProperty("user.language", locale.language)
        if (locale.country.isNotBlank()) {
            System.setProperty("user.country", locale.country)
        }
        if (locale.script.isNotBlank()) {
            System.setProperty("user.script", locale.script)
        }
    }

    /**
     * Resolve the directory containing the running executable.
     * On Linux, `/proc/self/exe` gives the true absolute path even when the
     * binary is invoked via `PATH` or a symlink.
     */
    private fun resolveExecDir(): String {
        val procSelf = File("/proc/self/exe")
        if (procSelf.exists()) {
            try {
                return procSelf.canonicalFile.parentFile.absolutePath
            } catch (_: Throwable) {
                // fall through
            }
        }
        return File(
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(""),
        ).parentFile?.absolutePath ?: "."
    }

    /**
     * Flush the JVM's cached library search paths.
     *
     * `ClassLoader` caches `java.library.path` into static fields (`sys_paths`
     * and `usr_paths`) on first use and never re-reads the system property.
     * Nullifying these fields forces the next `System.loadLibrary()` call to
     * re-parse `java.library.path`.
     */
    private fun resetLibraryPathCache() {
        try {
            val classLoader = ClassLoader::class.java
            for (fieldName in arrayOf("sys_paths", "usr_paths")) {
                try {
                    val field = classLoader.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(null, null)
                } catch (_: NoSuchFieldException) {
                    // Field may not exist in this JDK/SubstrateVM version
                }
            }
        } catch (_: Throwable) {
            // Reflection may be restricted — loadLibrary will use its default paths
        }
    }
}

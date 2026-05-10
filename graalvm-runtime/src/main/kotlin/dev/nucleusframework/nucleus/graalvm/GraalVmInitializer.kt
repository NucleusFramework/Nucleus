package dev.nucleusframework.nucleus.graalvm

import dev.nucleusframework.nucleus.hidpi.applyLinuxHiDpiScale
import java.io.File
import java.nio.charset.Charset

object GraalVmInitializer {
    val isNativeImage: Boolean =
        System.getProperty("org.graalvm.nativeimage.imagecode") != null

    /** Call once at the very start of main(), before any AWT/Compose usage. */
    fun initialize() {
        if (isNativeImage) {
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

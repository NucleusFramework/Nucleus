package dev.nucleusframework.nucleus.hidpi

/**
 * Returns the native HiDPI scale factor for the current Linux display,
 * mirroring the detection logic of JetBrains Runtime (systemScale.c).
 *
 * Sources consulted in priority order:
 *   1. `J2D_UISCALE`   — explicit JVM override (env var)
 *   2. GSettings       — GNOME `org.gnome.desktop.interface` → `scaling-factor`
 *   3. `GDK_SCALE`     — GTK / GNOME session variable
 *   4. `GDK_DPI_SCALE` — GTK fractional DPI multiplier
 *   5. `Xft.dpi`       — X Resource Manager (KDE, legacy GNOME, …)
 *
 * @return A positive scale factor (e.g. `2.0` for a 200 % HiDPI display),
 *         or `0.0` when the scale cannot be determined (let the JVM decide).
 *
 * **Call this before AWT initialises** (i.e. before `application {}`) and
 * apply the result:
 * ```kotlin
 * val scale = getLinuxNativeScaleFactor()
 * if (scale > 0.0) System.setProperty("sun.java2d.uiScale", scale.toString())
 * ```
 * This function is a no-op on non-Linux platforms and returns `0.0`.
 */
fun getLinuxNativeScaleFactor(): Double {
    if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) return 0.0
    return try {
        HiDpiLinuxBridge.nativeGetScaleFactor()
    } catch (_: Throwable) {
        // JNI unavailable — fall back to environment variables only
        System.getenv("J2D_UISCALE")?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: System.getenv("GDK_SCALE")?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: System.getenv("GDK_DPI_SCALE")?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: 0.0
    }
}

/**
 * Applies the detected HiDPI scale factor for Linux.
 *
 * This function sets up HiDPI scaling in a way that is compatible with
 * both JetBrains Runtime and standard OpenJDK / GraalVM native image:
 *
 * 1. **`GDK_SCALE` environment variable** (via native `setenv`):
 *    Triggers the JDK's native `X11GraphicsDevice.getNativeScaleFactor()`
 *    detection path, which properly configures **both** rendering AND
 *    mouse event coordinate scaling (`XWindow.scaleDown()`).
 *    This is the same path that JBR uses internally.
 *
 * 2. **`sun.java2d.uiScale` system property** (fallback):
 *    For JDKs where the native detection doesn't read `GDK_SCALE`,
 *    this ensures at least the rendering is at HiDPI resolution.
 *    On some JDKs this may not scale mouse events correctly, but
 *    step 1 should handle most cases.
 *
 * **Call this before AWT initialises** (i.e. before `application {}`).
 */
fun applyLinuxHiDpiScale() {
    if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) return
    if (System.getProperty("sun.java2d.uiScale") != null) return // already configured

    val scale = getLinuxNativeScaleFactor()
    if (scale <= 0.0) return

    // Step 1: set GDK_SCALE in the process env so the JDK's native
    // detection path picks it up → full scaling (rendering + input)
    try {
        HiDpiLinuxBridge.nativeApplyScaleToEnv(scale.toInt())
    } catch (_: Throwable) {
        // JNI unavailable — continue with property-only approach
    }

    // Step 2: set sun.java2d.uiScale as backup
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    System.setProperty("sun.java2d.uiScale", scale.toString())
}

package io.github.kdroidfilter.nucleus.taskbarprogress

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.taskbarprogress.linux.LinuxDesktopFileDetector
import io.github.kdroidfilter.nucleus.taskbarprogress.linux.NativeLinuxTaskbarBridge
import io.github.kdroidfilter.nucleus.taskbarprogress.macos.NativeMacOsTaskbarBridge
import io.github.kdroidfilter.nucleus.taskbarprogress.windows.NativeWindowsTaskbarBridge
import java.awt.Window

/**
 * Cross-platform taskbar/dock progress indicator and attention request.
 *
 * Windows: uses ITaskbarList3 COM interface and FlashWindowEx via JNI.
 * macOS: uses NSDockTile with a custom NSProgressIndicator and requestUserAttention via JNI.
 * Linux: uses DBus `com.canonical.Unity.LauncherEntry` protocol (GNOME, KDE, etc.) via JNI.
 */
object TaskbarProgress {
    private const val PROGRESS_MAX = 100L

    /** Last macOS attention request ID for cancellation. */
    @Volatile
    private var macOsAttentionRequestId: Int = -1

    /**
     * Explicit override for the `.desktop` filename on Linux.
     *
     * When set, bypasses all auto-detection. Must match the installed `.desktop` file
     * (e.g. `"myapp.desktop"`).
     *
     * In most cases you do **not** need to set this — the runtime auto-detects it via
     * [LinuxDesktopFileDetector].
     */
    @Volatile
    var linuxDesktopFilename: String? = null

    enum class AttentionType(
        internal val nativeValue: Int,
    ) {
        /** Flash taskbar button briefly (4 times on Windows, bounce once on macOS). */
        INFORMATIONAL(1),

        /** Flash window and taskbar button until the app gets focus (continuous bounce on macOS). */
        CRITICAL(2),
    }

    @Suppress("MagicNumber")
    enum class State(
        internal val flag: Int,
    ) {
        /** No progress displayed. */
        NO_PROGRESS(0x00),

        /** Indeterminate (pulsing) progress. */
        INDETERMINATE(0x01),

        /** Normal progress bar (green on Windows, blue on macOS). */
        NORMAL(0x02),

        /** Error state (red). */
        ERROR(0x04),

        /** Paused state (yellow). */
        PAUSED(0x08),
    }

    /**
     * Returns true if taskbar progress is available on this platform.
     */
    fun isAvailable(): Boolean =
        when (Platform.Current) {
            Platform.Windows -> NativeWindowsTaskbarBridge.isLoaded
            Platform.MacOS -> NativeMacOsTaskbarBridge.isLoaded
            Platform.Linux -> NativeLinuxTaskbarBridge.isLoaded && resolveDesktopFilename() != null
            else -> false
        }

    /**
     * Sets the progress value for the given window.
     *
     * @param window the AWT window whose taskbar button shows the progress
     *   (ignored on macOS — dock progress is app-wide)
     * @param value  progress fraction in 0.0..1.0
     * @return true if the operation succeeded
     */
    fun setProgress(
        window: Window,
        value: Double,
    ): Boolean {
        val clamped = value.coerceIn(0.0, 1.0)
        return when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeWindowsTaskbarBridge.isLoaded) return false
                NativeWindowsTaskbarBridge.nativeSetProgress(
                    window,
                    (clamped * PROGRESS_MAX).toLong(),
                    PROGRESS_MAX,
                ) == 0
            }
            Platform.MacOS -> {
                if (!NativeMacOsTaskbarBridge.isLoaded) return false
                NativeMacOsTaskbarBridge.nativeSetDockProgress(
                    (clamped * PROGRESS_MAX).toLong(),
                    PROGRESS_MAX,
                ) == 0
            }
            Platform.Linux -> {
                val filename = resolveDesktopFilename() ?: return false
                if (!NativeLinuxTaskbarBridge.isLoaded) return false
                NativeLinuxTaskbarBridge.nativeSetProgress(
                    filename,
                    (clamped * PROGRESS_MAX).toLong(),
                    PROGRESS_MAX,
                ) == 0
            }
            else -> false
        }
    }

    /**
     * Sets the progress state for the given window.
     *
     * @param window the AWT window whose taskbar button shows the progress (ignored on macOS)
     * @param state  the desired progress state
     * @return true if the operation succeeded
     */
    fun setState(
        window: Window,
        state: State,
    ): Boolean =
        when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeWindowsTaskbarBridge.isLoaded) return false
                NativeWindowsTaskbarBridge.nativeSetProgressState(window, state.flag) == 0
            }
            Platform.MacOS -> {
                if (!NativeMacOsTaskbarBridge.isLoaded) return false
                NativeMacOsTaskbarBridge.nativeSetDockState(state.flag) == 0
            }
            Platform.Linux -> {
                val filename = resolveDesktopFilename() ?: return false
                if (!NativeLinuxTaskbarBridge.isLoaded) return false
                NativeLinuxTaskbarBridge.nativeSetProgressState(filename, state.flag) == 0
            }
            else -> false
        }

    /** Convenience: shows normal progress at the given value. */
    fun showProgress(
        window: Window,
        value: Double,
    ): Boolean = setState(window, State.NORMAL) && setProgress(window, value)

    /** Convenience: shows error progress at the given value. */
    fun showError(
        window: Window,
        value: Double = 1.0,
    ): Boolean = setState(window, State.ERROR) && setProgress(window, value)

    /** Convenience: shows indeterminate (pulsing) progress. */
    fun showIndeterminate(window: Window): Boolean = setState(window, State.INDETERMINATE)

    /** Convenience: shows paused progress at the given value. */
    fun showPaused(
        window: Window,
        value: Double = 1.0,
    ): Boolean = setState(window, State.PAUSED) && setProgress(window, value)

    /** Hides progress from the taskbar button / dock icon. */
    fun hideProgress(window: Window): Boolean = setState(window, State.NO_PROGRESS)

    /**
     * Requests user attention.
     *
     * - Windows: flashes the taskbar button ([INFORMATIONAL][AttentionType.INFORMATIONAL] = 4 flashes,
     *   [CRITICAL][AttentionType.CRITICAL] = until the app gets focus).
     * - macOS: bounces the dock icon ([INFORMATIONAL][AttentionType.INFORMATIONAL] = once,
     *   [CRITICAL][AttentionType.CRITICAL] = until activated).
     * - Linux: sets the `urgent` flag on the launcher entry via DBus.
     *
     * @param window the AWT window to flash (ignored on macOS/Linux — attention is app-wide)
     * @param type   the attention urgency level
     * @return true if the operation succeeded
     */
    fun requestAttention(
        window: Window,
        type: AttentionType = AttentionType.INFORMATIONAL,
    ): Boolean =
        when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeWindowsTaskbarBridge.isLoaded) return false
                NativeWindowsTaskbarBridge.nativeRequestAttention(window, type.nativeValue) == 0
            }
            Platform.MacOS -> {
                if (!NativeMacOsTaskbarBridge.isLoaded) return false
                val id = NativeMacOsTaskbarBridge.nativeRequestAttention(type.nativeValue)
                macOsAttentionRequestId = id
                id >= 0
            }
            Platform.Linux -> {
                val filename = resolveDesktopFilename() ?: return false
                if (!NativeLinuxTaskbarBridge.isLoaded) return false
                NativeLinuxTaskbarBridge.nativeSetUrgent(filename, true) == 0
            }
            else -> false
        }

    /**
     * Stops any active attention request (window flashing / dock bouncing).
     *
     * @param window the AWT window (ignored on macOS/Linux)
     * @return true if the operation succeeded
     */
    fun stopAttention(window: Window): Boolean =
        when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeWindowsTaskbarBridge.isLoaded) return false
                NativeWindowsTaskbarBridge.nativeRequestAttention(window, 0) == 0
            }
            Platform.MacOS -> {
                if (!NativeMacOsTaskbarBridge.isLoaded) return false
                val id = macOsAttentionRequestId
                if (id >= 0) {
                    NativeMacOsTaskbarBridge.nativeCancelAttention(id)
                    macOsAttentionRequestId = -1
                }
                true
            }
            Platform.Linux -> {
                val filename = resolveDesktopFilename() ?: return false
                if (!NativeLinuxTaskbarBridge.isLoaded) return false
                NativeLinuxTaskbarBridge.nativeSetUrgent(filename, false) == 0
            }
            else -> false
        }

    /**
     * AWT-free variant of [setProgress]: takes a raw Windows HWND. On
     * macOS/Linux the [hwnd] argument is ignored (the underlying APIs are
     * app-wide). Intended for non-AWT backends (e.g. Tao).
     */
    fun setProgressForHwnd(
        hwnd: Long,
        value: Double,
    ): Boolean {
        val clamped = value.coerceIn(0.0, 1.0)
        return when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeWindowsTaskbarBridge.isLoaded) return false
                NativeWindowsTaskbarBridge.nativeSetProgressByHwnd(
                    hwnd,
                    (clamped * PROGRESS_MAX).toLong(),
                    PROGRESS_MAX,
                ) == 0
            }
            Platform.MacOS -> {
                if (!NativeMacOsTaskbarBridge.isLoaded) return false
                NativeMacOsTaskbarBridge.nativeSetDockProgress(
                    (clamped * PROGRESS_MAX).toLong(),
                    PROGRESS_MAX,
                ) == 0
            }
            Platform.Linux -> {
                val filename = resolveDesktopFilename() ?: return false
                if (!NativeLinuxTaskbarBridge.isLoaded) return false
                NativeLinuxTaskbarBridge.nativeSetProgress(
                    filename,
                    (clamped * PROGRESS_MAX).toLong(),
                    PROGRESS_MAX,
                ) == 0
            }
            else -> false
        }
    }

    /** AWT-free variant of [setState]. */
    fun setStateForHwnd(
        hwnd: Long,
        state: State,
    ): Boolean =
        when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeWindowsTaskbarBridge.isLoaded) return false
                NativeWindowsTaskbarBridge.nativeSetProgressStateByHwnd(hwnd, state.flag) == 0
            }
            Platform.MacOS -> {
                if (!NativeMacOsTaskbarBridge.isLoaded) return false
                NativeMacOsTaskbarBridge.nativeSetDockState(state.flag) == 0
            }
            Platform.Linux -> {
                val filename = resolveDesktopFilename() ?: return false
                if (!NativeLinuxTaskbarBridge.isLoaded) return false
                NativeLinuxTaskbarBridge.nativeSetProgressState(filename, state.flag) == 0
            }
            else -> false
        }

    /** AWT-free variant of [requestAttention]. */
    fun requestAttentionForHwnd(
        hwnd: Long,
        type: AttentionType = AttentionType.INFORMATIONAL,
    ): Boolean =
        when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeWindowsTaskbarBridge.isLoaded) return false
                NativeWindowsTaskbarBridge.nativeRequestAttentionByHwnd(hwnd, type.nativeValue) == 0
            }
            Platform.MacOS -> {
                if (!NativeMacOsTaskbarBridge.isLoaded) return false
                val id = NativeMacOsTaskbarBridge.nativeRequestAttention(type.nativeValue)
                macOsAttentionRequestId = id
                id >= 0
            }
            Platform.Linux -> {
                val filename = resolveDesktopFilename() ?: return false
                if (!NativeLinuxTaskbarBridge.isLoaded) return false
                NativeLinuxTaskbarBridge.nativeSetUrgent(filename, true) == 0
            }
            else -> false
        }

    /** AWT-free variant of [stopAttention]. */
    fun stopAttentionForHwnd(hwnd: Long): Boolean =
        when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeWindowsTaskbarBridge.isLoaded) return false
                NativeWindowsTaskbarBridge.nativeRequestAttentionByHwnd(hwnd, 0) == 0
            }
            Platform.MacOS -> {
                if (!NativeMacOsTaskbarBridge.isLoaded) return false
                val id = macOsAttentionRequestId
                if (id >= 0) {
                    NativeMacOsTaskbarBridge.nativeCancelAttention(id)
                    macOsAttentionRequestId = -1
                }
                true
            }
            Platform.Linux -> {
                val filename = resolveDesktopFilename() ?: return false
                if (!NativeLinuxTaskbarBridge.isLoaded) return false
                NativeLinuxTaskbarBridge.nativeSetUrgent(filename, false) == 0
            }
            else -> false
        }

    private fun resolveDesktopFilename(): String? = linuxDesktopFilename ?: LinuxDesktopFileDetector.desktopFilename
}

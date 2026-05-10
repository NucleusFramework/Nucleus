package dev.nucleusframework.nucleus.taskbarprogress.linux

import dev.nucleusframework.nucleus.launcher.linux.LauncherProperties
import dev.nucleusframework.nucleus.launcher.linux.LinuxLauncherEntry

private const val STATE_NO_PROGRESS = 0x00

internal object NativeLinuxTaskbarBridge {
    val isLoaded: Boolean get() = LinuxLauncherEntry.isAvailable

    @Volatile
    private var progressValue: Double = 0.0

    @Volatile
    private var progressVisible: Boolean = false

    fun nativeSetProgress(
        desktopFilename: String,
        completed: Long,
        total: Long,
    ): Int {
        progressValue = if (total > 0) completed.toDouble() / total.toDouble() else 0.0
        if (!progressVisible) progressVisible = true
        val ok =
            LinuxLauncherEntry.update(
                LinuxLauncherEntry.appUri(desktopFilename),
                LauncherProperties(
                    progress = progressValue,
                    progressVisible = progressVisible,
                ),
            )
        return if (ok) 0 else -1
    }

    fun nativeSetProgressState(
        desktopFilename: String,
        flags: Int,
    ): Int {
        progressVisible = flags != STATE_NO_PROGRESS
        val ok =
            LinuxLauncherEntry.update(
                LinuxLauncherEntry.appUri(desktopFilename),
                LauncherProperties(
                    progress = progressValue,
                    progressVisible = progressVisible,
                ),
            )
        return if (ok) 0 else -1
    }

    fun nativeSetUrgent(
        desktopFilename: String,
        urgent: Boolean,
    ): Int {
        val ok = LinuxLauncherEntry.setUrgent(LinuxLauncherEntry.appUri(desktopFilename), urgent)
        return if (ok) 0 else -1
    }
}

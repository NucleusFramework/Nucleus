package dev.nucleusframework.lab.probes.shell.badge

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.probes.shell.common.LauncherIdentity
import dev.nucleusframework.launcher.linux.LinuxLauncherEntry
import dev.nucleusframework.launcher.windows.BadgeGlyph
import dev.nucleusframework.launcher.windows.WindowsBadgeManager
import dev.nucleusframework.notification.NotificationCenter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Which OS surface carries the badge here. */
enum class BadgeBackend(
    val label: String,
) {
    MacOsDock("Dock tile (UNUserNotificationCenter badge)"),
    WindowsTaskbar("Taskbar button (WinRT BadgeUpdater)"),
    UnityLauncher("Launcher entry (com.canonical.Unity.LauncherEntry)"),
    None("none"),
}

/** Port over the three badge APIs: `WindowsBadgeManager`, `LinuxLauncherEntry`, `NotificationCenter`. */
interface BadgeGateway {
    val backend: BadgeBackend

    fun availability(): Availability

    /** Identity the badge is attached to: AUMID, desktop file id or bundle. */
    fun target(): String?

    /** Windows needs an explicit `initialize`; the others have nothing to prepare. */
    suspend fun prepare(): CallOutcome

    suspend fun setCount(count: Int): CallOutcome

    /** Windows only. */
    suspend fun setGlyph(glyph: BadgeGlyph): CallOutcome

    suspend fun clear(): CallOutcome

    /** The count the OS reports, or `null` where the platform has no read API. */
    suspend fun readBack(): Int?

    val canReadBack: Boolean get() = backend == BadgeBackend.MacOsDock
    val supportsGlyphs: Boolean get() = backend == BadgeBackend.WindowsTaskbar
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusBadgeGateway : BadgeGateway {
    override val backend: BadgeBackend =
        when (Platform.Current) {
            Platform.MacOS -> BadgeBackend.MacOsDock
            Platform.Windows -> BadgeBackend.WindowsTaskbar
            Platform.Linux -> BadgeBackend.UnityLauncher
            else -> BadgeBackend.None
        }

    private val desktopFile: String? get() = LauncherIdentity.linuxDesktopFile

    override fun availability(): Availability =
        when (backend) {
            BadgeBackend.MacOsDock ->
                Availability.of(NotificationCenter.isAvailable) { Availability.NEEDS_APP_BUNDLE }
            BadgeBackend.WindowsTaskbar ->
                LauncherIdentity.windowsLauncher(WindowsBadgeManager.isAvailable)
            BadgeBackend.UnityLauncher -> LauncherIdentity.linuxLauncherEntry()
            BadgeBackend.None -> Availability.Unavailable("no badge surface on this platform")
        }

    override fun target(): String? =
        when (backend) {
            BadgeBackend.MacOsDock -> NucleusApp.appId
            BadgeBackend.WindowsTaskbar -> NucleusApp.aumid
            BadgeBackend.UnityLauncher -> desktopFile?.let(LauncherIdentity::linuxAppUri)
            BadgeBackend.None -> null
        }

    override suspend fun prepare(): CallOutcome =
        when (backend) {
            BadgeBackend.WindowsTaskbar ->
                CallOutcome.of(
                    WindowsBadgeManager.initialize(),
                ) { WindowsBadgeManager.lastError }
            else -> CallOutcome.Ok
        }

    override suspend fun setCount(count: Int): CallOutcome =
        when (backend) {
            BadgeBackend.MacOsDock -> macOsSetBadge(count)
            BadgeBackend.WindowsTaskbar ->
                CallOutcome.of(
                    WindowsBadgeManager.setCount(count),
                ) { WindowsBadgeManager.lastError }
            BadgeBackend.UnityLauncher -> linux { LinuxLauncherEntry.setCount(it, count.toLong(), visible = count > 0) }
            BadgeBackend.None -> CallOutcome(false, "unsupported")
        }

    override suspend fun setGlyph(glyph: BadgeGlyph): CallOutcome =
        if (backend == BadgeBackend.WindowsTaskbar) {
            CallOutcome.of(WindowsBadgeManager.setGlyph(glyph)) { WindowsBadgeManager.lastError }
        } else {
            CallOutcome(false, "glyph badges exist on Windows only")
        }

    override suspend fun clear(): CallOutcome =
        when (backend) {
            BadgeBackend.MacOsDock -> macOsSetBadge(0)
            BadgeBackend.WindowsTaskbar -> CallOutcome.of(WindowsBadgeManager.clear()) { WindowsBadgeManager.lastError }
            BadgeBackend.UnityLauncher -> linux { LinuxLauncherEntry.clearCount(it) }
            BadgeBackend.None -> CallOutcome(false, "unsupported")
        }

    override suspend fun readBack(): Int? {
        if (backend != BadgeBackend.MacOsDock || !NotificationCenter.isAvailable) return null
        return suspendCancellableCoroutine { cont -> NotificationCenter.getBadgeCount { cont.resume(it) } }
    }

    private suspend fun macOsSetBadge(count: Int): CallOutcome =
        suspendCancellableCoroutine { cont ->
            NotificationCenter.setBadgeCount(count) { error ->
                cont.resume(
                    if (error == null) {
                        CallOutcome.Ok
                    } else {
                        CallOutcome(false, error)
                    },
                )
            }
        }

    private inline fun linux(call: (String) -> Boolean): CallOutcome {
        val uri = desktopFile?.let(LauncherIdentity::linuxAppUri) ?: return CallOutcome(false, "no desktop file id")
        return CallOutcome.of(call(uri)) { "D-Bus signal not emitted" }
    }
}

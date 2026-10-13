package dev.nucleusframework.lab.core.environment

import androidx.compose.runtime.Immutable
import dev.nucleusframework.aot.runtime.AotRuntime
import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoMonitors
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.management.ManagementFactory

@Immutable
data class MonitorInfo(
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
    val primary: Boolean,
)

/** Where the Lab is running: what every bug report needs and every check is keyed on. */
@Immutable
data class EnvironmentSnapshot(
    val platform: Platform,
    val osName: String,
    val osVersion: String,
    val arch: String,
    val displayServer: String?,
    val desktop: String?,
    val javaVersion: String,
    val javaVendor: String,
    val executableType: String,
    val nativeImage: Boolean,
    val sandboxed: Boolean,
    val aotMode: String,
    val appId: String,
    val appVersion: String?,
    val monitors: List<MonitorInfo>,
    /** Every `nucleus.*` system property set on this JVM. */
    val nucleusProperties: Map<String, String>,
    val jvmArguments: List<String>,
) {
    /** Checks are stored per fingerprint: a pass on macOS/DMG says nothing about Linux/Wayland/DEV. */
    val fingerprint: String
        get() = listOfNotNull(platform.name, arch, displayServer, executableType).joinToString("/")

    val summary: String
        get() =
            buildString {
                append("$osName $osVersion ($arch)")
                displayServer?.let { append(" · $it") }
                append(" · JDK $javaVersion · $executableType")
                if (sandboxed) append(" · sandboxed")
                if (aotMode != "OFF") append(" · AOT $aotMode")
            }
}

@SingleIn(AppScope::class)
@Inject
class EnvironmentProvider {
    private val state = MutableStateFlow(read(monitors = emptyList()))
    val snapshot: StateFlow<EnvironmentSnapshot> = state.asStateFlow()

    /** Re-reads everything, monitors included; call from the UI thread once Tao runs. */
    fun refresh() {
        val monitors =
            runCatching {
                TaoMonitors.all().map {
                    MonitorInfo(it.name, it.boundsPx.width, it.boundsPx.height, it.scaleFactor, it.isPrimary)
                }
            }.getOrDefault(emptyList())
        state.value = read(monitors)
    }

    private fun read(monitors: List<MonitorInfo>): EnvironmentSnapshot {
        val platform = Platform.Current
        return EnvironmentSnapshot(
            platform = platform,
            osName = System.getProperty("os.name"),
            osVersion = System.getProperty("os.version"),
            arch = System.getProperty("os.arch"),
            displayServer =
                when {
                    platform != Platform.Linux -> null
                    Platform.isWayland -> "Wayland"
                    else -> "X11"
                },
            desktop = if (platform == Platform.Linux) LinuxDesktopEnvironment.Current.name else null,
            javaVersion = System.getProperty("java.version"),
            javaVendor = System.getProperty("java.vendor"),
            executableType = ExecutableRuntime.type().name,
            nativeImage = ExecutableRuntime.isGraalVmNativeImage,
            sandboxed = ExecutableRuntime.isSandboxed(),
            aotMode = AotRuntime.mode().name,
            appId = NucleusApp.appId,
            appVersion = NucleusApp.version,
            monitors = monitors,
            nucleusProperties =
                System
                    .getProperties()
                    .stringPropertyNames()
                    .filter { it.startsWith("nucleus.") }
                    .sorted()
                    .associateWith { System.getProperty(it) },
            jvmArguments =
                runCatching { ManagementFactory.getRuntimeMXBean().inputArguments }.getOrDefault(
                    emptyList(),
                ),
        )
    }
}

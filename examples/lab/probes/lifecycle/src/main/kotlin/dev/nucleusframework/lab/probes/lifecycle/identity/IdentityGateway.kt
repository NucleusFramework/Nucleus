package dev.nucleusframework.lab.probes.lifecycle.identity

import androidx.compose.runtime.Immutable
import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.lab.core.environment.EnvironmentProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.lang.management.ManagementFactory

/** Who the app thinks it is and how it was started, every field as the runtime resolves it. */
@Immutable
data class Identity(
    val appId: String,
    val appName: String?,
    val version: String?,
    val vendor: String?,
    val description: String?,
    val aumid: String,
    val startupTaskId: String?,
    val pluginMetadata: Boolean,
    val executableType: String,
    val typeProperty: String?,
    val markerVersion: String?,
    val sandboxed: Boolean,
    val nativeImage: Boolean,
    val aotMode: String,
    val aotCacheArgument: String?,
    val jpackageVersion: String?,
    val launcherPath: String?,
    val javaHome: String,
    val uptimeMillis: Long,
)

interface IdentityGateway {
    fun read(): Identity
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusIdentityGateway(
    private val environment: EnvironmentProvider,
) : IdentityGateway {
    override fun read(): Identity {
        val runtime = ManagementFactory.getRuntimeMXBean()
        // What every bug report carries comes from the shared snapshot; the rest is identity-only.
        val snapshot = environment.snapshot.value
        return Identity(
            appId = snapshot.appId,
            appName = NucleusApp.appName,
            version = snapshot.appVersion,
            vendor = NucleusApp.vendor,
            description = NucleusApp.description,
            aumid = NucleusApp.aumid,
            startupTaskId = NucleusApp.startupTaskId,
            pluginMetadata = NucleusApp.isConfigured,
            executableType = snapshot.executableType,
            typeProperty = System.getProperty(ExecutableRuntime.TYPE_PROPERTY),
            markerVersion = ExecutableRuntime.markerVersion(),
            sandboxed = snapshot.sandboxed,
            nativeImage = snapshot.nativeImage,
            aotMode = snapshot.aotMode,
            aotCacheArgument =
                snapshot.jvmArguments.firstOrNull { it.startsWith("-XX:AOTCache") || it.startsWith("-XX:AOTMode") },
            jpackageVersion = System.getProperty("jpackage.app-version"),
            launcherPath =
                System.getProperty("jpackage.app-path") ?: ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null),
            javaHome = System.getProperty("java.home"),
            uptimeMillis = runtime.uptime,
        )
    }
}

package dev.nucleusframework.lab.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.nucleusframework.core.runtime.Platform

/** Stable identifier of a probe, `<domain>.<name>`; also the path of its deep link. */
@JvmInline
value class ProbeId(
    val value: String,
) {
    override fun toString(): String = value
}

/** Sidebar grouping. Declaration order is display order. */
enum class Domain(
    val title: String,
) {
    Lab("Lab"),
    Window("Window & chrome"),
    Workspace("Workspace"),
    Input("Input"),
    Rendering("Rendering"),
    Shell("Shell integration"),
    Notifications("Notifications"),
    Lifecycle("Lifecycle"),
    Updater("Updater"),
    System("System"),
    Network("Network"),
    Fixtures("Fixtures"),
}

/** One manual verification the tester ticks: what must be seen, not how to make it happen. */
@Immutable
data class Check(
    val id: String,
    val description: String,
)

@Immutable
data class ProbeDescriptor(
    val id: ProbeId,
    val title: String,
    val domain: Domain,
    /** One sentence: the question this probe answers. */
    val summary: String,
    /** Gradle modules under test, as named in `settings.gradle.kts` (`launcher-windows`). */
    val modules: List<String>,
    val platforms: Set<Platform> = ALL_PLATFORMS,
    val checks: List<Check> = emptyList(),
    /** Extra words the command palette matches on. */
    val keywords: List<String> = emptyList(),
) {
    val supportsCurrentPlatform: Boolean get() = Platform.Current in platforms

    companion object {
        val ALL_PLATFORMS: Set<Platform> = setOf(Platform.MacOS, Platform.Windows, Platform.Linux)
    }
}

/**
 * A screen of the Lab, contributed to the graph with `@ContributesIntoSet(AppScope::class)`.
 *
 * The shell owns everything around [Content] — header, checks, timeline — so a probe only
 * draws its capabilities, controls and observed state (see `ProbeLayout` in designsystem).
 * Its ViewModel is obtained with `metroViewModel()` and lives until the probe is reset.
 */
interface Probe {
    val descriptor: ProbeDescriptor

    @Composable
    fun Content()
}

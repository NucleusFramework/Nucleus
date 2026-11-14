package dev.nucleusframework.desktop.application.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logging

/**
 * The Nucleus runtime (`decorated-window-tao` and everything above it) is
 * compiled against Compose 1.13 internals: Skiko backend registration, the
 * group-qualified mangled names, `PlatformContext` members. On an older
 * Compose it fails at run time only (`NoClassDefFoundError`,
 * `NoSuchMethodError`), so the build checks the versions up front.
 */
internal const val MIN_COMPOSE_MAJOR = 1
internal const val MIN_COMPOSE_MINOR = 13
private const val MIN_COMPOSE = "$MIN_COMPOSE_MAJOR.$MIN_COMPOSE_MINOR"

private const val COMPOSE_UI_GROUP = "org.jetbrains.compose.ui"
private const val COMPOSE_UI = "ui-desktop"
private const val JETBRAINS_COMPOSE_PLUGIN = "org.jetbrains.compose"

/** A resolved `group:name:version` of the runtime classpath. */
internal data class ResolvedModule(
    val group: String,
    val name: String,
    val version: String,
)

/**
 * Fails the resolution of every non-test runtime classpath that carries the
 * Nucleus Tao runtime on a Compose older than 1.13, and warns when the
 * JetBrains Compose Gradle plugin is older (it injects its own hot reload
 * agent, which the Tao hot reload bridge is not binary compatible with).
 */
internal fun configureComposeVersionCheck(project: Project) {
    project.configurations.configureEach { configuration ->
        val name = configuration.name
        if (!name.endsWith("RuntimeClasspath", ignoreCase = true) || name.contains("Test", ignoreCase = true)) {
            return@configureEach
        }
        configuration.incoming.afterResolve { dependencies ->
            val modules =
                dependencies.resolutionResult.allComponents.mapNotNull { component ->
                    component.moduleVersion?.let { ResolvedModule(it.group, it.name, it.version) }
                }
            composeRuntimeVersionProblem(modules)?.let { problem -> throw GradleException("$name: $problem") }
        }
    }
    project.pluginManager.withPlugin(JETBRAINS_COMPOSE_PLUGIN) { applied ->
        val version = jetbrainsComposePluginVersion(project, applied.id) ?: return@withPlugin
        composePluginVersionProblem(version)?.let { Logging.getLogger(JETBRAINS_COMPOSE_PLUGIN).warn(it) }
    }
}

/** Why [modules] cannot run the Nucleus Tao runtime, or `null` when they can. */
internal fun composeRuntimeVersionProblem(modules: Collection<ResolvedModule>): String? {
    val tao = modules.firstOrNull { it.name in DECORATED_WINDOW_TAO_DEPENDENCY_NAMES } ?: return null
    val compose = modules.firstOrNull { it.group == COMPOSE_UI_GROUP && it.name == COMPOSE_UI } ?: return null
    if (isAtLeastMinCompose(compose.version) != false) return null
    val nucleus = if (tao.version == "unspecified") "Nucleus" else "Nucleus ${tao.version}"
    return "$nucleus requires Compose Multiplatform $MIN_COMPOSE or newer, but " +
        "$COMPOSE_UI_GROUP:$COMPOSE_UI resolved to ${compose.version}. It would fail at run time " +
        "(NoClassDefFoundError / NoSuchMethodError). Remove the constraint that pins Compose " +
        "(strictly / force / a BOM) or move it to $MIN_COMPOSE."
}

/** The warning for a JetBrains Compose Gradle plugin older than 1.13, or `null`. */
internal fun composePluginVersionProblem(pluginVersion: String): String? {
    if (isAtLeastMinCompose(pluginVersion) != false) return null
    return "The Compose Multiplatform Gradle plugin ($JETBRAINS_COMPOSE_PLUGIN $pluginVersion) is older than " +
        "$MIN_COMPOSE, which Nucleus requires. Gradle upgrades the Compose libraries to $MIN_COMPOSE anyway, " +
        "but the plugin injects an older Compose Hot Reload agent that the Nucleus Tao backend does not " +
        "support. Apply $JETBRAINS_COMPOSE_PLUGIN $MIN_COMPOSE or newer."
}

/**
 * `true` / `false` when [version] reads as `major.minor…`, `null` when it does
 * not (a snapshot or custom build): an unreadable version is not reported.
 */
internal fun isAtLeastMinCompose(version: String): Boolean? {
    val match = Regex("""^(\d+)\.(\d+)""").find(version) ?: return null
    val major = match.groupValues[1].toInt()
    val minor = match.groupValues[2].toInt()
    return major > MIN_COMPOSE_MAJOR || (major == MIN_COMPOSE_MAJOR && minor >= MIN_COMPOSE_MINOR)
}

// The JetBrains plugin publishes its version as a constant; reached by
// reflection so Nucleus does not depend on that plugin.
private fun jetbrainsComposePluginVersion(
    project: Project,
    pluginId: String,
): String? =
    runCatching {
        val plugin = project.plugins.getPlugin(pluginId)
        plugin.javaClass.classLoader
            .loadClass("org.jetbrains.compose.ComposeBuildConfig")
            .getField("composeGradlePluginVersion")
            .get(null) as String
    }.getOrNull()

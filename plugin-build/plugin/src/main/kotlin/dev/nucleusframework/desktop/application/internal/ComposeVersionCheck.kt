package dev.nucleusframework.desktop.application.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logging

/**
 * The Nucleus runtime (`decorated-window-tao` and everything above it) is
 * compiled against Compose 1.13 internals: Skiko backend registration, the
 * group-qualified mangled names, `PlatformContext` members. On an older
 * Compose it fails at run time only (`NoClassDefFoundError`,
 * `NoSuchMethodError`), so the build checks the versions up front. A newer
 * minor line may move those internals again, so it is reported as untested.
 */
private const val TESTED_COMPOSE_MAJOR = 1
private const val TESTED_COMPOSE_MINOR = 13
private const val TESTED_COMPOSE = "$TESTED_COMPOSE_MAJOR.$TESTED_COMPOSE_MINOR"

private const val COMPOSE_UI_GROUP = "org.jetbrains.compose.ui"
private const val COMPOSE_UI = "ui-desktop"
private const val JETBRAINS_COMPOSE_PLUGIN = "org.jetbrains.compose"

/** A resolved `group:name:version` of the runtime classpath. */
internal data class ResolvedModule(
    val group: String,
    val name: String,
    val version: String,
)

/** Where a Compose version sits against the line this Nucleus was built and tested on. */
internal enum class ComposeLine {
    /** Older than 1.13: the Tao runtime cannot run on it. */
    TOO_OLD,

    /** 1.13.x, any patch or prerelease. */
    TESTED,

    /** 1.14 or newer: may work, never verified. */
    UNTESTED,
}

/** A finding of the check: [fatal] ones fail the build, the others are warnings. */
internal data class ComposeVersionFinding(
    val message: String,
    val fatal: Boolean,
)

/**
 * Checks every non-test runtime classpath that carries the Nucleus Tao
 * runtime: a Compose older than 1.13 fails the build, a newer minor line is a
 * warning. The JetBrains Compose Gradle plugin is checked too (it injects its
 * own Hot Reload agent, which the Tao bridge is built against); there both
 * cases are warnings, since only hot reload is at stake.
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
            val finding = composeRuntimeVersionFinding(modules) ?: return@afterResolve
            if (finding.fatal) throw GradleException("$name: ${finding.message}")
            Logging.getLogger(ComposeVersionFinding::class.java).warn("$name: ${finding.message}")
        }
    }
    project.pluginManager.withPlugin(JETBRAINS_COMPOSE_PLUGIN) { applied ->
        val version = jetbrainsComposePluginVersion(project, applied.id) ?: return@withPlugin
        val warning = composePluginVersionWarning(version) ?: return@withPlugin
        // One plugin version per build: say it once, not once per module.
        val extra = project.gradle.extensions.extraProperties
        if (extra.has(PLUGIN_WARNED_KEY)) return@withPlugin
        extra.set(PLUGIN_WARNED_KEY, true)
        Logging.getLogger(JETBRAINS_COMPOSE_PLUGIN).warn(warning)
    }
}

private const val PLUGIN_WARNED_KEY = "dev.nucleusframework.composePluginVersionWarned"

/** What the resolved [modules] mean for the Nucleus Tao runtime, or `null` when all is well. */
internal fun composeRuntimeVersionFinding(modules: Collection<ResolvedModule>): ComposeVersionFinding? {
    val tao = modules.firstOrNull { it.name in DECORATED_WINDOW_TAO_DEPENDENCY_NAMES } ?: return null
    val compose = modules.firstOrNull { it.group == COMPOSE_UI_GROUP && it.name == COMPOSE_UI } ?: return null
    val nucleus = if (tao.version == "unspecified") "Nucleus" else "Nucleus ${tao.version}"
    val resolved = "$COMPOSE_UI_GROUP:$COMPOSE_UI resolved to ${compose.version}"
    return when (composeLine(compose.version)) {
        ComposeLine.TOO_OLD ->
            ComposeVersionFinding(
                "$nucleus requires Compose Multiplatform $TESTED_COMPOSE or newer, but $resolved. " +
                    "It would fail at run time (NoClassDefFoundError / NoSuchMethodError). Remove the " +
                    "constraint that pins Compose (strictly / force / a BOM) or move it to $TESTED_COMPOSE.",
                fatal = true,
            )
        ComposeLine.UNTESTED ->
            ComposeVersionFinding(
                "$nucleus was built and tested against Compose Multiplatform $TESTED_COMPOSE.x, but $resolved. " +
                    "The Tao backend reaches into Compose internals that a new minor version may change; " +
                    "it may work, but this combination has not been tested.",
                fatal = false,
            )
        ComposeLine.TESTED, null -> null
    }
}

/** The warning for a JetBrains Compose Gradle plugin outside the 1.13 line, or `null`. */
internal fun composePluginVersionWarning(pluginVersion: String): String? {
    val plugin = "The Compose Multiplatform Gradle plugin ($JETBRAINS_COMPOSE_PLUGIN $pluginVersion)"
    return when (composeLine(pluginVersion)) {
        ComposeLine.TOO_OLD ->
            "$plugin is older than $TESTED_COMPOSE, which Nucleus requires. Gradle upgrades the Compose " +
                "libraries to $TESTED_COMPOSE anyway, but the plugin injects an older Compose Hot Reload agent " +
                "that the Nucleus Tao backend does not support. Apply $JETBRAINS_COMPOSE_PLUGIN $TESTED_COMPOSE."
        ComposeLine.UNTESTED ->
            "$plugin is newer than the $TESTED_COMPOSE.x line Nucleus was tested with. Its Compose Hot Reload " +
                "agent may not match the Nucleus Tao backend; hot reload has not been tested with it."
        ComposeLine.TESTED, null -> null
    }
}

/**
 * Classifies [version] by its `major.minor`. `null` when it does not read as
 * one (a snapshot or custom build): an unreadable version is not reported.
 */
internal fun composeLine(version: String): ComposeLine? {
    val match = Regex("""^(\d+)\.(\d+)""").find(version) ?: return null
    val major = match.groupValues[1].toInt()
    val minor = match.groupValues[2].toInt()
    return when {
        major < TESTED_COMPOSE_MAJOR || (major == TESTED_COMPOSE_MAJOR && minor < TESTED_COMPOSE_MINOR) ->
            ComposeLine.TOO_OLD
        major == TESTED_COMPOSE_MAJOR && minor == TESTED_COMPOSE_MINOR -> ComposeLine.TESTED
        else -> ComposeLine.UNTESTED
    }
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

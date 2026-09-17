/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.NucleusExtension
import dev.nucleusframework.desktop.tasks.AbstractUnpackDefaultApplicationResourcesTask
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentOS
import dev.nucleusframework.internal.utils.registerTask
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.JavaExec

// Published Maven artifact id (`dev.nucleusframework:nucleus.decorated-window-tao`)
// and monorepo project name (`:decorated-window-tao`). Dependency.getName() returns
// the module/project name only — never the group id.
private val DECORATED_WINDOW_TAO_DEPENDENCY_NAMES =
    setOf(
        "nucleus.decorated-window-tao",
        "decorated-window-tao",
    )

internal fun configureDesktop(
    project: Project,
    nucleusExtension: NucleusExtension,
) {
    if (nucleusExtension.isJvmApplicationInitialized) {
        checkNoComposeDesktopApplication(project)
        val appInternal = nucleusExtension.application as JvmApplicationInternal
        val defaultBuildType = appInternal.data.buildTypes.default
        val appData = JvmApplicationContext(project, appInternal, defaultBuildType)
        appData.configureJvmApplication()

        if (appInternal.data.graalvm.isEnabled
                .getOrElse(false)
        ) {
            appData.configureGraalvmApplication()
        }

        propagateMainClassToHotReloadRun(project, appInternal)
        injectStartOnFirstThreadForTaoHotReload(project)
    }

    if (nucleusExtension.isNativeApplicationInitialized) {
        val unpackDefaultResources =
            project.registerTask<AbstractUnpackDefaultApplicationResourcesTask>(
                "unpackDefaultNativeApplicationResources",
            ) {}
        configureNativeApplication(project, nucleusExtension.nativeApplication, unpackDefaultResources)
    }
}

private const val COMPOSE_HOT_RUN_TASK = "org.jetbrains.compose.reload.gradle.ComposeHotRun"
private const val COMPOSE_HOT_DEV_RUN_TASK = "org.jetbrains.compose.reload.gradle.ComposeHotDevRun"

/**
 * Makes Compose Hot Reload's run tasks work out of the box on Nucleus projects.
 *
 * `hotRun`: the hot-reload plugin resolves `mainClass` from a convention chain ending in
 * `compose.desktop.application.mainClass` — but that extension is only initialized lazily at
 * execution time, so in Nucleus projects (where `compose.desktop.application { }` must stay
 * unconfigured, see [checkNoComposeDesktopApplication]) the chain always comes up empty and
 * `hotRun` fails with "Missing 'mainClass' property". `ComposeHotRun` is a plain [JavaExec],
 * so [dev.nucleusframework.desktop.application.dsl.JvmApplication.mainClass] is set as the
 * standard `JavaExec.mainClass` convention instead. The hot-reload plugin's own overrides are
 * replicated ahead of the Nucleus default, so `-PmainClass=...` / `-DmainClass=...` still win;
 * `--mainClass=...` sets the property directly and bypasses the convention entirely.
 *
 * `hotDev`: `ComposeHotDevRun` renders a single `@Composable` via
 * `org.jetbrains.compose.reload.jvm.DevApplication --className=... --funName=...`, parameters
 * the IDE run-gutter injects (`-PclassName` / `-PfunName`) — invoked bare it fails while the
 * argfile is serialized ("property 'className' has no value"), and no project-level default
 * `@Composable` exists to point it at. When no `className` is provided the task is redirected
 * to the application's own main class, i.e. a bare `hotDev` behaves exactly like `hotRun`;
 * with `-PclassName` the stock dev-composable behavior is untouched.
 *
 * Task types are identified by class name to avoid a compile-time dependency on the
 * hot-reload Gradle plugin (mirrors [injectStartOnFirstThreadForTaoHotReload]).
 */
private fun propagateMainClassToHotReloadRun(
    project: Project,
    appInternal: JvmApplicationInternal,
) {
    val mainClass = appInternal.data.mainClass ?: return
    val mainClassConvention =
        project.providers
            .gradleProperty("mainClass")
            .orElse(project.providers.systemProperty("mainClass"))
            .orElse(mainClass)
    val classNameProvided =
        project.providers
            .gradleProperty("className")
            .orElse(project.providers.systemProperty("className"))
            .isPresent
    project.tasks.withType(JavaExec::class.java).configureEach { task ->
        when {
            task.isSubtypeOf(COMPOSE_HOT_RUN_TASK) -> task.mainClass.convention(mainClassConvention)
            !classNameProvided && task.isSubtypeOf(COMPOSE_HOT_DEV_RUN_TASK) ->
                task.redirectBareHotDevToAppMain(mainClass)
        }
    }
}

/**
 * Points a `hotDev` invoked without `-PclassName` at the app's main class instead of
 * `DevApplication`. `className`/`funName` still need serializable values (the argfile task
 * queries them, and they were assigned with `.value(<empty provider>)`, which shadows any
 * convention) — the placeholders end up as `--className`/`--funName` program args that a
 * regular `main` ignores. Reflection keeps the hot-reload plugin off the compile classpath.
 */
private fun JavaExec.redirectBareHotDevToAppMain(appMainClass: String) {
    runCatching {
        setHotDevProperty("getClassName\$hot_reload_gradle_plugin", appMainClass)
        setHotDevProperty("getFunName\$hot_reload_gradle_plugin", "main")
        mainClass.set(appMainClass)
    }
}

private fun JavaExec.setHotDevProperty(
    getterName: String,
    value: String,
) {
    @Suppress("UNCHECKED_CAST")
    val property =
        javaClass.methods.first { it.name == getterName }.invoke(this)
            as org.gradle.api.provider.Property<String>
    property.set(value)
}

private fun JavaExec.isSubtypeOf(taskClassName: String): Boolean =
    generateSequence<Class<*>>(javaClass) { it.superclass }
        .any { it.name == taskClassName }

/**
 * Fails with an actionable message when both `nucleus.application { }` and
 * `compose.desktop.application { }` are configured in the same project. Nucleus ships its own
 * (forked) Compose Desktop packaging and registers the same task names, so letting both DSLs
 * drive packaging throws a cryptic `Cannot add task '…' as a task with that name already exists`.
 * The Compose plugin itself can stay applied (for Hot Reload, IDE integration, `compose.desktop
 * .currentOs`, …) — only its `application { }` packaging block must be removed.
 */
private fun checkNoComposeDesktopApplication(project: Project) {
    val desktop = composeDesktopExtension(project) ?: return
    if (!isComposeJvmApplicationInitialized(desktop)) return
    error(
        "Both `nucleus.application { }` and `compose.desktop.application { }` are configured in " +
            "project '${project.path}'. Nucleus replaces Compose Desktop's packaging and registers " +
            "the same Gradle tasks, so the two blocks conflict. Remove the " +
            "`compose.desktop.application { }` block and configure packaging via " +
            "`nucleus.application { }` instead — the Compose plugin can stay applied for Hot Reload " +
            "and IDE integration, and `mainClass` is forwarded automatically.",
    )
}

/** Returns the `compose.desktop` extension via reflection, or null when the Compose plugin is absent. */
private fun composeDesktopExtension(project: Project): ExtensionAware? {
    val compose = project.extensions.findByName("compose") as? ExtensionAware ?: return null
    return compose.extensions.findByName("desktop") as? ExtensionAware
}

/**
 * Reads Compose's internal `_isJvmApplicationInitialized` flag without initializing the lazy
 * `application` extension (unlike calling `getApplication`, which would trigger the Compose plugin
 * to register its packaging tasks). Returns false if the flag cannot be read.
 */
private fun isComposeJvmApplicationInitialized(desktop: ExtensionAware): Boolean =
    runCatching {
        desktop.javaClass
            .getMethod("get_isJvmApplicationInitialized\$compose")
            .invoke(desktop) as? Boolean
            ?: false
    }.getOrDefault(false)

/**
 * Adds `-XstartOnFirstThread` to Compose Hot Reload's `JavaExec` tasks on macOS when the
 * project depends on `nucleus.decorated-window-tao` (or the monorepo project
 * `:decorated-window-tao`). The TAO backend drives a winit/Tao native event loop that
 * must run on macOS thread 0; the standard `compose.desktop.application.run` relies on
 * AWT seizing the main thread to provide a reachable run loop, but Hot Reload's JavaExec
 * doesn't add the flag and the agent classloader can change AWT init ordering enough to
 * leave TAO's main-thread bouncing without a target — manifests as a launched process
 * with no visible window.
 *
 * The flag is harmless on JBR (the runtime Hot Reload requires), and is gated on TAO
 * presence to avoid affecting plain AWT/Skiko Compose Desktop projects.
 */
private fun injectStartOnFirstThreadForTaoHotReload(project: Project) {
    if (currentOS != OS.MacOS) return
    if (!projectUsesTaoBackend(project)) return

    project.tasks.withType(JavaExec::class.java).configureEach { task ->
        if (!task.isHotReloadJavaExec()) return@configureEach
        task.jvmArgs("-XstartOnFirstThread")
    }
}

private fun projectUsesTaoBackend(project: Project): Boolean =
    project.configurations.any { config ->
        config.dependencies.any { it.name in DECORATED_WINDOW_TAO_DEPENDENCY_NAMES }
    }

private fun JavaExec.isHotReloadJavaExec(): Boolean =
    generateSequence<Class<*>>(javaClass) { it.superclass }
        .any { it.name.startsWith("org.jetbrains.compose.reload") }

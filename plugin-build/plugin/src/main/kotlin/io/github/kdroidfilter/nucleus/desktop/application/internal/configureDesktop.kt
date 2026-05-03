/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kdroidfilter.nucleus.desktop.application.internal

import io.github.kdroidfilter.nucleus.NucleusExtension
import io.github.kdroidfilter.nucleus.desktop.tasks.AbstractUnpackDefaultApplicationResourcesTask
import io.github.kdroidfilter.nucleus.internal.utils.OS
import io.github.kdroidfilter.nucleus.internal.utils.currentOS
import io.github.kdroidfilter.nucleus.internal.utils.registerTask
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.JavaExec

private const val DECORATED_WINDOW_TAO_DEPENDENCY_NAME = "decorated-window-tao"

internal fun configureDesktop(
    project: Project,
    nucleusExtension: NucleusExtension,
) {
    if (nucleusExtension.isJvmApplicationInitialized) {
        val appInternal = nucleusExtension.application as JvmApplicationInternal
        val defaultBuildType = appInternal.data.buildTypes.default
        val appData = JvmApplicationContext(project, appInternal, defaultBuildType)
        appData.configureJvmApplication()

        if (appInternal.data.graalvm.isEnabled
                .getOrElse(false)
        ) {
            appData.configureGraalvmApplication()
        }

        propagateMainClassToComposeApplication(project, appInternal)
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

/**
 * Forwards [io.github.kdroidfilter.nucleus.desktop.application.dsl.JvmApplication.mainClass] to
 * `compose.desktop.application.mainClass` so that Compose Hot Reload tasks (e.g. `hotRun`,
 * `hotSnapshotMain`) can resolve the entry point without the user duplicating it via
 * `-PmainClass=...` or a manual `compose.desktop.application { ... }` block.
 *
 * This runs in Nucleus's `afterEvaluate`, which fires AFTER the Compose plugin's own
 * `afterEvaluate` (because Nucleus is applied last in user build scripts). By then Compose
 * has already inspected its `_isJvmApplicationInitialized` flag, so initializing the
 * `application` extension here cannot trigger duplicate task registration.
 *
 * Reflection is intentional: a hard dependency on the Compose Gradle plugin classes would
 * couple Nucleus's classpath to a specific Compose version. Reflection lets this become a
 * silent no-op on projects that don't apply the Compose plugin at all.
 */
private fun propagateMainClassToComposeApplication(
    project: Project,
    appInternal: JvmApplicationInternal,
) {
    val mainClass = appInternal.data.mainClass ?: return
    val compose = project.extensions.findByName("compose") as? ExtensionAware ?: return
    val desktop = compose.extensions.findByName("desktop") as? ExtensionAware ?: return
    runCatching {
        // `desktop.application` is lazy (org.jetbrains.compose.desktop.DesktopExtension).
        // Accessing it via reflection avoids a compile-time dependency on the Compose plugin.
        val applicationGetter = desktop.javaClass.getMethod("getApplication")
        val composeApplication = applicationGetter.invoke(desktop) ?: return
        val mainClassSetter = composeApplication.javaClass.methods
            .firstOrNull { it.name == "setMainClass" && it.parameterCount == 1 }
            ?: return
        mainClassSetter.invoke(composeApplication, mainClass)
    }
}

/**
 * Adds `-XstartOnFirstThread` to Compose Hot Reload's `JavaExec` tasks on macOS when the
 * project depends on `decorated-window-tao`. The TAO backend drives a winit/Tao native
 * event loop that must run on macOS thread 0; the standard `compose.desktop.application.run`
 * relies on AWT seizing the main thread to provide a reachable run loop, but Hot Reload's
 * JavaExec doesn't add the flag and the agent classloader can change AWT init ordering
 * enough to leave TAO's main-thread bouncing without a target — manifests as a launched
 * process with no visible window.
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
        config.dependencies.any { it.name == DECORATED_WINDOW_TAO_DEPENDENCY_NAME }
    }

private fun JavaExec.isHotReloadJavaExec(): Boolean =
    generateSequence<Class<*>>(javaClass) { it.superclass }
        .any { it.name.startsWith("org.jetbrains.compose.reload") }

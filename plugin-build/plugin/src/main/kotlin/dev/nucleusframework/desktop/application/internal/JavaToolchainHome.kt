package dev.nucleusframework.desktop.application.internal

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Points packaging / `run` at the project's JVM toolchain (`java.toolchain` or Kotlin's
 * `jvmToolchain(N)`) when one is configured, so the app runs on the JDK it was compiled for
 * rather than the Gradle daemon's (#830). An explicit `javaHome` and the `lastJdk` optimization
 * both win; without a toolchain the daemon's JDK stays the default. The launcher is resolved
 * lazily, so listing tasks never provisions a toolchain.
 */
internal fun applyJavaToolchainJdk(
    project: Project,
    app: JvmApplicationData,
) {
    if (app.hasCustomJavaHome || app.javaHomeOverride != null) return
    val toolchain = project.extensions.findByType(JavaPluginExtension::class.java)?.toolchain ?: return
    if (!toolchain.languageVersion.isPresent) return
    val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
    app.javaHomeOverride =
        toolchains
            .launcherFor(toolchain)
            .map { it.metadata.installationPath.asFile.absolutePath }
}

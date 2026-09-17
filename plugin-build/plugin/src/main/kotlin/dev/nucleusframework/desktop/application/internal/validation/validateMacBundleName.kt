/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal.validation

import dev.nucleusframework.desktop.application.dsl.AbstractDistributions
import dev.nucleusframework.desktop.application.dsl.AbstractMacOSPlatformSettings
import dev.nucleusframework.desktop.application.internal.JvmApplicationContext
import dev.nucleusframework.desktop.application.internal.macBundleNameWarnings
import dev.nucleusframework.desktop.application.internal.resolveMacBundleName
import org.gradle.api.Project

/**
 * Warns about configurations whose macOS `.app` bundle name is ambiguous.
 *
 * Reported on every host OS, not just macOS, so a Linux or Windows developer configuring a macOS
 * distribution still sees the problem.
 */
internal fun JvmApplicationContext.validateMacBundleName() {
    val dist = app.nativeDistributions
    validateMacBundleName(project, dist, dist.macOS)
}

/** Shared by the JVM and the Kotlin/Native pipelines, which resolve the same bundle name. */
internal fun validateMacBundleName(
    project: Project,
    distributions: AbstractDistributions,
    macOS: AbstractMacOSPlatformSettings,
) {
    val resolved = resolveMacBundleName(distributions, macOS, project.name)
    val warnings =
        macBundleNameWarnings(
            bundleName = macOS.bundleName,
            appName = distributions.appName,
            macPackageName = macOS.packageName,
            packageName = distributions.packageName,
            resolved = resolved,
        )
    for (warning in warnings) {
        project.logger.warn(warning)
    }
}

/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:Suppress("unused")

package dev.nucleusframework

import groovy.lang.Closure
import dev.nucleusframework.desktop.application.internal.configureDesktop
import dev.nucleusframework.experimental.internal.configureExperimentalTargetsFlagsCheck
import dev.nucleusframework.internal.KOTLIN_MPP_PLUGIN_ID
import dev.nucleusframework.internal.mppExt
import dev.nucleusframework.internal.utils.currentTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

internal val composeVersion get() = NucleusBuildConfig.composeVersion
internal val composeMaterial3Version get() = NucleusBuildConfig.composeMaterial3Version

@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class NucleusPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val nucleusExtension = project.extensions.create("nucleus", NucleusExtension::class.java, project)

        if ((project.dependencies as? ExtensionAware)?.extensions?.findByName("nucleus") == null) {
            project.dependencies.extensions.add("nucleus", Dependencies(project))
        }

        // `File.endsWith(String)` matches by path components, not by string suffix, so
        // `buildFile.endsWith(".gradle.kts")` is always false and the Groovy DSL setup used to
        // run for Kotlin DSL projects too — registering a `jetbrainsCompose` repositories
        // extension that clashes with the Compose plugin when it is applied afterwards.
        if (!project.buildFile.name.endsWith(".gradle.kts")) {
            setUpGroovyDslExtensions(project)
        }

        project.afterEvaluate {
            configureDesktop(project, nucleusExtension)
            project.plugins.withId(KOTLIN_MPP_PLUGIN_ID) {
                val mppExt = project.mppExt
                project.configureExperimentalTargetsFlagsCheck(mppExt)
            }
        }
    }

    @Suppress("DEPRECATION")
    class Dependencies(
        project: Project,
    ) {
        val desktop = DesktopDependencies

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.animation:animation:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val animation get() = composeDependency("org.jetbrains.compose.animation:animation")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.animation:animation-graphics:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val animationGraphics get() = composeDependency("org.jetbrains.compose.animation:animation-graphics")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.foundation:foundation:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val foundation get() = composeDependency("org.jetbrains.compose.foundation:foundation")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.material:material:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val material get() = composeDependency("org.jetbrains.compose.material:material")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.material3:material3:\${NucleusBuildConfig.composeMaterial3Version}\"",
                ),
        )
        val material3 get() = composeMaterial3Dependency("org.jetbrains.compose.material3:material3")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.material3:material3-adaptive-navigation-suite:" +
                        "\${NucleusBuildConfig.composeMaterial3Version}\"",
                ),
        )
        val material3AdaptiveNavigationSuite get() =
            composeMaterial3Dependency(
                "org.jetbrains.compose.material3:material3-adaptive-navigation-suite",
            )

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.runtime:runtime:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val runtime get() = composeDependency("org.jetbrains.compose.runtime:runtime")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.runtime:runtime-saveable:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val runtimeSaveable get() = composeDependency("org.jetbrains.compose.runtime:runtime-saveable")

        @Deprecated(
            "Specify dependency directly",
            replaceWith = ReplaceWith("\"org.jetbrains.compose.ui:ui:\${NucleusBuildConfig.composeVersion}\""),
        )
        val ui get() = composeDependency("org.jetbrains.compose.ui:ui")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-test:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        @ExperimentalNucleusLibrary
        val uiTest get() = composeDependency("org.jetbrains.compose.ui:ui-test")

        @Deprecated(
            "Use org.jetbrains.compose.ui:ui-tooling module instead",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-tooling:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val uiTooling get() = composeDependency("org.jetbrains.compose.ui:ui-tooling")

        @Deprecated(
            "Specify dependency directly",
            replaceWith = ReplaceWith("\"org.jetbrains.compose.ui:ui-util:\${NucleusBuildConfig.composeVersion}\""),
        )
        val uiUtil get() = composeDependency("org.jetbrains.compose.ui:ui-util")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-tooling-preview:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val preview get() = composeDependency("org.jetbrains.compose.ui:ui-tooling-preview")

        @Deprecated(
            "This artifact is pinned to version 1.7.3 and will not receive updates. " +
                "Either use this version explicitly or migrate to Material Symbols (vector resources). " +
                "See https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html",
            replaceWith = ReplaceWith("\"org.jetbrains.compose.material:material-icons-extended:1.7.3\""),
        )
        val materialIconsExtended get() = "org.jetbrains.compose.material:material-icons-extended:1.7.3"

        @Deprecated("Specify dependency directly")
        val components get() = CommonComponentsDependencies
    }

    @Deprecated("Specify dependency directly")
    object DesktopDependencies {
        @Deprecated("Specify dependency directly")
        val components = DesktopComponentsDependencies

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val common = composeDependency("org.jetbrains.compose.desktop:desktop")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-linux-x64:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val linux_x64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-linux-x64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-linux-arm64:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val linux_arm64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-linux-arm64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-windows-x64:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val windows_x64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-windows-x64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-windows-arm64:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val windows_arm64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-windows-arm64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-macos-x64:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val macos_x64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-macos-x64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val macos_arm64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-test-junit4:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val uiTestJUnit4 get() = composeDependency("org.jetbrains.compose.ui:ui-test-junit4")

        val currentOs by lazy {
            composeDependency("org.jetbrains.compose.desktop:desktop-jvm-${currentTarget.id}")
        }
    }

    @Deprecated("Specify dependency directly")
    object CommonComponentsDependencies {
        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.components:components-resources:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val resources = composeDependency("org.jetbrains.compose.components:components-resources")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-tooling-preview:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        val uiToolingPreview = composeDependency("org.jetbrains.compose.components:components-ui-tooling-preview")
    }

    object DesktopComponentsDependencies {
        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.components:components-splitpane:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        @ExperimentalNucleusLibrary
        val splitPane = composeDependency("org.jetbrains.compose.components:components-splitpane")

        @Suppress("MaxLineLength")
        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.components:components-animatedimage:\${NucleusBuildConfig.composeVersion}\"",
                ),
        )
        @ExperimentalNucleusLibrary
        val animatedImage = composeDependency("org.jetbrains.compose.components:components-animatedimage")
    }
}

fun RepositoryHandler.jetbrainsCompose(): MavenArtifactRepository =
    maven { repo -> repo.setUrl("https://packages.jetbrains.team/maven/p/cmp/dev") }

fun KotlinDependencyHandler.compose(groupWithArtifact: String) = composeDependency(groupWithArtifact)

fun DependencyHandler.compose(groupWithArtifact: String) = composeDependency(groupWithArtifact)

private fun composeDependency(groupWithArtifact: String) = "$groupWithArtifact:$composeVersion"

private fun composeMaterial3Dependency(groupWithArtifact: String) = "$groupWithArtifact:$composeMaterial3Version"

private fun setUpGroovyDslExtensions(project: Project) {
    project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        (project.extensions.getByName("kotlin") as? ExtensionAware)?.apply {
            if (extensions.findByName("nucleus") == null) {
                extensions.add("nucleus", NucleusPlugin.Dependencies(project))
            }
        }
    }
    (project.repositories as? ExtensionAware)?.extensions?.apply {
        if (findByName("jetbrainsCompose") == null) {
            add(
                "jetbrainsCompose",
                object : Closure<MavenArtifactRepository>(project.repositories) {
                    fun doCall(): MavenArtifactRepository = project.repositories.jetbrainsCompose()
                },
            )
        }
    }
}

/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

internal class JvmApplicationRuntimeFiles(
    val allRuntimeJars: FileCollection,
    val mainJar: Provider<RegularFile>,
    private val taskDependencies: Array<Any>,
) {
    operator fun component1() = allRuntimeJars

    operator fun component2() = mainJar

    fun <T : Task> configureUsageBy(
        task: T,
        fn: T.(JvmApplicationRuntimeFiles) -> Unit,
    ) {
        task.dependsOn(taskDependencies)
        task.fn(this)
    }
}

internal sealed class JvmApplicationRuntimeFilesProvider {
    abstract fun jvmApplicationRuntimeFiles(project: Project): JvmApplicationRuntimeFiles

    /**
     * Class directories produced by this app's own compilation (Kotlin + Java).
     * Fed to the GraalVM orphan / project-class detectors (#441).
     *
     * Must resolve the **application's** JVM target output — including KMP named
     * targets such as `jvm("desktop")` → `build/classes/kotlin/desktop/main` —
     * not a hardcoded `jvm/main` path.
     *
     * Empty for [Custom] / `fromFiles` setups where the plugin does not own compilation.
     */
    open fun projectClassDirs(project: Project): FileCollection = project.files()

    /**
     * Tasks that produce [projectClassDirs] (e.g. `desktopMainClasses`, `classes`).
     * [AnalyzeStaticMetadataTask] depends on these so Room/KSP outputs are on disk
     * before the orphan detector walks the project class dirs.
     */
    open fun projectClassTaskDependencies(project: Project): Array<Any> = emptyArray()

    abstract class GradleJvmApplicationRuntimeFilesProvider : JvmApplicationRuntimeFilesProvider() {
        protected abstract val jarTaskName: String
        protected abstract val runtimeFiles: FileCollection

        override fun jvmApplicationRuntimeFiles(project: Project): JvmApplicationRuntimeFiles {
            val jarTask = project.tasks.named(jarTaskName, Jar::class.java)
            val mainJar = jarTask.flatMap { it.archiveFile }
            val runtimeJarFiles =
                project.objects.fileCollection().apply {
                    from(mainJar)
                    from(runtimeFiles.filter { it.path.endsWith(".jar") })
                }
            return JvmApplicationRuntimeFiles(runtimeJarFiles, mainJar, arrayOf(jarTask))
        }
    }

    class FromGradleSourceSet(
        private val sourceSet: SourceSet,
    ) : GradleJvmApplicationRuntimeFilesProvider() {
        override val jarTaskName: String
            get() = sourceSet.jarTaskName

        override val runtimeFiles: FileCollection
            get() = sourceSet.runtimeClasspath

        override fun projectClassDirs(project: Project): FileCollection = sourceSet.output.classesDirs

        override fun projectClassTaskDependencies(project: Project): Array<Any> =
            arrayOf(sourceSet.classesTaskName)
    }

    class FromKotlinMppTarget(
        private val target: KotlinJvmTarget,
    ) : GradleJvmApplicationRuntimeFilesProvider() {
        override val jarTaskName: String
            get() = target.artifactsTaskName

        override val runtimeFiles: FileCollection
            get() = target.compilations.getByName("main").runtimeDependencyFiles

        override fun projectClassDirs(project: Project): FileCollection =
            target.compilations.getByName("main").output.classesDirs

        /**
         * `compileAllTaskName` is target-aware (`desktopMainClasses`, `jvmMainClasses`, …)
         * and already depends on compileKotlin + compileJava for that compilation.
         */
        override fun projectClassTaskDependencies(project: Project): Array<Any> =
            arrayOf(target.compilations.getByName("main").compileAllTaskName)
    }

    class Custom(
        private val runtimeJarFiles: FileCollection,
        private val mainJar: Provider<RegularFile>,
        private val taskDependencies: Array<Any>,
    ) : JvmApplicationRuntimeFilesProvider() {
        override fun jvmApplicationRuntimeFiles(project: Project): JvmApplicationRuntimeFiles =
            JvmApplicationRuntimeFiles(runtimeJarFiles, mainJar, taskDependencies)
    }
}

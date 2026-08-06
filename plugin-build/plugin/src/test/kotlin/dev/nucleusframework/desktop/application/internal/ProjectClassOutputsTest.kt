package dev.nucleusframework.desktop.application.internal

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression for KMP named JVM targets (`jvm("desktop")`) and plain Java/Kotlin JVM:
 * orphan / project-class detection (#441) must resolve compilation class dirs from the
 * application target — not hard-coded `classes/kotlin/jvm/main` paths that miss
 * `classes/kotlin/desktop/main`.
 */
class ProjectClassOutputsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun fromGradleSourceSet_exposesClassesDirsAndClassesTask() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        project.plugins.apply("java")
        val sourceSet =
            project.extensions
                .getByType(JavaPluginExtension::class.java)
                .sourceSets
                .getByName("main")

        val provider = JvmApplicationRuntimeFilesProvider.FromGradleSourceSet(sourceSet)

        assertEquals(sourceSet.output.classesDirs, provider.projectClassDirs(project))
        assertArrayEquals(arrayOf(sourceSet.classesTaskName), provider.projectClassTaskDependencies(project))
    }

    @Test
    fun customProvider_hasNoProjectClassOutputs() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        val provider =
            JvmApplicationRuntimeFilesProvider.Custom(
                runtimeJarFiles = project.files(),
                mainJar = project.objects.fileProperty(),
                taskDependencies = emptyArray(),
            )

        assertTrue(provider.projectClassDirs(project).isEmpty)
        assertArrayEquals(emptyArray<Any>(), provider.projectClassTaskDependencies(project))
    }

    @Test
    fun discoverFallback_usesJavaMainClassesWhenOnlyJavaPluginApplied() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        project.plugins.apply("java")
        val sourceSet =
            project.extensions
                .getByType(JavaPluginExtension::class.java)
                .sourceSets
                .getByName("main")

        val dirs = discoverProjectClassDirCollections(project)
        val deps = discoverProjectClassTaskDependencies(project)

        assertEquals(1, dirs.size)
        assertEquals(sourceSet.output.classesDirs, dirs.single())
        assertArrayEquals(arrayOf(sourceSet.classesTaskName), deps)
    }
}

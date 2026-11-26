package dev.nucleusframework.desktop.application.internal

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class JavaToolchainHomeTest {
    @Test
    fun `no java plugin keeps the daemon JDK`() {
        val project = ProjectBuilder.builder().build()
        val app = applicationData(project)
        applyJavaToolchainJdk(project, app)
        assertNull(app.javaHomeOverride)
        assertEquals(System.getProperty("java.home"), app.javaHomeProvider.get())
    }

    @Test
    fun `java plugin without a toolchain keeps the daemon JDK`() {
        val project = javaProject()
        val app = applicationData(project)
        applyJavaToolchainJdk(project, app)
        assertNull(app.javaHomeOverride)
    }

    @Test
    fun `configured toolchain becomes the default JDK home`() {
        val project = javaProject(toolchainVersion = currentFeatureVersion())
        val app = applicationData(project)
        applyJavaToolchainJdk(project, app)
        assertNotNull(app.javaHomeOverride)
        assertEquals(
            File(System.getProperty("java.home")).canonicalPath,
            File(app.javaHomeProvider.get()).canonicalPath,
        )
    }

    @Test
    fun `explicit javaHome wins over the toolchain`() {
        val project = javaProject(toolchainVersion = currentFeatureVersion())
        val app = applicationData(project)
        app.javaHome = "/custom/jdk"
        applyJavaToolchainJdk(project, app)
        assertNull(app.javaHomeOverride)
        assertEquals("/custom/jdk", app.javaHomeProvider.get())
    }

    @Test
    fun `lastJdk optimization wins over the toolchain`() {
        val project = javaProject(toolchainVersion = currentFeatureVersion())
        val app = applicationData(project)
        app.nucleusOptimizationSettings.lastJdk = true
        applyNucleusOptimizationJdk(project, app)
        val lastJdk = app.javaHomeOverride
        applyJavaToolchainJdk(project, app)
        assertEquals(lastJdk, app.javaHomeOverride)
    }

    private fun javaProject(toolchainVersion: Int? = null): Project =
        ProjectBuilder.builder().build().also { project ->
            project.pluginManager.apply("java")
            if (toolchainVersion != null) {
                project.extensions
                    .getByType(JavaPluginExtension::class.java)
                    .toolchain
                    .languageVersion
                    .set(JavaLanguageVersion.of(toolchainVersion))
            }
        }

    private fun currentFeatureVersion(): Int = Runtime.version().feature()

    private fun applicationData(project: Project): JvmApplicationData =
        project.objects.newInstance(JvmApplicationData::class.java)
}

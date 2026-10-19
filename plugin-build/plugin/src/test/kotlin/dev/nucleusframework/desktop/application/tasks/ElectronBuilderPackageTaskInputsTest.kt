package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The packaging task reads most of its settings from [JvmApplicationDistributions]. Each of them
 * must be a task input, or changing it in the DSL leaves the task up to date and the previous
 * installer in place: changing `linux.appImage.compressionLevel` did not rebuild the AppImage.
 */
class ElectronBuilderPackageTaskInputsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val project: Project = ProjectBuilder.builder().build()

    private fun task(configure: JvmApplicationDistributions.() -> Unit): AbstractElectronBuilderPackageTask {
        val distributions =
            project.objects.newInstance(JvmApplicationDistributions::class.java).apply {
                packageName = "InputsDemo"
                configure()
            }
        return project.tasks
            .register(
                "package${project.tasks.size}",
                AbstractElectronBuilderPackageTask::class.java,
                TargetFormat.AppImage,
            ).get()
            .apply { this.distributions = distributions }
    }

    private fun AbstractElectronBuilderPackageTask.inputProperties(): Map<String, Any?> = inputs.properties

    @Test
    fun `dsl settings are input properties of the task`() {
        val properties =
            task {
                compressionLevel = CompressionLevel.Ultra
                artifactName = "custom-\${version}.\${ext}"
                linux.appImage.compressionLevel = CompressionLevel.Store
                linux.debDepends = listOf("libgtk-3-0")
                windows.nsis.oneClick = false
                protocol("Demo", "demo")
            }.inputProperties()

        assertEquals(CompressionLevel.Ultra, properties["distributions.compressionLevel"])
        assertEquals("custom-\${version}.\${ext}", properties["distributions.artifactName"])
        assertEquals(CompressionLevel.Store, properties["distributions.linux.appImage.compressionLevel"])
        assertEquals(listOf("libgtk-3-0"), properties["distributions.linux.debDepends"])
        assertEquals(false, properties["distributions.windows.nsis.oneClick"])
        assertTrue(properties.toString(), properties.containsKey("distributions.protocols"))
    }

    @Test
    fun `changing the appimage compression changes the task inputs`() {
        val normal = task { linux.appImage.compressionLevel = CompressionLevel.Normal }.inputProperties()
        val store = task { linux.appImage.compressionLevel = CompressionLevel.Store }.inputProperties()

        assertNotEquals(normal, store)
    }

    @Test
    fun `files referenced only by the dsl are input files of the task`() {
        val license = tmp.newFile("license.txt")
        val header = tmp.newFile("header.bmp")

        val files =
            task {
                licenseFile.set(license)
                windows.nsis.installerHeader.set(header)
            }.inputs.files.files

        assertTrue(files.toString(), license in files)
        assertTrue(files.toString(), header in files)
    }
}

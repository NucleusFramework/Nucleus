package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentOS
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The packaging task reads most of its settings from [JvmApplicationDistributions]. Those that
 * reach this format's package must be task inputs, or changing them in the DSL leaves the task up
 * to date and the previous installer in place: changing `linux.appImage.compressionLevel` did not
 * rebuild the AppImage. Settings of other formats and other OSes must not be, or every DSL edit
 * rebuilds every installer.
 *
 * The task only packages for the host OS, so each test uses a format of the host.
 */
class ElectronBuilderPackageTaskInputsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val project: Project = ProjectBuilder.builder().build()

    private val hostFormat =
        when (currentOS) {
            OS.Linux -> TargetFormat.AppImage
            OS.MacOS -> TargetFormat.Dmg
            OS.Windows -> TargetFormat.Nsis
        }

    private fun task(configure: JvmApplicationDistributions.() -> Unit = {}): AbstractElectronBuilderPackageTask {
        val distributions =
            project.objects.newInstance(JvmApplicationDistributions::class.java).apply {
                packageName = "InputsDemo"
                configure()
            }
        return project.tasks
            .register(
                "package${project.tasks.size}",
                AbstractElectronBuilderPackageTask::class.java,
                hostFormat,
            ).get()
            .apply {
                packageName.set("InputsDemo")
                this.distributions = distributions
            }
    }

    private fun settings(configure: JvmApplicationDistributions.() -> Unit = {}): String =
        task(configure).packagingSettings

    /** A setting of [hostFormat] that reaches its package. */
    private fun JvmApplicationDistributions.changeHostFormat() {
        when (currentOS) {
            OS.Linux -> linux.appImage.compressionLevel = CompressionLevel.Store
            OS.MacOS -> macOS.dmg.title = "Other title"
            OS.Windows -> windows.nsis.oneClick = false
        }
    }

    /** A setting of another format of the host OS, which [hostFormat] does not read. */
    private fun JvmApplicationDistributions.changeSiblingFormat() {
        when (currentOS) {
            OS.Linux -> linux.debDepends = listOf("libgtk-3-0")
            OS.MacOS -> macOS.pkg.appStore = false
            OS.Windows -> windows.msi.oneClick = false
        }
    }

    /** A setting of another OS. */
    private fun JvmApplicationDistributions.changeOtherOs() {
        when (currentOS) {
            OS.Linux -> windows.nsis.oneClick = false
            OS.MacOS, OS.Windows -> linux.appImage.compressionLevel = CompressionLevel.Store
        }
    }

    @Test
    fun `a setting of the packaged format changes the task inputs`() {
        assertNotEquals(settings(), settings { changeHostFormat() })
    }

    @Test
    fun `common settings change the task inputs`() {
        val base = settings()
        assertNotEquals(base, settings { artifactName = "custom-\${version}.\${ext}" })
        assertNotEquals(base, settings { compressionLevel = CompressionLevel.Ultra })
        assertNotEquals(base, settings { protocol("Demo", "demo") })
    }

    @Test
    fun `settings the package does not read leave the task inputs unchanged`() {
        val base = settings()
        assertEquals(base, settings { changeSiblingFormat() })
        assertEquals(base, settings { changeOtherOs() })
        assertEquals(base, settings { targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi) })
    }

    @Test
    fun `a rotated publish token leaves the task inputs unchanged`() {
        val withToken: JvmApplicationDistributions.(String) -> Unit = { token ->
            publish.github.enabled = true
            publish.github.token = token
        }
        val first = settings { withToken("token-1") }
        assertEquals(first, settings { withToken("token-2") })
        assertFalse(first, first.contains("token-1"))
    }

    @Test
    fun `files the config points at are input files of the task`() {
        val file = tmp.newFile("asset.bin")
        val unrelated = tmp.newFile("unrelated.bin")

        val files =
            task {
                when (currentOS) {
                    OS.Linux -> linux.iconFile.set(file)
                    OS.MacOS -> macOS.dmg.background.set(file)
                    OS.Windows -> windows.nsis.installerHeader.set(file)
                }
                when (currentOS) {
                    OS.Linux -> windows.nsis.installerHeader.set(unrelated)
                    OS.MacOS, OS.Windows -> linux.flatpak.license.set(unrelated)
                }
            }.packagingFiles.files

        assertTrue(files.toString(), file in files)
        assertFalse(files.toString(), unrelated in files)
    }

    @Test
    fun `packaging inputs are registered on the task`() {
        val file = tmp.newFile("asset.bin")
        val inputs =
            task {
                when (currentOS) {
                    OS.Linux -> linux.iconFile.set(file)
                    OS.MacOS -> macOS.dmg.background.set(file)
                    OS.Windows -> windows.nsis.installerHeader.set(file)
                }
            }.inputs
        assertTrue(inputs.properties.toString(), inputs.properties.keys.any { it.startsWith("packagingSettings") })
        assertTrue(inputs.files.files.toString(), file in inputs.files.files)
    }
}

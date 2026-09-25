package dev.nucleusframework.desktop.application.internal

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MacPkgScriptsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun script(
        name: String,
        content: String = "#!/bin/sh\necho $name\n",
    ): File = tmp.newFile(name).apply { writeText(content) }

    @Test
    fun `stages an entry point per script plus the app's own copy, all executable`() {
        val build = tmp.newFolder("build")

        val staged = MacPkgScripts.stage(build, script("pre.sh"), script("post.sh"), appStore = false)

        assertEquals(build.resolve("pkg-scripts"), staged)
        assertEquals(
            setOf("preinstall", "postinstall", "nucleus-app-pre", "nucleus-app-post"),
            staged!!.list()!!.toSet(),
        )
        staged.listFiles()!!.forEach { assertTrue(it.name, it.canExecute()) }
    }

    @Test
    fun `the app's script is copied verbatim under a name electron-builder does not scan`() {
        val build = tmp.newFolder("build")

        val staged = MacPkgScripts.stage(build, script("pre.sh"), script("post.sh"), appStore = false)!!

        assertEquals("#!/bin/sh\necho pre.sh\n", staged.resolve("nucleus-app-pre").readText())
        assertEquals("#!/bin/sh\necho post.sh\n", staged.resolve("nucleus-app-post").readText())
        // electron-builder sets BundlePre/PostInstallScriptPath for any file whose name contains
        // these substrings; matching here would re-introduce the double execution.
        for (name in staged.list()!!.filter { it.startsWith("nucleus-app") }) {
            assertFalse(name, name.contains("preinstall"))
            assertFalse(name, name.contains("postinstall"))
        }
    }

    @Test
    fun `the entry point skips the per-bundle pass and delegates on the top-level one`() {
        val build = tmp.newFolder("build")
        val staged = MacPkgScripts.stage(build, script("pre.sh"), null, appStore = false)!!
        val entryPoint = staged.resolve("preinstall").readText()

        assertTrue(entryPoint, entryPoint.startsWith("#!/bin/sh"))
        assertTrue(entryPoint, entryPoint.contains("*.app|*.app/) exit 0"))
        assertTrue(entryPoint, entryPoint.contains("\"\$(dirname \"\$0\")/nucleus-app-pre\" \"\$@\""))
    }

    @Test
    fun `stages a single script`() {
        val build = tmp.newFolder("build")

        val staged = MacPkgScripts.stage(build, preInstall = null, postInstall = script("post.sh"), appStore = false)

        assertEquals(setOf("postinstall", "nucleus-app-post"), staged!!.list()!!.toSet())
    }

    @Test
    fun `wipes a stale directory when no script is configured`() {
        val build = tmp.newFolder("build")
        build.resolve("pkg-scripts").mkdirs()
        build.resolve("pkg-scripts/postinstall").writeText("#!/bin/sh\nstale\n")

        assertNull(MacPkgScripts.stage(build, preInstall = null, postInstall = null, appStore = true))
        assertFalse(build.resolve("pkg-scripts").exists())
    }

    @Test
    fun `refuses scripts for an app store pkg`() {
        val build = tmp.newFolder("build")

        val error =
            assertThrows(GradleException::class.java) {
                MacPkgScripts.stage(build, script("pre.sh"), null, appStore = true)
            }

        assertTrue(error.message, error.message!!.contains("appStore = false"))
        assertFalse(build.resolve("pkg-scripts").exists())
    }

    @Test
    fun `refuses a missing script`() {
        val build = tmp.newFolder("build")

        val error =
            assertThrows(GradleException::class.java) {
                MacPkgScripts.stage(build, null, File(build, "nope.sh"), appStore = false)
            }

        assertTrue(error.message, error.message!!.contains("postinstall script not found"))
    }

    @Test
    fun `refuses a script without a shebang`() {
        val build = tmp.newFolder("build")

        val error =
            assertThrows(GradleException::class.java) {
                MacPkgScripts.stage(build, script("pre.sh", content = "echo hi\n"), null, appStore = false)
            }

        assertTrue(error.message, error.message!!.contains("shebang"))
    }
}

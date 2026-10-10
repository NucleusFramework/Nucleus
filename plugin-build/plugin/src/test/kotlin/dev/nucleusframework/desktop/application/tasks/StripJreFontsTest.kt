package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StripJreFontsTest {
    @Test
    fun `distributions strip jre fonts by default`() {
        val distributions =
            ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)

        assertTrue(distributions.stripJreFonts)
    }

    @Test
    fun `jlink excludes jre fonts unless stripJreFonts is false`() {
        val stripped = jlinkArgs(stripJreFonts = null)
        val kept = jlinkArgs(stripJreFonts = false)

        assertTrue(stripped.contains(JRE_FONTS_EXCLUDE))
        assertFalse(kept.contains(JRE_FONTS_EXCLUDE))
        assertTrue(stripped.contains("--strip-debug"))
        assertTrue(kept.contains("--add-modules"))
    }

    private fun jlinkArgs(stripJreFonts: Boolean?): List<String> {
        val project = ProjectBuilder.builder().build()
        val task =
            project.tasks.register("createRuntimeImage", JLinkArgsProbe::class.java) {
                it.includeAllModules.set(false)
                it.modules.set(listOf("java.base", "java.desktop"))
                if (stripJreFonts != null) {
                    it.stripJreFonts.set(stripJreFonts)
                }
            }.get()
        return task.args(project.file("tmp"))
    }

    private companion object {
        const val JRE_FONTS_EXCLUDE = "--exclude-files=glob:/java.desktop/lib/fonts/**"
    }
}

/** Gradle instantiates this abstract task so the test can read [AbstractJLinkTask.makeArgs]. */
abstract class JLinkArgsProbe : AbstractJLinkTask() {
    fun args(tmpDir: File): List<String> = makeArgs(tmpDir)
}

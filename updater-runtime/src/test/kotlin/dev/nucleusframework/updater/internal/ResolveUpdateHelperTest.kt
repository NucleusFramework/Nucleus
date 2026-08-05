package dev.nucleusframework.updater.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResolveUpdateHelperTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `finds helper next to launcher`() {
        val app = tmp.newFolder("App")
        val helper = File(app, PlatformInstaller.UPDATE_HELPER_NAME).apply { writeText("x"); setExecutable(true) }
        val launcher = File(app, "App").apply { writeText("#!/bin/sh"); setExecutable(true) }
        assertEquals(helper.canonicalFile, resolveUpdateHelperFromLauncher(launcher.absolutePath)?.canonicalFile)
    }

    @Test
    fun `finds helper when process is under bin`() {
        val app = tmp.newFolder("App")
        val helper = File(app, PlatformInstaller.UPDATE_HELPER_NAME).apply { writeText("x"); setExecutable(true) }
        val bin = File(app, "bin").apply { mkdirs() }
        val launcher = File(bin, "App").apply { writeText("#!/bin/sh"); setExecutable(true) }
        assertEquals(helper.canonicalFile, resolveUpdateHelperFromLauncher(launcher.absolutePath)?.canonicalFile)
    }

    @Test
    fun `returns null when helper is missing`() {
        val app = tmp.newFolder("App")
        val launcher = File(app, "App").apply { writeText("#!/bin/sh"); setExecutable(true) }
        assertNull(resolveUpdateHelperFromLauncher(launcher.absolutePath))
    }
}

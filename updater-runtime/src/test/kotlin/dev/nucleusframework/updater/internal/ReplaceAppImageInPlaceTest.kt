package dev.nucleusframework.updater.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReplaceAppImageInPlaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `missing source returns false and leaves the destination alone`() {
        val destination = tmp.newFile("old.AppImage").apply { writeText("old") }
        val missing = File(tmp.root, "new.AppImage")
        assertFalse(PlatformInstaller.replaceAppImageInPlace(missing, destination))
        assertEquals("old", destination.readText())
    }

    @Test
    fun `rename replaces the destination and marks it executable`() {
        val destination = tmp.newFile("old.AppImage").apply { writeText("old-bytes") }
        val source = tmp.newFile("new.AppImage").apply { writeText("new-bytes") }

        assertTrue(PlatformInstaller.replaceAppImageInPlace(source, destination))
        assertEquals("new-bytes", destination.readText())
        assertTrue(destination.canExecute())
        assertFalse(source.exists())
    }

    @Test
    fun `replace works when the destination does not exist yet`() {
        val destination = File(tmp.root, "fresh.AppImage")
        val source = tmp.newFile("payload.AppImage").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(PlatformInstaller.replaceAppImageInPlace(source, destination))
        assertArrayEquals(byteArrayOf(1, 2, 3), destination.readBytes())
        assertTrue(destination.canExecute())
    }

    @Test
    fun `helper search stops at max depth`() {
        val root = tmp.newFolder("root")
        File(root, PlatformInstaller.UPDATE_HELPER_NAME).writeText("helper")
        val deep = File(root, "x/y/z").apply { mkdirs() }
        val launcher = File(deep, "App").apply { writeText("#!/bin/sh") }

        // parent=z, then y, x — three steps never reach root
        assertNull(resolveUpdateHelperFromLauncher(launcher.absolutePath, maxDepth = 3))
        assertEquals(
            File(root, PlatformInstaller.UPDATE_HELPER_NAME).canonicalFile,
            resolveUpdateHelperFromLauncher(launcher.absolutePath, maxDepth = 4)?.canonicalFile,
        )
    }

    @Test
    fun `helper search returns null when the launcher has no parent`() {
        // A path whose canonical parent is the filesystem root still has a parent; an empty
        // parent component is the case that short-circuits immediately.
        assertNull(resolveUpdateHelperFromLauncher("", helperName = "missing-helper", maxDepth = 1))
    }

    @Test
    fun `resolveUpdateHelper delegates to the launcher walk`() {
        val app = tmp.newFolder("opt-app")
        val helper = File(app, PlatformInstaller.UPDATE_HELPER_NAME).apply { writeText("x") }
        val launcher = File(app, "App").apply { writeText("#!/bin/sh") }
        assertEquals(helper.canonicalFile, PlatformInstaller.resolveUpdateHelper(launcher.absolutePath)?.canonicalFile)
    }
}

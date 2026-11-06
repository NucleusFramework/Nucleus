package dev.nucleusframework.lab.core

import java.nio.file.Files
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabPathsTest {
    @Test
    fun `a file in the way of the data dir is moved aside, not fatal`() {
        val parent = Files.createTempDirectory("lab-paths")
        try {
            val dir = parent.resolve("NucleusLab")
            dir.writeText("not a directory")

            val created = LabPaths.ensureDirectory(dir)

            assertTrue(created.isDirectory())
            val aside = parent.listDirectoryEntries("NucleusLab.not-a-dir-*").single()
            assertEquals("not a directory", aside.readText())
            // Idempotent once it is a directory.
            assertEquals(dir, LabPaths.ensureDirectory(dir))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}

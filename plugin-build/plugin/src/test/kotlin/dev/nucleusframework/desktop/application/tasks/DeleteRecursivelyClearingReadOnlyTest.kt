package dev.nucleusframework.desktop.application.tasks

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteRecursivelyClearingReadOnlyTest {
    @Test
    fun `deletes a tree holding read-only files`() {
        val root = Files.createTempDirectory("delete-read-only").toFile()
        val launcher = File(root, "My App/My App.exe").apply { parentFile.mkdirs() }
        launcher.writeText("launcher")
        File(root, "My App/app/app.jar").apply { parentFile.mkdirs() }.writeText("jar")
        assertTrue(launcher.setReadOnly())

        assertTrue(root.deleteRecursivelyClearingReadOnly())
        assertFalse(root.exists())
    }
}

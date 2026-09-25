package dev.nucleusframework.application.internal

import dev.nucleusframework.core.runtime.NucleusApp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileKitIntegrationTest {
    // FileKit is a process-wide singleton with no reset, so the uninitialized case must come
    // first and the whole sequence lives in one test.
    @Test
    fun `initializes FileKit only when the app has not`() {
        initializeFileKitIfPresent()
        assertEquals(NucleusApp.appId, FileKit.appId)

        val custom = Files.createTempDirectory("filekit-integration").toFile()
        val filesDir = File(custom, "files")
        FileKit.init(filesDir = filesDir, cacheDir = File(custom, "cache"))
        initializeFileKitIfPresent()
        assertEquals(filesDir.path, FileKit.filesDir.path)

        FileKit.init(appId = "app-chosen-id")
        initializeFileKitIfPresent()
        assertEquals("app-chosen-id", FileKit.appId)

        custom.deleteRecursively()
    }
}

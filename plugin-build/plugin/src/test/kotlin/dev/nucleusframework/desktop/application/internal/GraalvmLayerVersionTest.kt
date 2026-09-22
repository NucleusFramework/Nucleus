package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GraalvmLayerVersionTest {
    @Test
    fun `25_3 and newer support layers`() {
        assertSupports("25.3", expected = true)
        assertSupports("25.3.4.1", expected = true)
        assertSupports("26.0.1", expected = true)
    }

    @Test
    fun `older than 25_3 skips layers`() {
        assertSupports("25.2.4", expected = false)
        assertSupports("25.0.1", expected = false)
        assertSupports("24.1.2", expected = false)
    }

    @Test
    fun `a toolchain without GRAALVM_VERSION skips layers`() {
        withRelease("JAVA_VERSION=\"25.0.4\"\n") { home ->
            assertNull(graalvmReleaseVersion(home))
            assertFalse(supportsLayeredImages(home))
        }
    }

    private fun assertSupports(
        version: String,
        expected: Boolean,
    ) {
        withRelease("GRAALVM_VERSION=\"$version\"\n") { home ->
            if (expected) {
                assertTrue(supportsLayeredImages(home))
            } else {
                assertFalse(supportsLayeredImages(home))
            }
        }
    }

    private fun withRelease(
        release: String,
        check: (File) -> Unit,
    ) {
        val home = Files.createTempDirectory("graal-release").toFile()
        try {
            home.resolve("release").writeText(release)
            check(home)
        } finally {
            home.deleteRecursively()
        }
    }
}

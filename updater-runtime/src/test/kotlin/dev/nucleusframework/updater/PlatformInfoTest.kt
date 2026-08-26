package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.internal.Arch
import dev.nucleusframework.updater.internal.PlatformInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformInfoTest {
    @Test
    fun `current platform matches the JVM os name`() {
        assertEquals(Platform.Current, PlatformInfo.currentPlatform())
    }

    @Test
    fun `current arch maps aarch64 and arm64 to ARM64`() {
        val osArch = System.getProperty("os.arch").lowercase()
        val expected = if (osArch.contains("aarch64") || osArch.contains("arm64")) Arch.ARM64 else Arch.X64
        assertEquals(expected, PlatformInfo.currentArch())
    }

    @Test
    fun `yml suffix and file name follow the current platform`() {
        val suffix = PlatformInfo.ymlSuffix()
        val expectedSuffix =
            when (Platform.Current) {
                Platform.Windows, Platform.Unknown -> ""
                Platform.MacOS -> "mac"
                Platform.Linux -> "linux"
            }
        assertEquals(expectedSuffix, suffix)
        val fileName = PlatformInfo.ymlFileName("latest")
        if (suffix.isEmpty()) {
            assertEquals("latest.yml", fileName)
        } else {
            assertEquals("latest-$suffix.yml", fileName)
        }
        assertTrue(PlatformInfo.ymlFileName("beta").endsWith(".yml"))
    }
}

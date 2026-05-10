package dev.nucleusframework.nucleus.updater

import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.updater.internal.Arch
import dev.nucleusframework.nucleus.updater.internal.FileSelector
import dev.nucleusframework.nucleus.updater.internal.YamlFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FileSelectorTest {
    private val linuxFiles =
        listOf(
            YamlFileEntry("App-1.0.0-linux-amd64.deb", "hash1", 100L, null),
            YamlFileEntry("App-1.0.0-linux-arm64.deb", "hash2", 200L, null),
            YamlFileEntry("App-1.0.0-linux-amd64.rpm", "hash3", 300L, null),
            YamlFileEntry("App-1.0.0-linux-arm64.rpm", "hash4", 400L, null),
        )

    private val macFiles =
        listOf(
            YamlFileEntry("App-1.0.0-mac-x64.dmg", "hash1", 100L, null),
            YamlFileEntry("App-1.0.0-mac-arm64.dmg", "hash2", 200L, null),
        )

    // Realistic Windows yml with suffixed exe filenames (as produced by the plugin)
    private val windowsFiles =
        listOf(
            YamlFileEntry("App-1.0.0-win-x64-nsis.exe", "hash1", 100L, null),
            YamlFileEntry("App-1.0.0-win-arm64-nsis.exe", "hash2", 200L, null),
            YamlFileEntry("App-1.0.0-win-x64-portable.exe", "hash3", 300L, null),
            YamlFileEntry("App-1.0.0-win-x64.msi", "hash4", 400L, null),
        )

    @Test
    fun `select deb for linux x64`() {
        val result = FileSelector.select(linuxFiles, Platform.Linux, Arch.X64, "deb")
        assertNotNull(result)
        assertEquals("App-1.0.0-linux-amd64.deb", result!!.url)
    }

    @Test
    fun `select deb for linux arm64`() {
        val result = FileSelector.select(linuxFiles, Platform.Linux, Arch.ARM64, "deb")
        assertNotNull(result)
        assertEquals("App-1.0.0-linux-arm64.deb", result!!.url)
    }

    @Test
    fun `select rpm for linux x64`() {
        val result = FileSelector.select(linuxFiles, Platform.Linux, Arch.X64, "rpm")
        assertNotNull(result)
        assertEquals("App-1.0.0-linux-amd64.rpm", result!!.url)
    }

    @Test
    fun `select dmg for mac arm64`() {
        val result = FileSelector.select(macFiles, Platform.MacOS, Arch.ARM64, "dmg")
        assertNotNull(result)
        assertEquals("App-1.0.0-mac-arm64.dmg", result!!.url)
    }

    @Test
    fun `select msi for windows x64`() {
        val result = FileSelector.select(windowsFiles, Platform.Windows, Arch.X64, "msi")
        assertNotNull(result)
        assertEquals("App-1.0.0-win-x64.msi", result!!.url)
    }

    @Test
    fun `auto-detect platform when format is null`() {
        val result = FileSelector.select(macFiles, Platform.MacOS, Arch.ARM64, null)
        assertNotNull(result)
        assertEquals("App-1.0.0-mac-arm64.dmg", result!!.url)
    }

    @Test
    fun `fallback to first match when no arch in filename`() {
        val files =
            listOf(
                YamlFileEntry("App-1.0.0.dmg", "hash1", 100L, null),
            )
        val result = FileSelector.select(files, Platform.MacOS, Arch.ARM64, "dmg")
        assertNotNull(result)
        assertEquals("App-1.0.0.dmg", result!!.url)
    }

    @Test
    fun `return null for empty file list`() {
        val result = FileSelector.select(emptyList(), Platform.Linux, Arch.X64, "deb")
        assertNull(result)
    }

    @Test
    fun `return null when no matching format`() {
        val files =
            listOf(
                YamlFileEntry("App-1.0.0.deb", "hash1", 100L, null),
            )
        val result = FileSelector.select(files, Platform.Linux, Arch.X64, "rpm")
        assertNull(result)
    }

    // --- Suffix-based disambiguation tests ---

    @Test
    fun `nsis selects nsis exe not portable exe`() {
        val result = FileSelector.select(windowsFiles, Platform.Windows, Arch.X64, "nsis")
        assertNotNull(result)
        assertEquals("App-1.0.0-win-x64-nsis.exe", result!!.url)
    }

    @Test
    fun `exe selects nsis exe`() {
        val result = FileSelector.select(windowsFiles, Platform.Windows, Arch.X64, "exe")
        assertNotNull(result)
        assertEquals("App-1.0.0-win-x64-nsis.exe", result!!.url)
    }

    @Test
    fun `nsis-web selects nsis exe (same installer)`() {
        val result = FileSelector.select(windowsFiles, Platform.Windows, Arch.X64, "nsis-web")
        assertNotNull(result)
        assertEquals("App-1.0.0-win-x64-nsis.exe", result!!.url)
    }

    @Test
    fun `portable selects portable exe not nsis exe`() {
        val result = FileSelector.select(windowsFiles, Platform.Windows, Arch.X64, "portable")
        assertNotNull(result)
        assertEquals("App-1.0.0-win-x64-portable.exe", result!!.url)
    }

    @Test
    fun `nsis selects correct arch`() {
        val result = FileSelector.select(windowsFiles, Platform.Windows, Arch.ARM64, "nsis")
        assertNotNull(result)
        assertEquals("App-1.0.0-win-arm64-nsis.exe", result!!.url)
    }

    @Test
    fun `nsis-web does not match nsis-web suffixed files`() {
        val filesWithWeb =
            listOf(
                YamlFileEntry("App-1.0.0-win-x64-nsis.exe", "hash1", 100L, null),
                YamlFileEntry("App-1.0.0-win-x64-nsis-web.exe", "hash2", 200L, null),
            )
        // nsis-web should pick the full nsis installer, not the web stub
        val result = FileSelector.select(filesWithWeb, Platform.Windows, Arch.X64, "nsis-web")
        assertNotNull(result)
        assertEquals("App-1.0.0-win-x64-nsis.exe", result!!.url)
    }
}

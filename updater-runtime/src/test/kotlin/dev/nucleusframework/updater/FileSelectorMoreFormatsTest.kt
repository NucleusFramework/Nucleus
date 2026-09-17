package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.internal.Arch
import dev.nucleusframework.updater.internal.FileSelector
import dev.nucleusframework.updater.internal.YamlFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FileSelectorMoreFormatsTest {
    private fun entry(url: String) = YamlFileEntry(url, "hash", 1L, null)

    @Test
    fun `extension formats that have no suffix mapping`() {
        val files =
            listOf(
                entry("App-1.0.0-linux-amd64.snap"),
                entry("App-1.0.0-linux-amd64.flatpak"),
                entry("App-1.0.0-linux-x86_64.AppImage"),
                entry("App-1.0.0-mac-x64.zip"),
                entry("App-1.0.0-linux-amd64.tar.gz"),
                entry("App-1.0.0-win-x64.appx"),
                entry("App-1.0.0-win-x64.7z"),
            )
        assertEquals("App-1.0.0-linux-amd64.snap", FileSelector.select(files, Platform.Linux, Arch.X64, "snap")!!.url)
        assertEquals(
            "App-1.0.0-linux-amd64.flatpak",
            FileSelector.select(files, Platform.Linux, Arch.X64, "flatpak")!!.url,
        )
        assertEquals(
            "App-1.0.0-linux-x86_64.AppImage",
            FileSelector.select(files, Platform.Linux, Arch.X64, "appimage")!!.url,
        )
        assertEquals("App-1.0.0-mac-x64.zip", FileSelector.select(files, Platform.MacOS, Arch.X64, "zip")!!.url)
        assertEquals(
            "App-1.0.0-linux-amd64.tar.gz",
            FileSelector.select(files, Platform.Linux, Arch.X64, "tar.gz")!!.url,
        )
        assertEquals("App-1.0.0-linux-amd64.tar.gz", FileSelector.select(files, Platform.Linux, Arch.X64, "tar")!!.url)
        assertEquals("App-1.0.0-win-x64.appx", FileSelector.select(files, Platform.Windows, Arch.X64, "appx")!!.url)
        assertEquals("App-1.0.0-win-x64.7z", FileSelector.select(files, Platform.Windows, Arch.X64, "7z")!!.url)
    }

    @Test
    fun `format is case-insensitive`() {
        val files = listOf(entry("App-1.0.0-linux-amd64.DEB"))
        val result = FileSelector.select(files, Platform.Linux, Arch.X64, "DEB")
        assertNotNull(result)
        assertEquals("App-1.0.0-linux-amd64.DEB", result!!.url)
    }

    @Test
    fun `unknown format returns null`() {
        val files = listOf(entry("App-1.0.0.deb"))
        assertNull(FileSelector.select(files, Platform.Linux, Arch.X64, "pacman"))
    }

    @Test
    fun `auto-detect prefers platform extensions in documented order`() {
        val windows =
            listOf(
                entry("App-1.0.0-win-x64.msi"),
                entry("App-1.0.0-win-x64.exe"),
            )
        assertEquals(
            "App-1.0.0-win-x64.exe",
            FileSelector.select(windows, Platform.Windows, Arch.X64, null)!!.url,
        )

        val mac =
            listOf(
                entry("App-1.0.0-mac-arm64.dmg"),
                entry("App-1.0.0-mac-arm64.zip"),
            )
        assertEquals(
            "App-1.0.0-mac-arm64.zip",
            FileSelector.select(mac, Platform.MacOS, Arch.ARM64, null)!!.url,
        )

        val linux =
            listOf(
                entry("App-1.0.0-linux-amd64.rpm"),
                entry("App-1.0.0-linux-amd64.deb"),
            )
        assertEquals(
            "App-1.0.0-linux-amd64.deb",
            FileSelector.select(linux, Platform.Linux, Arch.X64, null)!!.url,
        )
    }

    @Test
    fun `unknown platform with no format matches nothing`() {
        val files = listOf(entry("App-1.0.0.deb"), entry("App-1.0.0.exe"))
        assertNull(FileSelector.select(files, Platform.Unknown, Arch.X64, null))
    }

    @Test
    fun `aarch64 is treated as arm64`() {
        val files = listOf(entry("App-1.0.0-linux-aarch64.deb"), entry("App-1.0.0-linux-amd64.deb"))
        assertEquals(
            "App-1.0.0-linux-aarch64.deb",
            FileSelector.select(files, Platform.Linux, Arch.ARM64, "deb")!!.url,
        )
    }

    @Test
    fun `x86_64 is treated as x64`() {
        val files = listOf(entry("App-1.0.0-linux-x86_64.rpm"), entry("App-1.0.0-linux-arm64.rpm"))
        assertEquals(
            "App-1.0.0-linux-x86_64.rpm",
            FileSelector.select(files, Platform.Linux, Arch.X64, "rpm")!!.url,
        )
    }

    @Test
    fun `nsis portable and nsis-web pick the matching suffix`() {
        val files =
            listOf(
                entry("App-1.0.0-win-x64-nsis.exe"),
                entry("App-1.0.0-win-x64-portable.exe"),
                entry("App-1.0.0-win-x64.msi"),
            )
        assertEquals(
            "App-1.0.0-win-x64-nsis.exe",
            FileSelector.select(files, Platform.Windows, Arch.X64, "nsis")!!.url,
        )
        assertEquals(
            "App-1.0.0-win-x64-nsis.exe",
            FileSelector.select(files, Platform.Windows, Arch.X64, "nsis-web")!!.url,
        )
        assertEquals(
            "App-1.0.0-win-x64-portable.exe",
            FileSelector.select(files, Platform.Windows, Arch.X64, "portable")!!.url,
        )
    }

    @Test
    fun `suffix format with no match falls back to extension`() {
        val files = listOf(entry("legacy-setup.exe"))
        val result = FileSelector.select(files, Platform.Windows, Arch.X64, "exe")
        assertNotNull(result)
        assertEquals("legacy-setup.exe", result!!.url)
    }
}

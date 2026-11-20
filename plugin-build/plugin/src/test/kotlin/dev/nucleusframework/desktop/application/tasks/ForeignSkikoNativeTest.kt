package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignSkikoNativeTest {
    @Test
    fun `the current platform's Skiko native and its checksum are kept`() {
        assertFalse(isForeignSkikoNative("libskiko-macos-arm64.dylib", OS.MacOS, Arch.Arm64))
        assertFalse(isForeignSkikoNative("libskiko-macos-arm64.dylib.sha256", OS.MacOS, Arch.Arm64))
        assertFalse(isForeignSkikoNative("skiko-windows-x64.dll", OS.Windows, Arch.X64))
        assertFalse(isForeignSkikoNative("libskiko-linux-arm64.so", OS.Linux, Arch.Arm64))
    }

    @Test
    fun `other platforms and architectures are dropped`() {
        listOf(
            "libskiko-macos-x64.dylib",
            "libskiko-macos-x64.dylib.sha256",
            "libskiko-linux-x64.so",
            "libskiko-linux-arm64.so.sha256",
            "skiko-windows-x64.dll",
            "skiko-windows-arm64.dll",
        ).forEach { assertTrue(it, isForeignSkikoNative(it, OS.MacOS, Arch.Arm64)) }
    }

    @Test
    fun `ICU data is kept on Windows only`() {
        assertFalse(isForeignSkikoNative("icudtl.dat", OS.Windows, Arch.X64))
        assertTrue(isForeignSkikoNative("icudtl.dat", OS.MacOS, Arch.Arm64))
        assertTrue(isForeignSkikoNative("icudtl.dat", OS.Linux, Arch.X64))
    }

    @Test
    fun `anything that is not a Skiko native is kept`() {
        assertFalse(isForeignSkikoNative("libnucleus_tao.dylib", OS.Linux, Arch.X64))
        assertFalse(isForeignSkikoNative("SkikoKt.class", OS.Linux, Arch.X64))
        assertFalse(isForeignSkikoNative("skiko.properties", OS.MacOS, Arch.Arm64))
    }
}

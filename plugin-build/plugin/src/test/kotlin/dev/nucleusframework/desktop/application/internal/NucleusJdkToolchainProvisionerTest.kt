package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NucleusJdkToolchainProvisionerTest {
    @Test
    fun `download URL is the pinned OpenJDK 27 GA`() {
        val url = NucleusJdkToolchainProvisioner.downloadUrl(OS.Windows, Arch.X64)
        assertEquals(
            "https://download.java.net/java/GA/jdk27/" +
                "$OPENJDK_27_HASH/$OPENJDK_27_BUILD/GPL/" +
                "openjdk-27_windows-x64_bin.zip",
            url,
        )
        assertTrue(url.contains("openjdk-27_"))
    }

    @Test
    fun `linux aarch64 uses the aarch64 token and tar gz`() {
        val url = NucleusJdkToolchainProvisioner.downloadUrl(OS.Linux, Arch.Arm64)
        assertTrue(url.endsWith("openjdk-27_linux-aarch64_bin.tar.gz"))
    }

    @Test
    fun `macos aarch64 is published`() {
        val url = NucleusJdkToolchainProvisioner.downloadUrl(OS.MacOS, Arch.Arm64)
        assertTrue(url.endsWith("openjdk-27_macos-aarch64_bin.tar.gz"))
    }

    @Test
    fun `install id is the GA pin so RC caches re-provision`() {
        val id =
            NucleusJdkToolchainProvisioner.installationId(
                NucleusJdkToolchainRequest(
                    os = OS.Windows,
                    arch = Arch.X64,
                    installBaseDir = java.io.File("."),
                ),
            )
        assertEquals("openjdk-27-windows-x64", id)
    }

    @Test
    fun `macos x64 falls back to Liberica`() {
        val url = NucleusJdkToolchainProvisioner.downloadUrl(OS.MacOS, Arch.X64)
        assertEquals(LIBERICA_27_MACOS_X64_URL, url)
        assertTrue(NucleusJdkToolchainProvisioner.usesLibericaFallback(OS.MacOS, Arch.X64))
    }

    @Test
    fun `macos x64 install id is Liberica so Oracle caches are not reused`() {
        val id =
            NucleusJdkToolchainProvisioner.installationId(
                NucleusJdkToolchainRequest(
                    os = OS.MacOS,
                    arch = Arch.X64,
                    installBaseDir = java.io.File("."),
                ),
            )
        assertEquals("liberica-jdk-27-macos-x64", id)
    }

    @Test
    fun `windows aarch64 falls back to Liberica`() {
        val url = NucleusJdkToolchainProvisioner.downloadUrl(OS.Windows, Arch.Arm64)
        assertEquals(LIBERICA_27_WINDOWS_AARCH64_URL, url)
        assertTrue(NucleusJdkToolchainProvisioner.usesLibericaFallback(OS.Windows, Arch.Arm64))
    }

    @Test
    fun `windows aarch64 install id is Liberica so Oracle caches are not reused`() {
        val id =
            NucleusJdkToolchainProvisioner.installationId(
                NucleusJdkToolchainRequest(
                    os = OS.Windows,
                    arch = Arch.Arm64,
                    installBaseDir = java.io.File("."),
                ),
            )
        assertEquals("liberica-jdk-27-windows-aarch64", id)
    }
}

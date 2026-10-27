package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NodeToolchainProvisionerTest {
    private val index =
        """
        [
          {"version": "v24.2.0", "lts": false},
          {"version": "v22.11.0", "lts": "Jod"},
          {"version": "v22.9.0", "lts": false},
          {"version": "v20.18.1", "lts": "Iron"}
        ]
        """.trimIndent()

    @Test
    fun `a pinned version resolves without touching the network`() {
        val resolved =
            NodeToolchainProvisioner.resolveVersion("22.11.0") { error("index.json must not be fetched") }
        assertEquals("v22.11.0", resolved)
    }

    @Test
    fun `a major line resolves to its newest release`() {
        assertEquals("v22.11.0", NodeToolchainProvisioner.resolveVersion("22") { index })
    }

    @Test
    fun `lts resolves to the newest release carrying an LTS codename`() {
        assertEquals("v22.11.0", NodeToolchainProvisioner.resolveVersion("lts") { index })
    }

    @Test
    fun `an unreleased line fails with an actionable message`() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                NodeToolchainProvisioner.resolveVersion("19") { index }
            }
        assertTrue(failure.message!!.contains("nodejs { version }"))
    }

    @Test
    fun `windows downloads a zip and every other platform a tarball`() {
        assertEquals(
            "node-v22.11.0-win-x64.zip",
            NodeToolchainProvisioner.archiveName("v22.11.0", OS.Windows, Arch.X64),
        )
        assertEquals(
            "node-v22.11.0-linux-arm64.tar.gz",
            NodeToolchainProvisioner.archiveName("v22.11.0", OS.Linux, Arch.Arm64),
        )
        assertEquals(
            "node-v22.11.0-darwin-arm64.tar.gz",
            NodeToolchainProvisioner.archiveName("v22.11.0", OS.MacOS, Arch.Arm64),
        )
    }

    @Test
    fun `the install id keeps the requested version so a floating line stays sticky`() {
        val id =
            NodeToolchainProvisioner.installationId(
                NodeToolchainRequest(version = "22", os = OS.MacOS, arch = Arch.Arm64, installBaseDir = File(".")),
            )
        assertEquals("node-22-darwin-arm64", id)
    }

    @Test
    fun `an installation is recognised by its node executable, on both layouts`() {
        val root = Files.createTempDirectory("node-toolchain").toFile()
        try {
            val windows = File(root, "win").apply { mkdirs() }
            File(windows, "node.exe").writeText("")
            assertEquals(File(windows, "npm.cmd"), NodeToolchainProvisioner.installationAt(windows)!!.npm)

            val unix = File(root, "unix/bin").apply { mkdirs() }.parentFile
            File(unix, "bin/node").writeText("")
            assertEquals(File(unix, "bin/npm"), NodeToolchainProvisioner.installationAt(unix)!!.npm)

            assertNull(NodeToolchainProvisioner.installationAt(File(root, "empty")))
        } finally {
            root.deleteRecursively()
        }
    }
}

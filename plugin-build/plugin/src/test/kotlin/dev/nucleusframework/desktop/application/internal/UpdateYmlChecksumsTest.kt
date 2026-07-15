package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateYmlChecksumsTest {
    private val macYml =
        """
        version: 1.0.0
        files:
          - url: app-1.0.0-mac-arm64.dmg
            sha512: OLDHASH==
            size: 75030177
            blockMapSize: 79282
        path: app-1.0.0-mac-arm64.dmg
        sha512: OLDHASH==
        releaseDate: '2026-07-15T00:00:00.000Z'
        """.trimIndent()

    @Test
    fun `updates per-file and top-level sha512, size and blockMapSize`() {
        val updated =
            UpdateYmlChecksums.updateYamlEntry(
                yaml = macYml,
                fileName = "app-1.0.0-mac-arm64.dmg",
                newHash = "NEWHASH==",
                newSize = 58607594,
                newBlockMapSize = 61868,
            )

        assertFalse("old hash must be gone", updated.contains("OLDHASH=="))
        assertTrue(updated.contains("sha512: NEWHASH=="))
        assertTrue(updated.contains("size: 58607594"))
        assertTrue(updated.contains("blockMapSize: 61868"))
        // Top-level sha512 (paired with path:) is updated too.
        assertTrue(updated.lines().any { it == "sha512: NEWHASH==" })
    }

    @Test
    fun `null blockMapSize drops the blockMapSize line`() {
        val updated =
            UpdateYmlChecksums.updateYamlEntry(
                yaml = macYml,
                fileName = "app-1.0.0-mac-arm64.dmg",
                newHash = "NEWHASH==",
                newSize = 58607594,
                newBlockMapSize = null,
            )

        assertFalse(updated.contains("blockMapSize"))
        assertTrue(updated.contains("size: 58607594"))
        assertTrue(updated.contains("sha512: NEWHASH=="))
    }

    @Test
    fun `unrelated file names are left untouched`() {
        val updated =
            UpdateYmlChecksums.updateYamlEntry(
                yaml = macYml,
                fileName = "other.dmg",
                newHash = "NEWHASH==",
                newSize = 1,
                newBlockMapSize = 2,
            )

        assertTrue(updated.contains("sha512: OLDHASH=="))
        assertTrue(updated.contains("size: 75030177"))
    }
}

package dev.nucleusframework.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Developer ID PKG installs an ordinary `.app`, so it self-updates from the release's ZIP/DMG
 * like any direct-distribution build. Only the sandboxed Mac App Store build must stay excluded.
 */
class PkgUpdateSupportTest {
    private fun updater(type: String): NucleusUpdater =
        NucleusUpdater {
            currentVersion = "1.0.0"
            provider = FakeUpdateProvider()
            executableType = type
        }

    @Test
    fun `a pkg outside the app sandbox can update itself`() {
        assertTrue(updater("pkg").isUpdateSupported())
    }

    @Test
    fun `store containers stay excluded`() {
        assertFalse(updater("appx").isUpdateSupported())
        assertFalse(updater("flatpak").isUpdateSupported())
    }

    @Test
    fun `direct distribution formats keep updating`() {
        assertTrue(updater("dmg").isUpdateSupported())
        assertTrue(updater("zip").isUpdateSupported())
        assertTrue(updater("nsis").isUpdateSupported())
    }
}

package dev.nucleusframework.media.control

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.media.control.linux.NativeLinuxBridge
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxMediaControlNativeTest {
    @Test
    fun `metadata refreshes preserve the mpris track id`() {
        if (Platform.Current != Platform.Linux || System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return
        assertTrue(NativeLinuxBridge.isLoaded)
        NativeLinuxBridge.nativeConfigure(BUS_NAME, "Nucleus metadata test")
        try {
            assertTrue(NativeLinuxBridge.nativeStartListening())
            val song = MediaMetadata(title = "Song", artist = "Artist", album = "Album", duration = 180_000L)
            val first = publish(song)
            assertEquals(trackId(first), trackId(publish(song)))

            val updated = publish(song.copy(duration = 180_001L))
            assertEquals(trackId(first), trackId(updated))
            assertTrue(updated.contains("180001000"), updated)
            val artwork = publish(song.copy(coverUrl = "file:///tmp/cover.jpg"))
            assertEquals(trackId(first), trackId(artwork))
            assertTrue(artwork.contains("file:///tmp/cover.jpg"), artwork)
            assertEquals(trackId(first), trackId(publish(song.copy(duration = null))))

            for (next in listOf(song.copy(title = "Next"), song.copy(artist = "Other"), song.copy(album = "Other"))) {
                val before = trackId(publish(song))
                assertNotEquals(before, trackId(publish(next)))
            }
            val previous = publish(song)
            val cleared = publish(MediaMetadata())
            assertNotEquals(trackId(previous), trackId(cleared))
            assertEquals(trackId(cleared), trackId(publish(MediaMetadata(title = "", artist = "", album = ""))))
            assertNotEquals(trackId(cleared), trackId(publish(song)))
        } finally {
            NativeLinuxBridge.nativeStopListening()
        }
    }

    private fun publish(metadata: MediaMetadata): String {
        MediaControlService.setMetadata(metadata)
        val process =
            ProcessBuilder(
                "gdbus",
                "call",
                "--session",
                "--timeout",
                "3",
                "--dest",
                BUS_NAME,
                "--object-path",
                "/org/mpris/MediaPlayer2",
                "--method",
                "org.freedesktop.DBus.Properties.Get",
                "org.mpris.MediaPlayer2.Player",
                "Metadata",
            ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Metadata read timed out")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            process.destroyForcibly()
        }
    }

    private fun trackId(metadata: String): String =
        assertNotNull(Regex("'mpris:trackid': <objectpath '([^']+)'>").find(metadata), metadata).groupValues[1]

    private companion object {
        const val BUS_NAME = "org.mpris.MediaPlayer2.nucleus_metadata_test"
    }
}

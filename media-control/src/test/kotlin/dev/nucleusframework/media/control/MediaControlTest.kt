package dev.nucleusframework.media.control

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.media.control.linux.NativeLinuxBridge
import dev.nucleusframework.media.control.macos.NativeMacOsBridge
import dev.nucleusframework.media.control.windows.NativeWindowsBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaControlTest {
    @Test
    fun `metadata and playback state models keep optional fields`() {
        val empty = MediaMetadata()
        assertNull(empty.title)
        assertNull(empty.duration)
        val metadata =
            MediaMetadata(
                title = "Song",
                artist = "Artist",
                album = "Album",
                coverUrl = "file:///tmp/cover.jpg",
                duration = 180_000L,
            )
        assertEquals("Song", metadata.title)
        assertEquals(180_000L, metadata.duration)

        val playing = MediaPlaybackState(MediaPlaybackStatus.PLAYING, 12_000L)
        assertEquals(MediaPlaybackStatus.PLAYING, playing.status)
        assertEquals(12_000L, playing.positionMs)
        val stopped = MediaPlaybackState(MediaPlaybackStatus.STOPPED)
        assertNull(stopped.positionMs)
        assertEquals(3, MediaPlaybackStatus.entries.size)
    }

    @Test
    fun `control events expose seek volume and uri payloads`() {
        assertEquals(-1500L, MediaControlEvent.SeekBy(-1500L).offsetMs)
        assertEquals(2500L, MediaControlEvent.SetPosition(2500L).positionMs)
        assertEquals(0.4, MediaControlEvent.SetVolume(0.4).volume)
        assertEquals("https://example.com/track", MediaControlEvent.OpenUri("https://example.com/track").uri)
        assertIs<MediaControlEvent.Play>(MediaControlEvent.Play)
        assertIs<MediaControlEvent.Pause>(MediaControlEvent.Pause)
        assertIs<MediaControlEvent.Toggle>(MediaControlEvent.Toggle)
        assertIs<MediaControlEvent.Next>(MediaControlEvent.Next)
        assertIs<MediaControlEvent.Previous>(MediaControlEvent.Previous)
        assertIs<MediaControlEvent.Stop>(MediaControlEvent.Stop)
        assertIs<MediaControlEvent.Raise>(MediaControlEvent.Raise)
        assertIs<MediaControlEvent.Quit>(MediaControlEvent.Quit)
    }

    @Test
    fun `service configure metadata playback and volume are safe to call`() {
        MediaControlService.configure(dbusName = "org.mpris.MediaPlayer2.test", displayName = "Test Player")
        MediaControlService.setMetadata(MediaMetadata(title = "Song", artist = "A", album = "B", duration = 1000L))
        MediaControlService.setMetadata(MediaMetadata())
        MediaControlService.setPlaybackState(MediaPlaybackState(MediaPlaybackStatus.PLAYING, 10L))
        MediaControlService.setPlaybackState(MediaPlaybackState(MediaPlaybackStatus.PAUSED))
        MediaControlService.setPlaybackState(MediaPlaybackState(MediaPlaybackStatus.STOPPED))
        MediaControlService.setVolume(0.5)
        MediaControlService.setVolume(1.5)
        MediaControlService.setVolume(-2.0)
        MediaControlService.attach { }
        MediaControlService.detach()
    }

    @Test
    fun `mpris suffix sanitization produces legal dbus names`() {
        assertEquals("Music_Radio", MediaControlService.sanitizeMprisSuffix("Music Radio"))
        assertEquals("dev.kdroid.musicradio", MediaControlService.sanitizeMprisSuffix("dev.kdroid.musicradio"))
        assertEquals("_1password", MediaControlService.sanitizeMprisSuffix("1password"))
        assertEquals("my-app", MediaControlService.sanitizeMprisSuffix("my-app"))
        assertEquals("caf__app", MediaControlService.sanitizeMprisSuffix("café app"))
        assertEquals("a.b", MediaControlService.sanitizeMprisSuffix(".a..b."))
        assertEquals("NucleusApp", MediaControlService.sanitizeMprisSuffix(""))
        assertEquals("NucleusApp", MediaControlService.sanitizeMprisSuffix("..."))
        assertEquals(200, MediaControlService.sanitizeMprisSuffix("a".repeat(300)).length)
    }

    @Test
    fun `attach with an invalid dbus name fails open without blocking`() {
        if (Platform.Current != Platform.Linux || !MediaControlService.isAvailable()) return

        val done = CountDownLatch(1)
        Thread {
            MediaControlService.configure(
                dbusName = "org.mpris.MediaPlayer2.Music Radio",
                displayName = "Test Player",
            )
            MediaControlService.attach { }
            done.countDown()
        }.apply {
            isDaemon = true
            start()
        }
        try {
            assertTrue(done.await(15, TimeUnit.SECONDS), "attach blocked forever on an invalid D-Bus name")
        } finally {
            MediaControlService.detach()
            MediaControlService.configure(dbusName = "org.mpris.MediaPlayer2.test", displayName = "Test Player")
        }
    }

    @Test
    fun `native event json is parsed into typed events when a backend is attached`() {
        if (!MediaControlService.isAvailable()) {
            MediaControlService.attach { }
            MediaControlService.detach()
            return
        }

        val received = AtomicReference<MediaControlEvent?>(null)
        MediaControlService.attach { }
        try {
            fun expect(
                json: String,
                expected: MediaControlEvent,
            ) {
                received.set(null)
                val next = CountDownLatch(1)
                MediaControlService.attach { event ->
                    received.set(event)
                    next.countDown()
                }
                fireNativeEvent(json)
                assertTrue(next.await(3, TimeUnit.SECONDS), "timed out for $json")
                assertEquals(expected, received.get())
            }

            expect("""{"type":"play"}""", MediaControlEvent.Play)
            expect("""{"type":"pause"}""", MediaControlEvent.Pause)
            expect("""{"type":"toggle"}""", MediaControlEvent.Toggle)
            expect("""{"type":"next"}""", MediaControlEvent.Next)
            expect("""{"type":"previous"}""", MediaControlEvent.Previous)
            expect("""{"type":"stop"}""", MediaControlEvent.Stop)
            expect("""{"type":"seek","offsetUs":5000000}""", MediaControlEvent.SeekBy(5000L))
            expect("""{"type":"set_position","positionUs":2500000}""", MediaControlEvent.SetPosition(2500L))
            expect("""{"type":"set_volume","volume":0.25}""", MediaControlEvent.SetVolume(0.25))
            expect("""{"type":"open_uri","uri":"file:///tmp/a.mp3"}""", MediaControlEvent.OpenUri("file:///tmp/a.mp3"))
            expect("""{"type":"raise"}""", MediaControlEvent.Raise)
            expect("""{"type":"quit"}""", MediaControlEvent.Quit)

            val ignored = CountDownLatch(1)
            received.set(MediaControlEvent.Play)
            fireNativeEvent("not-json")
            fireNativeEvent("""{"type":"unknown"}""")
            fireNativeEvent("""{"type":"seek"}""")
            fireNativeEvent("""{"type":"set_position"}""")
            fireNativeEvent("""{"type":"set_volume"}""")
            fireNativeEvent("""{"type":"open_uri"}""")
            assertFalse(ignored.await(150, TimeUnit.MILLISECONDS))
            assertEquals(MediaControlEvent.Play, received.get())
        } finally {
            MediaControlService.detach()
        }
    }

    private fun fireNativeEvent(json: String) {
        when (Platform.Current) {
            Platform.Linux -> NativeLinuxBridge.onMediaControlEvent(json)
            Platform.MacOS -> NativeMacOsBridge.onMediaControlEvent(json)
            Platform.Windows -> NativeWindowsBridge.onMediaControlEvent(json)
            else -> Unit
        }
    }
}

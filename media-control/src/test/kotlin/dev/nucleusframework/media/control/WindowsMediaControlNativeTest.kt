package dev.nucleusframework.media.control

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.media.control.windows.NativeWindowsBridge
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class WindowsMediaControlNativeTest {
    @AfterTest
    fun tearDown() {
        MediaControlService.detach()
    }

    @Test
    fun `smtc backend loads and accepts metadata playback and volume`() {
        if (Platform.Current != Platform.Windows) return
        assertTrue(NativeWindowsBridge.isLoaded, "nucleus_media_control_windows must load on Windows")
        assertTrue(MediaControlService.isAvailable())

        MediaControlService.configure(displayName = "NucleusKover")
        MediaControlService.setMetadata(
            MediaMetadata(title = "Song", artist = "A", album = "B", duration = 1_000L),
        )
        MediaControlService.setPlaybackState(MediaPlaybackState(MediaPlaybackStatus.PLAYING, 10L))
        MediaControlService.setVolume(0.4)
        MediaControlService.attach { }
        MediaControlService.detach()
    }
}

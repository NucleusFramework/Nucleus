package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapPlugIdsTest {
    @Test
    fun `snap plugs expose snapcraft ids`() {
        assertEquals("desktop", SnapPlug.Desktop.id)
        assertEquals("desktop-legacy", SnapPlug.DesktopLegacy.id)
        assertEquals("network-bind", SnapPlug.NetworkBind.id)
        assertEquals("audio-playback", SnapPlug.AudioPlayback.id)
        assertEquals("removable-media", SnapPlug.RemovableMedia.id)
    }
}

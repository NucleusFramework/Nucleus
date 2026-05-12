package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetFormatUpdateYmlFilenameTest {
    @Test
    fun `windows update metadata has no platform suffix`() {
        assertEquals("latest.yml", TargetFormat.Msi.updateYmlFilename(ReleaseChannel.Latest))
    }

    @Test
    fun `mac and linux update metadata include platform suffix`() {
        assertEquals("beta-mac.yml", TargetFormat.Dmg.updateYmlFilename(ReleaseChannel.Beta))
        assertEquals("alpha-linux.yml", TargetFormat.AppImage.updateYmlFilename(ReleaseChannel.Alpha))
    }
}

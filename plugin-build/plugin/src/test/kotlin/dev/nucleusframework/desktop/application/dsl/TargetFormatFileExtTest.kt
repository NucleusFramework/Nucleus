package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetFormatFileExtTest {
    @Test
    fun `packaged formats expose file extensions`() {
        assertEquals(".dmg", TargetFormat.Dmg.fileExt)
        assertEquals(".tar.gz", TargetFormat.Tar.fileExt)
        assertEquals(".7z", TargetFormat.SevenZ.fileExt)
    }

    @Test
    fun `raw app image has no file extension`() {
        assertThrows(IllegalStateException::class.java) {
            TargetFormat.RawAppImage.fileExt
        }
    }
}

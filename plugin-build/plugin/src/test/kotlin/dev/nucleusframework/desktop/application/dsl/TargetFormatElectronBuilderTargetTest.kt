package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetFormatElectronBuilderTargetTest {
    @Test
    fun `electron builder aliases are normalized`() {
        assertEquals("nsis", TargetFormat.Exe.electronBuilderTarget)
        assertEquals("nsis-web", TargetFormat.NsisWeb.electronBuilderTarget)
        assertEquals("tar.gz", TargetFormat.Tar.electronBuilderTarget)
    }

    @Test
    fun `raw app image is not an electron builder target`() {
        assertThrows(IllegalStateException::class.java) {
            TargetFormat.RawAppImage.electronBuilderTarget
        }
    }
}

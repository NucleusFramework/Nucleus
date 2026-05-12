package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutableTypeValueTest {
    @Test
    fun `target formats map to executable type values`() {
        assertEquals("dmg", TargetFormat.Dmg.executableTypeValue)
        assertEquals("nsis-web", TargetFormat.NsisWeb.executableTypeValue)
        assertEquals("7z", TargetFormat.SevenZ.executableTypeValue)
        assertEquals(EXECUTABLE_TYPE_DEV, TargetFormat.RawAppImage.executableTypeValue)
    }
}

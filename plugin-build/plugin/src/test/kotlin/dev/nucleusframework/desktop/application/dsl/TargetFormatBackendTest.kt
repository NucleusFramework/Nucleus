package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetFormatBackendTest {
    @Test
    fun `raw app image uses jpackage backend`() {
        assertEquals(PackagingBackend.JPACKAGE, TargetFormat.RawAppImage.backend)
    }

    @Test
    fun `installers use electron builder backend`() {
        assertEquals(PackagingBackend.ELECTRON_BUILDER, TargetFormat.Dmg.backend)
        assertEquals(PackagingBackend.ELECTRON_BUILDER, TargetFormat.Msi.backend)
        assertEquals(PackagingBackend.ELECTRON_BUILDER, TargetFormat.AppImage.backend)
    }
}

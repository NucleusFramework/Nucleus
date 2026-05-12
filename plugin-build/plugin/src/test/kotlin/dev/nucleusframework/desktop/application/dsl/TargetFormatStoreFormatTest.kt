package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetFormatStoreFormatTest {
    @Test
    fun `store formats are identified`() {
        assertTrue(TargetFormat.Pkg.isStoreFormat)
        assertTrue(TargetFormat.AppX.isStoreFormat)
        assertTrue(TargetFormat.Flatpak.isStoreFormat)
    }

    @Test
    fun `non store formats are not marked as store formats`() {
        assertFalse(TargetFormat.Dmg.isStoreFormat)
        assertFalse(TargetFormat.Msi.isStoreFormat)
    }
}

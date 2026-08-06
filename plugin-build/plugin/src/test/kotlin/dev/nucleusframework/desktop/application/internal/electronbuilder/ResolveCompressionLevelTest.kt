package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Per-format compression overrides for AppImage and Windows portable.
 *
 * Global [JvmApplicationDistributions.compressionLevel] stays the default; format-local
 * settings win so apps can keep Ultra for DEB/DMG while packaging AppImage/portable lighter.
 */
class ResolveCompressionLevelTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)

    @Test
    fun `null global and no override yields null`() {
        val dist = distributions()
        assertNull(resolveCompressionLevel(dist, TargetFormat.AppImage))
        assertNull(resolveCompressionLevel(dist, TargetFormat.Portable))
        assertNull(resolveCompressionLevel(dist, TargetFormat.Deb))
    }

    @Test
    fun `global level applies to every format without override`() {
        val dist = distributions()
        dist.compressionLevel = CompressionLevel.Ultra

        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.AppImage))
        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.Portable))
        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.Deb))
        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.Nsis))
        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.Dmg))
    }

    @Test
    fun `appImage override wins over global`() {
        val dist = distributions()
        dist.compressionLevel = CompressionLevel.Ultra
        dist.linux.appImage.compressionLevel = CompressionLevel.Normal

        assertEquals(CompressionLevel.Normal, resolveCompressionLevel(dist, TargetFormat.AppImage))
        // Sibling Linux formats keep the global level.
        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.Deb))
        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.Rpm))
    }

    @Test
    fun `portable override wins over global`() {
        val dist = distributions()
        dist.compressionLevel = CompressionLevel.Ultra
        dist.windows.portable.compressionLevel = CompressionLevel.Store

        assertEquals(CompressionLevel.Store, resolveCompressionLevel(dist, TargetFormat.Portable))
        // Sibling Windows formats keep the global level.
        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.Nsis))
        assertEquals(CompressionLevel.Ultra, resolveCompressionLevel(dist, TargetFormat.Msi))
    }

    @Test
    fun `format override without global is still used`() {
        val dist = distributions()
        dist.linux.appImage.compressionLevel = CompressionLevel.Store
        dist.windows.portable.compressionLevel = CompressionLevel.Normal

        assertEquals(CompressionLevel.Store, resolveCompressionLevel(dist, TargetFormat.AppImage))
        assertEquals(CompressionLevel.Normal, resolveCompressionLevel(dist, TargetFormat.Portable))
        assertNull(resolveCompressionLevel(dist, TargetFormat.Deb))
    }

    @Test
    fun `appImage and portable overrides are independent`() {
        val dist = distributions()
        dist.compressionLevel = CompressionLevel.Maximum
        dist.linux.appImage.compressionLevel = CompressionLevel.Normal
        dist.windows.portable.compressionLevel = CompressionLevel.Store

        assertEquals(CompressionLevel.Normal, resolveCompressionLevel(dist, TargetFormat.AppImage))
        assertEquals(CompressionLevel.Store, resolveCompressionLevel(dist, TargetFormat.Portable))
        assertEquals(CompressionLevel.Maximum, resolveCompressionLevel(dist, TargetFormat.Nsis))
    }
}

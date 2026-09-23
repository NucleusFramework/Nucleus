package dev.nucleusframework.graalvm

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.graalvm.locale.NativeLocaleBridge
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraalVmInitializerTest {
    @Test
    fun `hotspot jvm is not a native image`() {
        assertFalse(GraalVmInitializer.isNativeImage)
        assertEquals(
            System.getProperty("org.graalvm.nativeimage.imagecode") != null,
            GraalVmInitializer.isNativeImage,
        )
    }

    @Test
    fun `initialize is safe on a regular jvm`() {
        GraalVmInitializer.initialize()
        GraalVmInitializer.initialize()
        assertFalse(GraalVmInitializer.isNativeImage)
    }

    @Test
    fun `macos font config is resolved from the bundle Resources dir`() {
        val bundle = Files.createTempDirectory("nucleus-bundle").toFile()
        try {
            val execDir = bundle.resolve("Contents/MacOS")
            val resourcesDir = bundle.resolve("Contents/Resources")
            assertTrue(execDir.mkdirs())
            assertTrue(resourcesDir.mkdirs())

            assertNull(GraalVmInitializer.resolveMacOsFontConfig(execDir))

            val fontConfig = resourcesDir.resolve("fontconfig.bfc")
            fontConfig.writeBytes(byteArrayOf(0))
            assertEquals(fontConfig, GraalVmInitializer.resolveMacOsFontConfig(execDir))
        } finally {
            bundle.deleteRecursively()
        }
    }

    @Test
    fun `macos locale bridge reports a language tag when loaded`() {
        if (Platform.Current != Platform.MacOS) return
        assertTrue(NativeLocaleBridge.isLoaded)
        val tag = NativeLocaleBridge.nativePreferredLanguageTag()
        if (tag != null) {
            assertTrue(tag.isNotBlank())
            assertTrue(tag[0].isLetter())
        }
    }
}

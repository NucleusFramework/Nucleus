package dev.nucleusframework.graalvm

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.graalvm.locale.NativeLocaleBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

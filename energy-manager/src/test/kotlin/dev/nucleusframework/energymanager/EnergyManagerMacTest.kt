package dev.nucleusframework.energymanager

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnergyManagerMacTest {
    @AfterTest
    fun tearDown() {
        EnergyManager.resetAwakeForTests()
    }

    @Test
    fun `efficiency modes are available on this mac`() {
        assertTrue(EnergyManager.isAvailable())
        val light = EnergyManager.enableLightEfficiencyMode()
        assertTrue(light.success, light.message)
        assertTrue(EnergyManager.disableLightEfficiencyMode().success)
        val thread = EnergyManager.enableThreadEfficiencyMode()
        assertTrue(thread.success, thread.message)
        assertTrue(EnergyManager.disableThreadEfficiencyMode().success)
        val full = EnergyManager.enableEfficiencyMode()
        assertTrue(full.success, full.message)
        assertTrue(EnergyManager.disableEfficiencyMode().success)
    }

    @Test
    fun `keepAwake and acquireAwake coexist and release`() {
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY).success)
        assertTrue(EnergyManager.isAwakeActive())
        val handle = EnergyManager.acquireAwake(AwakeMode.SYSTEM_AND_DISPLAY)
        assertEquals(AwakeMode.SYSTEM_AND_DISPLAY, handle.mode)
        assertTrue(EnergyManager.isAwakeActive())
        handle.close()
        assertTrue(EnergyManager.isAwakeActive(), "explicit keepAwake slot still held")
        assertTrue(EnergyManager.releaseAwake().success)
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `replacing keepAwake mode then releasing drops the slot`() {
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success)
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY).success)
        assertTrue(EnergyManager.releaseAwake().success)
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `closing an acquire handle twice is safe`() {
        val handle = EnergyManager.acquireAwake(AwakeMode.SYSTEM_ONLY)
        handle.close()
        handle.close()
        assertFalse(EnergyManager.isAwakeActive())
    }
}

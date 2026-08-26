package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.energymanager.EnergyManager
import org.junit.After
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaoKeepScreenOnTest {
    @After
    fun tearDown() {
        TaoKeepScreenOn.resetForTests()
        EnergyManager.releaseAwake()
    }

    @Test
    fun `PlatformContext setter refcounts across scenes`() {
        assumeAvailable()
        val first = TestContext()
        val second = TestContext()

        first.isKeepScreenOnEnabled = true
        assertTrue(EnergyManager.isAwakeActive())

        second.isKeepScreenOnEnabled = true
        first.isKeepScreenOnEnabled = false
        assertTrue(EnergyManager.isAwakeActive(), "second scene must keep the request")

        second.isKeepScreenOnEnabled = false
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `idempotent setter does not double-acquire`() {
        assumeAvailable()
        val context = TestContext()
        context.isKeepScreenOnEnabled = true
        context.isKeepScreenOnEnabled = true
        context.isKeepScreenOnEnabled = false
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `keepScreenOn does not drop an explicit keepAwake slot`() {
        assumeAvailable()
        EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY)
        val context = TestContext()
        context.isKeepScreenOnEnabled = true
        context.isKeepScreenOnEnabled = false
        assertTrue(EnergyManager.isAwakeActive(), "app-owned keepAwake must survive")
        EnergyManager.releaseAwake()
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `Modifier keepScreenOn attaches and detaches through the scene`() {
        assumeAvailable()
        runTaoSceneTest {
            setContent {
                Box(Modifier.keepScreenOn())
            }
            assertTrue(EnergyManager.isAwakeActive())
        }
        assertFalse(EnergyManager.isAwakeActive())
    }

    private fun assumeAvailable() {
        assumeTrue("Energy manager not available", EnergyManager.isAvailable())
    }

    private class TestContext : TaoPlatformContextBase()
}

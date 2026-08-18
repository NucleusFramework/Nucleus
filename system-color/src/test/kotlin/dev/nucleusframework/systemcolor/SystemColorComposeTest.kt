package dev.nucleusframework.systemcolor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SystemColorComposeTest {
    @Test
    fun `composables match the mac detector snapshot`() =
        runComposeUiTest {
            var accent: androidx.compose.ui.graphics.Color? = androidx.compose.ui.graphics.Color.Transparent
            var contrast: Boolean? = null
            setContent {
                accent = systemAccentColor()
                contrast = isSystemInHighContrast()
            }
            waitForIdle()
            assertEquals(
                dev.nucleusframework.systemcolor.mac.MacSystemColorDetector.getAccentColor(),
                accent,
            )
            assertEquals(
                dev.nucleusframework.systemcolor.mac.MacSystemColorDetector.isHighContrast(),
                contrast,
            )
        }
}

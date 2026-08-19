package dev.nucleusframework.systemcolor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systemcolor.linux.LinuxSystemColorDetector
import dev.nucleusframework.systemcolor.mac.MacSystemColorDetector
import dev.nucleusframework.systemcolor.windows.WindowsSystemColorDetector
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SystemColorComposeTest {
    @Test
    fun `composables match the current platform detector snapshot`() =
        runComposeUiTest {
            var accent: androidx.compose.ui.graphics.Color? = androidx.compose.ui.graphics.Color.Transparent
            var contrast: Boolean? = null
            setContent {
                accent = systemAccentColor()
                contrast = isSystemInHighContrast()
            }
            waitForIdle()
            val expectedAccent =
                when (Platform.Current) {
                    Platform.MacOS -> MacSystemColorDetector.getAccentColor()
                    Platform.Windows -> WindowsSystemColorDetector.getAccentColor()
                    Platform.Linux -> LinuxSystemColorDetector.getAccentColor()
                    else -> null
                }
            val expectedContrast =
                when (Platform.Current) {
                    Platform.MacOS -> MacSystemColorDetector.isHighContrast()
                    Platform.Windows -> WindowsSystemColorDetector.isHighContrast()
                    Platform.Linux -> LinuxSystemColorDetector.isHighContrast()
                    else -> false
                }
            assertEquals(expectedAccent, accent)
            assertEquals(expectedContrast, contrast)
        }
}

package dev.nucleusframework.darkmodedetector

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class IsSystemInDarkModeTest {
    @Test
    fun `composable matches the platform detector`() =
        runComposeUiTest {
            val expected = getPlatformDarkModeDetector().isDark()
            var observed: Boolean? = null
            setContent {
                observed = isSystemInDarkMode()
            }
            waitForIdle()
            assertEquals(expected, observed)
        }
}

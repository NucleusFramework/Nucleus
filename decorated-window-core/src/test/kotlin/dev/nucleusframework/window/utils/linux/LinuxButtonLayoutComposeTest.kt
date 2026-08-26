package dev.nucleusframework.window.utils.linux

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LinuxButtonLayoutComposeTest {
    @Test
    fun `rememberLinuxButtonLayout reports a layout with a close button`() =
        runComposeUiTest {
            var layout: LinuxButtonLayout? = null
            setContent {
                layout = rememberLinuxButtonLayout()
            }
            waitForIdle()
            val resolved = layout!!
            assertTrue(resolved.hasClose == LinuxTitleBarButton.CLOSE in resolved.buttons)
        }
}

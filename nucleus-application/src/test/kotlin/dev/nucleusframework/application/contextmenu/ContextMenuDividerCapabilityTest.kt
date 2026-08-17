@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContextMenuDividerCapabilityTest {
    @Test
    fun `no divider is published by default`() {
        runComposeUiTest {
            setContent {
                assertNull(
                    "Compose's own representation cannot draw a divider",
                    LocalContextMenuDivider.current,
                )
            }
        }
    }

    @Test
    fun `the OS-looking provider publishes the nucleus divider`() {
        assumeTrue("OS-looking context menu unavailable on this host", isNativeContextMenuSupported)
        runComposeUiTest {
            setContent {
                NativeContextMenuProvider {
                    assertSame(NucleusContextMenuDivider, LocalContextMenuDivider.current)
                }
            }
        }
    }

    @Test
    fun `a disabled provider publishes no divider`() {
        runComposeUiTest {
            setContent {
                NativeContextMenuProvider(enabled = false) {
                    assertNull(LocalContextMenuDivider.current)
                }
            }
        }
    }
}

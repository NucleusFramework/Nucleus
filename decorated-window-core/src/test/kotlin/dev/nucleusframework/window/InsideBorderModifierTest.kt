package dev.nucleusframework.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.internal.insideBorder
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InsideBorderModifierTest {
    @Test
    fun `inside border draws on rectangle and rounded shapes without changing size`() =
        runComposeUiTest {
            var rect = IntSize.Zero
            var rounded = IntSize.Zero
            var skipped = IntSize.Zero
            setContent {
                Box(
                    Modifier
                        .size(48.dp)
                        .insideBorder(width = 2.dp, color = Color.Red, shape = RectangleShape)
                        .onSizeChanged { rect = it },
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .insideBorder(width = 3.dp, color = Color.Blue, shape = RoundedCornerShape(8.dp))
                        .onSizeChanged { rounded = it },
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .insideBorder(width = 0.dp, color = Color.Transparent)
                        .onSizeChanged { skipped = it },
                )
            }
            waitForIdle()
            assertTrue(rect.width > 0 && rect.height > 0)
            assertTrue(rounded.width > 0 && rounded.height > 0)
            assertTrue(skipped.width > 0 && skipped.height > 0)
        }
}

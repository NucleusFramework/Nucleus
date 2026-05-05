package io.github.kdroidfilter.sampleshared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Demonstrates `detectTransformGestures` driven by macOS trackpad pinch /
 * rotate / smart-magnify (Tao backend) and standard mouse drag.
 *
 * Modifier topology — important: the gesture detector lives on the **outer**
 * (viewport) Box, the visual transform lives on the **inner** Box. Compose
 * delivers `pan` to a `pointerInput` modifier in the local coordinate space
 * of that modifier *after* upstream `graphicsLayer` modifiers have been
 * un-applied. By keeping the detector outside the transformed layer, `pan`
 * arrives in raw viewport pixels — no scale/rotation forward-transform math
 * is needed, dragging follows the cursor 1:1 at any zoom or rotation, and
 * the touch surface never moves out from under the user.
 */
@Composable
fun ZoomTab(modifier: Modifier = Modifier) {
    var scale by remember { mutableStateOf(1f) }
    var rotation by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF0F1115)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "Trackpad gestures — pinch to zoom, two-finger rotate, drag to pan",
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        )
        BasicText(
            text = "scale=%.2f · rotation=%.1f° · offset=(%.0f, %.0f)".format(
                scale, rotation, offset.x, offset.y,
            ),
            style = TextStyle(color = Color(0xFFA0A4B0), fontSize = 12.sp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF15181D))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rot ->
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        rotation += rot
                        offset += pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        rotationZ = rotation,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF8AB4FF),
                                Color(0xFF34D399),
                                Color(0xFF8B5CF6),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Pinch / Rotate / Drag",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        BasicText(
            text = "Reset",
            style = TextStyle(color = Color(0xFF8AB4FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable {
                    scale = 1f
                    rotation = 0f
                    offset = Offset.Zero
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private const val MIN_SCALE: Float = 0.25f
private const val MAX_SCALE: Float = 6f

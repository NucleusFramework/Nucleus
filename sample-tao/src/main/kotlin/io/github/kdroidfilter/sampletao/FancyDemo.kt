package io.github.kdroidfilter.sampletao

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val PALETTE = listOf(
    Color(0xFF6366F1), // indigo
    Color(0xFFEC4899), // pink
    Color(0xFF06B6D4), // cyan
    Color(0xFFF59E0B), // amber
)

@Composable
fun FancyDemo(
    modifier: Modifier = Modifier,
    clicks: Int,
    onClick: () -> Unit,
    enabledBlobs: List<Boolean>,
) {
    val transition = rememberInfiniteTransition(label = "mesh")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
        ),
        label = "phase",
    )

    var cursor by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Move,
                        PointerEventType.Enter,
                        -> cursor = event.changes.first().position
                        PointerEventType.Exit -> cursor = null
                        PointerEventType.Press -> {
                            cursor = event.changes.first().position
                            onClick()
                        }
                        else -> Unit
                    }
                }
            }
        },
    ) {
        AnimatedMeshBackground(phase, cursor, enabledBlobs)
        GlassCard(clicks)
    }
}

@Composable
private fun AnimatedMeshBackground(
    phase: Float,
    cursor: Offset?,
    enabledBlobs: List<Boolean>,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07080B))
            .blur(120.dp)
            .drawBehind {
                val tau = (PI * 2).toFloat()
                val w = size.width
                val h = size.height

                PALETTE.forEachIndexed { idx, color ->
                    if (idx >= enabledBlobs.size || !enabledBlobs[idx]) return@forEachIndexed
                    val phaseI = (phase + idx * 0.27f) * tau
                    val cx = w * (0.5f + sin(phaseI) * 0.42f)
                    val cy = h * (0.5f + cos(phaseI * 1.3f + idx) * 0.42f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)),
                            center = Offset(cx, cy),
                            radius = w * 0.45f,
                        ),
                        radius = w * 0.45f,
                        center = Offset(cx, cy),
                    )
                }

                cursor?.let { c ->
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                            center = c,
                            radius = w * 0.18f,
                        ),
                        radius = w * 0.18f,
                        center = c,
                    )
                }
            },
    )
}

@Composable
private fun GlassCard(clicks: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                text = "Compose × Tao × Metal",
                style = TextStyle(
                    color = Color(0xFFF5F5FA),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = "no AWT — Skia drawing into a CAMetalLayer attached by Tao",
                style = TextStyle(
                    color = Color(0xFFB7B9C4),
                    fontSize = 14.sp,
                ),
            )
            Spacer(Modifier.height(28.dp))
            BasicText(
                text = "clicks · $clicks",
                style = TextStyle(
                    color = Color(0xFF8AB4FF),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

package dev.nucleusframework.lab.probes.rendering.showcase

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.common.FrameMeter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Palette =
    listOf(
        Color(0xFF6366F1),
        Color(0xFFEC4899),
        Color(0xFF06B6D4),
        Color(0xFFF59E0B),
    )

@Composable
internal fun FancyScene(
    state: ShowcaseState,
    meter: FrameMeter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cursor by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter -> cursor = event.changes.first().position
                        PointerEventType.Exit -> cursor = null
                        PointerEventType.Press -> onClick()
                        else -> Unit
                    }
                }
            }
        },
    ) {
        if (state.animating) {
            val phase by rememberInfiniteTransition(label = "mesh").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
                label = "phase",
            )
            MeshBackground({ phase }, cursor.takeIf { state.cursorGlow }, state, meter)
        } else {
            MeshBackground({ 0.2f }, cursor.takeIf { state.cursorGlow }, state, meter)
        }
        GlassCard(state.clicks)
    }
}

@Composable
private fun MeshBackground(
    phase: () -> Float,
    cursor: Offset?,
    state: ShowcaseState,
    meter: FrameMeter,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07080B))
            .then(if (state.blur) Modifier.blur(120.dp) else Modifier)
            .drawBehind {
                meter.tick()
                val tau = (PI * 2).toFloat()
                val w = size.width
                val h = size.height
                Palette.forEachIndexed { index, color ->
                    if (!state.blobs.getOrElse(index) { false }) return@forEachIndexed
                    val p = (phase() + index * 0.27f) * tau
                    val center = Offset(w * (0.5f + sin(p) * 0.42f), h * (0.5f + cos(p * 1.3f + index) * 0.42f))
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)),
                                center,
                                w * 0.45f,
                            ),
                        radius = w * 0.45f,
                        center = center,
                    )
                }
                cursor?.let { c ->
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                                c,
                                w * 0.18f,
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
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(horizontal = 40.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Compose × Skia on Tao",
                color = Color(0xFFF5F5FA),
                style = LabTheme.typography.title.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
            )
            Text(
                "click anywhere · move the pointer",
                color = Color(0xFFB7B9C4),
                style = LabTheme.typography.body,
            )
            Text(
                "clicks · $clicks",
                color = Color(0xFF8AB4FF),
                style = LabTheme.typography.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

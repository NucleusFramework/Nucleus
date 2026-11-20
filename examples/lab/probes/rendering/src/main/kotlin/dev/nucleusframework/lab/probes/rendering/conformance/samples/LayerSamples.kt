package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabSlider
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory

@OptIn(ExperimentalLayoutApi::class)
val LayerSamples: List<SampleEntry> =
    samples(SampleCategory.Layers) {
        sample(
            "transform",
            "graphicsLayer transforms",
            "Drag the slider: the card rotates in 3D around Y, scales and fades smoothly; its text stays crisp at rest.",
        ) {
            var t by remember { mutableFloatStateOf(0.3f) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabSlider(t) { t = it }
                Box(
                    Modifier
                        .padding(24.dp)
                        .graphicsLayer {
                            rotationY = t * 60f
                            rotationZ = t * 10f
                            scaleX = 1f - t * 0.3f
                            scaleY = 1f - t * 0.3f
                            alpha = 1f - t * 0.6f
                            cameraDistance = 12 * density
                        }.size(200.dp, 110.dp)
                        .background(Color(0xFF6366F1), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("graphicsLayer", color = Color.White, style = LabTheme.typography.heading) }
            }
        }
        sample(
            "shadows",
            "Elevation, drop and inner shadows",
            "Elevation shadows grow with elevation; the coloured drop shadow and the inner shadow follow the rounded shape, with no clipped or square corners.",
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                listOf(1, 4, 12, 24).forEach { elevation ->
                    Box(
                        Modifier
                            .size(
                                72.dp,
                            ).shadow(
                                elevation.dp,
                                RoundedCornerShape(12.dp),
                            ).background(LabTheme.colors.panel, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("$elevation dp") }
                }
                Box(
                    Modifier
                        .size(72.dp)
                        .dropShadow(
                            RoundedCornerShape(12.dp),
                            Shadow(
                                radius = 16.dp,
                                color = Color(0xAAEC4899),
                                offset =
                                    androidx.compose.ui.unit
                                        .DpOffset(0.dp, 6.dp),
                            ),
                        ).background(Color(0xFFEC4899), RoundedCornerShape(12.dp)),
                )
                Box(
                    Modifier
                        .size(72.dp)
                        .background(LabTheme.colors.raised, RoundedCornerShape(12.dp))
                        .innerShadow(RoundedCornerShape(12.dp), Shadow(radius = 10.dp, color = Color(0x88000000))),
                )
            }
        }
        sample(
            "blur",
            "Blur & render effects",
            "Modifier.blur softens the pill evenly; the BlurEffect layer blurs its whole subtree; edges stay inside the bounds (Decal) or spread (Clamp).",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(96.dp).blur(8.dp).background(Color(0xFF06B6D4), RoundedCornerShape(50)))
                Box(
                    Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            renderEffect = BlurEffect(12f, 12f, TileMode.Decal)
                        }.background(Color(0xFFF59E0B), CircleShape),
                )
                Box(
                    Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            renderEffect = BlurEffect(12f, 12f, TileMode.Clamp)
                        }.background(Color(0xFF10B981), CircleShape),
                )
            }
        }
        sample(
            "offscreen",
            "Compositing strategy",
            "Left: overlapping children at 50 % alpha each blend (darker overlap). Right: Offscreen composites first, so the overlap is uniform.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                listOf(CompositingStrategy.ModulateAlpha, CompositingStrategy.Offscreen).forEach { strategy ->
                    Box(
                        Modifier.size(140.dp, 90.dp).graphicsLayer {
                            alpha = 0.5f
                            compositingStrategy = strategy
                        },
                    ) {
                        Box(Modifier.size(80.dp).background(Color(0xFF6366F1), CircleShape))
                        Box(Modifier.padding(start = 50.dp).size(80.dp).background(Color(0xFFEC4899), CircleShape))
                    }
                }
            }
        }
    }

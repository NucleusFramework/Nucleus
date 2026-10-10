package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory

val MotionSamples: List<SampleEntry> =
    samples(SampleCategory.Motion) {
        sample(
            "springs",
            "Value animations",
            "Click: the block springs across with a slight bounce while its colour cross-fades; clicking mid-flight retargets with no jump.",
        ) {
            var moved by remember { mutableStateOf(false) }
            val offset by animateDpAsState(
                if (moved) 220.dp else 0.dp,
                spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
                label = "offset",
            )
            val color by animateColorAsState(if (moved) Color(0xFFEC4899) else Color(0xFF6366F1), label = "color")
            Box(Modifier.width(300.dp).clickable { moved = !moved }.padding(8.dp)) {
                Box(Modifier.offset(x = offset).size(56.dp).background(color, RoundedCornerShape(12.dp)))
            }
        }
        sample(
            "visibility",
            "AnimatedVisibility & content size",
            "Toggle: the panel expands and fades in, collapses on the way out; the card below grows smoothly when its text is longer.",
        ) {
            var shown by remember { mutableStateOf(true) }
            var long by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction(if (shown) "Hide" else "Show") { shown = !shown }
                    SecondaryAction("Resize card") { long = !long }
                }
                AnimatedVisibility(
                    shown,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Box(
                        Modifier
                            .size(
                                260.dp,
                                70.dp,
                            ).background(LabTheme.colors.selection, RoundedCornerShape(12.dp)),
                    )
                }
                Text(
                    if (long) LONG_TEXT else "Short",
                    Modifier
                        .width(
                            260.dp,
                        ).animateContentSize()
                        .background(
                            LabTheme.colors.raised,
                            RoundedCornerShape(12.dp),
                        ).padding(12.dp),
                )
            }
        }
        sample(
            "content",
            "AnimatedContent",
            "Each click slides the old number out and the new one in, with no overlap glitch or flicker at the end of the transition.",
        ) {
            var count by remember { mutableIntStateOf(0) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryAction("+1") { count++ }
                AnimatedContent(count, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "count") {
                    Text("$it", style = LabTheme.typography.title.copy(fontSize = 36.sp))
                }
            }
        }
    }

private const val LONG_TEXT =
    "A much longer text that needs more room, so the card animates its size instead of jumping."

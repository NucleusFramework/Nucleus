package dev.nucleusframework.lab.probes.rendering.conformance

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.probes.rendering.conformance.samples.DrawingSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.ExpressiveSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.ImageSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.LayerSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.LayoutSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.ListSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.MaterialOverlaySamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.MaterialSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.MotionSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.SampleEntry
import dev.nucleusframework.lab.probes.rendering.conformance.samples.StateSamples
import dev.nucleusframework.lab.probes.rendering.conformance.samples.TextSamples

/** Every conformance sample, in category order. */
object ConformanceCatalog {
    val entries: List<SampleEntry> by lazy {
        TextSamples + LayoutSamples + DrawingSamples + LayerSamples + ImageSamples + ListSamples +
            MotionSamples + StateSamples + MaterialSamples + MaterialOverlaySamples + ExpressiveSamples
    }

    val samples: List<Sample> by lazy { entries.map { it.sample } }

    private val byId by lazy { entries.associateBy { it.sample.id } }

    fun entry(sample: Sample): SampleEntry? = byId[sample.id]
}

private val OutlineColor = Color(0xFFE040FB)
private val GridColor = Color(0x33E040FB)

/** Renders [content] under the levers: direction, font scale, extra density, debug outline. */
@Composable
fun Levered(
    levers: Levers,
    content: @Composable () -> Unit,
) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalLayoutDirection provides if (levers.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalDensity provides Density(base.density * levers.densityScale, base.fontScale * levers.fontScale),
    ) {
        val outline =
            if (levers.outlines) {
                Modifier.border(1.dp, OutlineColor).drawBehind {
                    // An 8 dp grid: alignment drift and fractional-scale rounding show against it.
                    val step = 8.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawLine(GridColor, Offset(x, 0f), Offset(x, size.height))
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(GridColor, Offset(0f, y), Offset(size.width, y))
                        y += step
                    }
                }
            } else {
                Modifier
            }
        Box(outline.padding(4.dp)) { content() }
    }
}

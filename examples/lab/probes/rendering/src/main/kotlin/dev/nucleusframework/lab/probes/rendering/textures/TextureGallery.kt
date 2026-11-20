package dev.nucleusframework.lab.probes.rendering.textures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.OverlayPill
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.probes.rendering.common.FrameMeter
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.TextureViewController
import dev.nucleusframework.window.tao.rememberTextureViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Controllers of the four producers, one per source. */
class TextureControllers(
    val primary: TextureViewController,
    val secondary: TextureViewController,
    val planar: TextureViewController,
    val swapped: TextureViewController,
)

@Composable
fun rememberTextureControllers(): TextureControllers {
    val primary = rememberTextureViewController()
    val secondary = rememberTextureViewController()
    val planar = rememberTextureViewController()
    val swapped = rememberTextureViewController()
    // One stable holder, so composables taking it can skip.
    return remember(primary, secondary, planar, swapped) { TextureControllers(primary, secondary, planar, swapped) }
}

/**
 * Display-paced producer loop: one producer frame per composited frame, the GPU work on a
 * background dispatcher, only the draw pass invalidated — the gallery must never recompose
 * for a frame.
 */
@Composable
fun ProducerLoop(
    bench: TextureBench,
    controllers: TextureControllers,
    animating: Boolean,
    producerMeter: FrameMeter,
) {
    LaunchedEffect(bench, animating) {
        // No producer, or paused: stay out of the frame clock, or it would spin on empty frames.
        if (bench.all.isEmpty() || !animating) return@LaunchedEffect
        var tick = 0
        while (isActive) {
            withFrameNanos { }
            val background = Color.hsv((tick % 360).toFloat(), 0.65f, 0.75f).toArgb()
            withContext(Dispatchers.Default) {
                bench.primary?.let {
                    it.draw(tick, background)
                    controllers.primary.markFrameAvailable()
                }
                bench.secondary?.let {
                    it.draw(tick, background)
                    controllers.secondary.markFrameAvailable()
                }
                // The fence is handed over: the compositor's GPU waits on it, the producer never blocks.
                bench.planar?.drawFenced(tick, background)?.let(controllers.planar::markFrameAvailable)
                bench.swappedPlanar?.let {
                    it.draw(tick, background)
                    controllers.swapped.markFrameAvailable()
                }
            }
            producerMeter.tick()
            tick++
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextureGallery(
    bench: TextureBench,
    controllers: TextureControllers,
    compositedMeter: FrameMeter,
) {
    val primary = bench.primary?.source
    SubHeading("Primary producer — contentScale")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LabDimens.blockGap)) {
        TextureSpecimen("FillBounds", 160.dp, 120.dp) { size ->
            TextureView(
                primary,
                controller = controllers.primary,
                modifier = size.drawBehind { compositedMeter.tick() },
                contentScale = ContentScale.FillBounds,
            )
        }
        TextureSpecimen("Fit (letterbox)", 120.dp, 120.dp) { size ->
            TextureView(primary, controller = controllers.primary, modifier = size, contentScale = ContentScale.Fit)
        }
        TextureSpecimen("Crop", 120.dp, 120.dp) { size ->
            TextureView(primary, controller = controllers.primary, modifier = size, contentScale = ContentScale.Crop)
        }
        // Room for the rotated corners, so only the texture's own clip cuts them.
        TextureSpecimen("Rotated + Compose on top", 168.dp, 168.dp) { size ->
            Box(size, contentAlignment = Alignment.Center) {
                TextureView(
                    primary,
                    controller = controllers.primary,
                    modifier = Modifier.size(120.dp).rotate(15f).clip(RoundedCornerShape(12.dp)),
                )
                OverlayPill("Compose")
            }
        }
    }
    SubHeading("filterQuality, ${TEXTURE_WIDTH}×$TEXTURE_HEIGHT upscaled 2.5×")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LabDimens.blockGap)) {
        TextureSpecimen("None (nearest)", 320.dp, 240.dp) { size ->
            TextureView(primary, controller = controllers.primary, modifier = size, filterQuality = FilterQuality.None)
        }
        TextureSpecimen("High (cubic)", 320.dp, 240.dp) { size ->
            TextureView(primary, controller = controllers.primary, modifier = size, filterQuality = FilterQuality.High)
        }
    }
    bench.secondary?.let { secondary ->
        SubHeading("Secondary producer — ${secondary.syncMode}")
        TextureSpecimen(null, 160.dp, 120.dp) { size ->
            TextureView(secondary.source, controller = controllers.secondary, modifier = size)
        }
    }
    if (bench.planar != null) {
        SubHeading("Planar DMA-BUF: I420 then YV12")
        Hint("Both must match the packed boxes: same colours, same orientation.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LabDimens.blockGap)) {
            TextureSpecimen("I420", 160.dp, 120.dp) { size ->
                TextureView(bench.planar.source, controller = controllers.planar, modifier = size)
            }
            TextureSpecimen("YV12", 160.dp, 120.dp) { size ->
                TextureView(bench.swappedPlanar?.source, controller = controllers.swapped, modifier = size)
            }
        }
    }
}

/** One texture box, framed as a specimen of a fixed size; [content] gets the size modifier. */
@Composable
internal fun TextureSpecimen(
    caption: String?,
    width: Dp,
    height: Dp,
    content: @Composable (size: Modifier) -> Unit,
) {
    SpecimenFrame(Modifier.width(width), caption = caption) { content(Modifier.size(width, height)) }
}

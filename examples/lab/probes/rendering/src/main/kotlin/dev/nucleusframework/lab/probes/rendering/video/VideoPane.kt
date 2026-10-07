package dev.nucleusframework.lab.probes.rendering.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.OverlayPill
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.probes.rendering.common.FrameMeter
import dev.nucleusframework.lab.probes.rendering.common.rememberRecompositionCounter
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.rememberTextureViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Display-paced pull: one pull per composited frame on a background dispatcher, only the
 * draw pass invalidated; the backend drops ticks that come before the next frame is due.
 */
@Composable
internal fun VideoPane(
    stream: VideoStream?,
    transforms: Set<VideoTransform>,
    meter: FrameMeter,
    onDrawPass: () -> Unit,
) {
    val controller = rememberTextureViewController()
    val recompositions = rememberRecompositionCounter()
    LaunchedEffect(stream) {
        if (stream == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            if (withContext(Dispatchers.Default) { stream.pullFrame() }) {
                controller.markFrameAvailable()
                meter.tick()
            }
        }
    }
    Readout("video pane recompositions", recompositions[0].toString())
    SpecimenFrame(Modifier.drawBehind { onDrawPass() }, height = 380.dp) {
        var modifier: Modifier = Modifier.fillMaxSize().padding(12.dp)
        if (VideoTransform.Clip in transforms) modifier = modifier.clip(RoundedCornerShape(28.dp))
        if (VideoTransform.Rotate in transforms) modifier = modifier.rotate(8f)
        if (VideoTransform.Fade in transforms) modifier = modifier.alpha(0.5f)
        TextureView(
            stream?.source,
            modifier.align(Alignment.Center),
            controller,
            contentScale = if (VideoTransform.Crop in transforms) ContentScale.Crop else ContentScale.Fit,
        )
        if (VideoTransform.Overlay in transforms) VideoOverlay()
    }
}

@Composable
private fun VideoOverlay() {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
        horizontalAlignment = Alignment.End,
    ) {
        OverlayPill("Compose, above the video")
        // Translucent, so the video shows through: the frame takes part in blending.
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0x9934D399)))
    }
}

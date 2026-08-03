package dev.nucleusframework.sampletao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.tao.D3D11TestTextureProducer
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.rememberTextureViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TEX_W = 128
private const val TEX_H = 96

/**
 * TextureView demo (discussion #338): external D3D11 producers render an
 * animated pattern on their own device+thread; frames reach the Compose
 * scene via `markFrameAvailable` — draw-only invalidation, zero
 * recompositions per frame (watch the recomposition counter stay put
 * while the pattern animates). The keyed-mutex producer takes the
 * tear-free staging path automatically; the plain one is sampled
 * zero-copy. Windows-only; elsewhere the boxes stay empty.
 */
@Suppress("FunctionNaming", "MagicNumber", "LongMethod")
@Composable
fun TextureTab(modifier: Modifier = Modifier) {
    val syncProducer = remember { D3D11TestTextureProducer.create(TEX_W, TEX_H, useKeyedMutex = true) }
    val rawProducer = remember { D3D11TestTextureProducer.create(TEX_W, TEX_H, useKeyedMutex = false) }
    DisposableEffect(Unit) {
        onDispose {
            syncProducer?.close()
            rawProducer?.close()
        }
    }

    val syncController = rememberTextureViewController()
    val rawController = rememberTextureViewController()

    // Producer loop on a background dispatcher: proves markFrameAvailable
    // is thread-safe and that animation never touches the composition.
    LaunchedEffect(syncProducer, rawProducer) {
        withContext(Dispatchers.Default) {
            var tick = 0
            while (isActive) {
                val hue = (tick % 360).toFloat()
                val bg = Color.hsv(hue, 0.65f, 0.75f).toArgb()
                if (syncProducer != null) {
                    syncProducer.drawTestPattern(tick, bg)
                    syncController.markFrameAvailable()
                }
                if (rawProducer != null) {
                    rawProducer.drawTestPattern(tick, bg)
                    rawController.markFrameAvailable()
                }
                tick++
                delay(16)
            }
        }
    }

    // Increments on every recomposition of this tab — must NOT follow the
    // 60 fps producer (frame updates invalidate the draw pass only).
    val recompositions = remember { intArrayOf(0) }
    recompositions[0]++

    val label = TextStyle(color = Color(0xFFA0A4B0), fontSize = 11.sp)
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text =
                if (syncProducer == null) {
                    "TextureView — producer unavailable (not Windows, or D3D11/ANGLE missing)"
                } else {
                    "TextureView — external D3D11 producers, ${TEX_W}x$TEX_H @ 60 fps " +
                        "(recompositions=${recompositions[0]})"
                },
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 13.sp),
        )

        BasicText("Keyed mutex (tear-free staging) — shared import, contentScale variants:", style = label)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(160.dp, 120.dp),
                    contentScale = ContentScale.FillBounds,
                )
                BasicText("FillBounds", style = label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(120.dp, 120.dp),
                    contentScale = ContentScale.Fit,
                )
                BasicText("Fit (letterbox)", style = label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(120.dp, 120.dp),
                    contentScale = ContentScale.Crop,
                )
                BasicText("Crop", style = label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    TextureView(
                        source = syncProducer?.source,
                        controller = syncController,
                        modifier =
                            Modifier
                                .size(120.dp)
                                .rotate(15f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1F2630)),
                    )
                    BasicText(
                        text = "Compose on top",
                        style = TextStyle(color = Color.White, fontSize = 11.sp),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xCC15181D))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                BasicText("Rotated + overlap", style = label)
            }
        }

        BasicText("filterQuality on a ${TEX_W}x$TEX_H texture upscaled 2.5x:", style = label)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(320.dp, 240.dp),
                    filterQuality = FilterQuality.None,
                )
                BasicText("None (nearest)", style = label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(320.dp, 240.dp),
                    filterQuality = FilterQuality.High,
                )
                BasicText("High (cubic)", style = label)
            }
        }

        BasicText("No mutex — true zero copy (producer just flushes):", style = label)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextureView(
                source = rawProducer?.source,
                controller = rawController,
                modifier = demoBox(160.dp, 120.dp),
            )
        }
    }
}

private fun demoBox(
    width: Dp,
    height: Dp,
): Modifier =
    Modifier
        .size(width, height)
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF1F2630))

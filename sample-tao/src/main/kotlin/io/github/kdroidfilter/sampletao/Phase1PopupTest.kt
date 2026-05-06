package io.github.kdroidfilter.sampletao

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.kdroidfilter.nucleus.window.tao.TaoWindow
import io.github.kdroidfilter.nucleus.window.tao.render.LocalTaoPopupHost
import io.github.kdroidfilter.nucleus.window.tao.render.TaoPopupRenderer

/**
 * Phase 2 smoke-test row.
 *
 * The Show button now drives a real [TaoPopupRenderer]: the panel hosts a
 * transparent CAMetalLayer + an inner `CanvasLayersComposeScene` that
 * renders a Compose tree (rounded red box + live counter incremented
 * by the Bump button). Goals to validate visually:
 *  1. The panel content is *Compose-rendered*, not native — rounded
 *     corners, anti-aliased text, etc.
 *  2. State changes (Bump) re-trigger a render through the parent host's
 *     redraw cycle, proving the per-popup renderer is hooked into the
 *     frame pump (see `popupRenderers` registry in `TaoComposeSceneHost`).
 *  3. Move resizes both the NSPanel and the inner scene without flicker.
 *  4. Hide tears everything down cleanly.
 *
 * Will be deleted once Phase 7 ships. Phase 3 will replace this manual
 * driver with `Popup`-framework integration via `ComposeSceneContext`.
 */
@Composable
internal fun Phase1PopupTest(window: TaoWindow, onLog: (String) -> Unit) {
    val popupHost = LocalTaoPopupHost.current
    var renderer by remember { mutableStateOf<TaoPopupRenderer?>(null) }
    var bumpCount by remember { mutableIntStateOf(0) }
    var moved by remember { mutableStateOf(false) }
    var phase3PopupVisible by remember { mutableStateOf(false) }
    var phase3Bump by remember { mutableIntStateOf(0) }

    DisposableEffect(window) {
        onDispose { renderer?.dispose() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF152030))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = "Phase 2/3 popup smoke-test (popupHost=${popupHost != null}):",
            style = TextStyle(color = Color(0xFFA0A4B0), fontSize = 11.sp),
        )
        Button("P2 Show") {
            if (renderer != null) {
                onLog("[Phase2] popup already shown")
                return@Button
            }
            val host = popupHost ?: run {
                onLog("[Phase2] no popup host available")
                return@Button
            }
            val r = TaoPopupRenderer(host)
            r.create(xPx = 120, yPx = 80, widthPx = 320, heightPx = 200)
            r.setContent { Phase2Content(bumpCountProvider = { bumpCount }) }
            renderer = r
            onLog("[Phase2] popup shown @ (120,80) 320×200")
        }
        Button("Bump") {
            bumpCount++
            phase3Bump++
            onLog("[Phase2/3] bump → $bumpCount / $phase3Bump")
        }
        Button("P2 Move") {
            val r = renderer ?: run {
                onLog("[Phase2] no popup to move"); return@Button
            }
            moved = !moved
            if (moved) {
                r.setBounds(xPx = 420, yPx = 200, widthPx = 240, heightPx = 160)
                onLog("[Phase2] moved to (420,200) 240×160")
            } else {
                r.setBounds(xPx = 120, yPx = 80, widthPx = 320, heightPx = 200)
                onLog("[Phase2] moved back to (120,80) 320×200")
            }
        }
        Button("P2 Hide") {
            renderer?.dispose()
            renderer = null
            onLog("[Phase2] popup disposed")
        }
        Button(if (phase3PopupVisible) "P3 Hide" else "P3 Show") {
            phase3PopupVisible = !phase3PopupVisible
            onLog("[Phase3] popup visible=$phase3PopupVisible")
        }
    }

    // Phase 3 — uses Compose's actual `Popup` composable. This routes
    // through `LocalComposeSceneContext.current.createLayer(...)`, which
    // resolves to our `TaoComposeSceneContext` because the host scene is
    // a `PlatformLayersComposeScene`. The popup's content is rendered
    // into a `TaoPopupSceneLayer` = a borderless NSPanel sibling of the
    // host NSWindow.
    if (phase3PopupVisible) {
        Popup(
            popupPositionProvider = AbsolutePopupPositionProvider(IntOffset(700, 120)),
            onDismissRequest = { phase3PopupVisible = false },
            properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC1F2937))
                    .border(2.dp, Color(0xFF60A5FA), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicText(
                    text = "Phase 3 Popup\n(real Compose Popup composable)\nbump=$phase3Bump",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

private class AbsolutePopupPositionProvider(private val anchor: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = anchor
}

@Composable
private fun Phase2Content(bumpCountProvider: () -> Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCCDC2626))                 // semi-transparent red
            .border(2.dp, Color.White, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "Hello from Compose\ninside an NSPanel\nbump=${bumpCountProvider()}",
            style = TextStyle(
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF3B82F6))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

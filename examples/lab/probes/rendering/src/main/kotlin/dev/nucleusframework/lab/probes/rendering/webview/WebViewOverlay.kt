package dev.nucleusframework.lab.probes.rendering.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabSurfaces
import dev.nucleusframework.lab.designsystem.OverlayPill
import dev.nucleusframework.lab.designsystem.Text

/**
 * Compose drawn in the WebView's content slot, i.e. over the native view: a chip that
 * toggles a focusable Compose `Popup`, and a status pill. Both must stay visible above the
 * page on every OS (hole punch on macOS/Linux, DirectComposition overlay on Windows).
 */
@Composable
internal fun WebViewOverlay(
    state: WebProbeState,
    onTogglePopup: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(LabDimens.page)) {
        OverlayPill(
            if (state.popupShown) "Hide popup" else "Popup over WebView",
            Modifier.align(Alignment.TopStart),
            onClick = onTogglePopup,
        )
        if (state.popupShown) {
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = onTogglePopup,
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    Modifier
                        .padding(start = 24.dp, top = 64.dp)
                        .clip(LabShapes.block)
                        .background(LabSurfaces.overlay)
                        .padding(LabDimens.block),
                ) {
                    Text("Compose Popup over NativeView — text must be crisp", color = LabSurfaces.onOverlay)
                }
            }
        }
        val status =
            when {
                state.loading -> "loading ${(state.progress * 100).toInt()} %"
                else -> state.title ?: state.lastLoadedUrl ?: "—"
            }
        OverlayPill("Compose overlay · $status", Modifier.align(Alignment.BottomEnd))
    }
}

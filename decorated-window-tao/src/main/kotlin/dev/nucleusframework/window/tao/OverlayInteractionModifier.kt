package dev.nucleusframework.window.tao

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * Previously registered a NativeView overlay-scene hit region. Interop
 * blending now hit-tests Compose siblings and the [NativeView] `content`
 * slot in the host scene, so this modifier is a no-op except for
 * [cursor], which maps to [pointerHoverIcon].
 */
@Deprecated(
    message =
        "Interop blending hit-tests Compose over NativeView in the host " +
            "scene. Drop this modifier; pass cursor via pointerHoverIcon if needed.",
)
public fun Modifier.consumeOverlayPointerEvents(cursor: PointerIcon? = null): Modifier =
    if (cursor == null) {
        this
    } else {
        pointerHoverIcon(cursor, overrideDescendants = true)
    }

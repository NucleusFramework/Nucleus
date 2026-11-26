package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation

/**
 * Compose 1.13 resolves its graphics and text backends through registries that
 * only the AWT `ComposeContainer` and `ImageComposeScene` fill. A Tao scene, or
 * any Compose `ImageBitmap` / `Paint` / `Path` built before the first window
 * (window and tray icons), fails without this call. Registration of the same
 * singletons is idempotent, so every entry point calls it.
 */
@OptIn(InternalComposeUiApi::class)
internal fun ensureSkikoComposeBackend() {
    registerSkikoComposeImplementation()
}

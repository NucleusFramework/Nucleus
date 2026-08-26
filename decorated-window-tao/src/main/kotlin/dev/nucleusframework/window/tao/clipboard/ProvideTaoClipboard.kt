@file:Suppress("DEPRECATION")

package dev.nucleusframework.window.tao.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxClipboardBridge

/**
 * Routes Compose's clipboard through GTK for the duration of [content], so
 * copy/paste follows the GDK backend the Tao window actually runs on instead
 * of AWT's X11-only clipboard (issue #582).
 *
 * A no-op — composition-local-wise — when the native helper is unavailable
 * (non-Linux, missing library, headless process, GTK never initialised): the
 * locals are left on their AWT-backed defaults, which is exactly the fallback
 * we want.
 *
 * The inherited values are read *outside* the provider and become the
 * implementations' fallback, so nothing has to reconstruct Compose's AWT
 * clipboard (its classes are internal) and a parent that provided its own
 * clipboard still wins for everything GTK cannot serve.
 *
 * `LocalClipboardManager` is deprecated upstream but still provided: legacy
 * call sites would otherwise keep reading the X11 selection while everything
 * else reads the Wayland one.
 */
@Composable
internal fun ProvideTaoClipboard(content: @Composable () -> Unit) {
    val awtClipboard = LocalClipboard.current
    val awtClipboardManager = LocalClipboardManager.current
    val clipboard =
        remember(awtClipboard) {
            if (NativeTaoLinuxClipboardBridge.isAvailable) {
                TaoLinuxClipboard(fallback = awtClipboard)
            } else {
                null
            }
        }
    if (clipboard == null) {
        content()
        return
    }
    val manager = remember(awtClipboardManager) { TaoLinuxClipboardManager(fallback = awtClipboardManager) }
    CompositionLocalProvider(
        LocalClipboard provides clipboard,
        LocalClipboardManager provides manager,
        content = content,
    )
}

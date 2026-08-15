package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Hides the native GTK/KWin titlebar while custom Compose chrome ([TitleBar],
 * [dev.nucleusframework.window.WindowScaffold], [dev.nucleusframework.window.DialogTitleBar])
 * is composed. Without this, KDE would show both the native frame we keep for
 * #425 and the in-content bar.
 *
 * No-op on desktops that already use the hidden-titlebar CSD path.
 */
@Composable
internal fun HideNativeLinuxTitlebarWhileComposed(window: TaoWindow) {
    if (!linuxKeepsNativeWindowDecorations(undecorated = false)) return
    DisposableEffect(window.handle) {
        claimNativeLinuxTitlebarHidden(window)
        onDispose { releaseNativeLinuxTitlebarHidden(window) }
    }
}

private val hideClaims = ConcurrentHashMap<Long, Int>()

private fun claimNativeLinuxTitlebarHidden(window: TaoWindow) {
    val next = hideClaims.merge(window.handle, 1) { current, _ -> current + 1 } ?: 1
    if (next == 1) {
        NativeTaoBridge.nativeLinuxSetTitlebarVisible(window.handle, false)
    }
}

private fun releaseNativeLinuxTitlebarHidden(window: TaoWindow) {
    val remaining =
        hideClaims.compute(window.handle) { _, current ->
            val next = (current ?: 0) - 1
            if (next <= 0) null else next
        }
    if (remaining == null) {
        NativeTaoBridge.nativeLinuxSetTitlebarVisible(window.handle, true)
    }
}

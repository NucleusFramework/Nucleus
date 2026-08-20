@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package dev.nucleusframework.window.tao.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxClipboardBridge
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * GTK-backed [ClipboardManager] for the Tao backend on Linux — the blocking
 * counterpart of [TaoLinuxClipboard], for the `LocalClipboardManager` API
 * Compose deprecated in favour of `LocalClipboard`. Provided so third-party
 * composables still on the old local get the same clipboard as the rest of the
 * app instead of silently reading AWT's X11 selection.
 *
 * GTK has no non-blocking way to serve a synchronous read: both native calls
 * used here spin a nested GTK main loop until the selection owner answers.
 * That is only legal on the GTK main thread, so off-thread callers are handed
 * to [fallback] rather than corrupting the loop — as is every call when the
 * native helper is unavailable.
 */
internal class TaoLinuxClipboardManager(
    private val fallback: ClipboardManager,
) : ClipboardManager {
    private val usesNative: Boolean
        get() =
            NativeTaoLinuxClipboardBridge.isAvailable &&
                Thread.currentThread() === TaoMainDispatcher.taoMainThread

    override fun getText(): AnnotatedString? {
        if (!usesNative) return fallback.getText()
        val text = NativeTaoLinuxClipboardBridge.nativeWaitForTextUtf8() ?: return null
        return AnnotatedString(text.toString(Charsets.UTF_8))
    }

    override fun setText(annotatedString: AnnotatedString) {
        if (!usesNative) {
            fallback.setText(annotatedString)
            return
        }
        NativeTaoLinuxClipboardBridge.nativeSetTextUtf8(annotatedString.text.toByteArray())
    }

    override fun hasText(): Boolean =
        if (usesNative) NativeTaoLinuxClipboardBridge.nativeHasText() else fallback.hasText()

    override fun getClip(): ClipEntry? {
        if (!usesNative) return fallback.getClip()
        val text = NativeTaoLinuxClipboardBridge.nativeWaitForTextUtf8() ?: return null
        return ClipEntry(StringSelection(text.toString(Charsets.UTF_8)))
    }

    override fun setClip(clipEntry: ClipEntry?) {
        if (!usesNative) {
            fallback.setClip(clipEntry)
            return
        }
        if (clipEntry == null) {
            NativeTaoLinuxClipboardBridge.nativeClear()
            return
        }
        val text = (clipEntry.nativeClipEntry as? Transferable)?.plainTextOrNull() ?: return
        NativeTaoLinuxClipboardBridge.nativeSetTextUtf8(text.toByteArray())
    }

    override val nativeClipboard: Any
        get() = fallback.nativeClipboard
}

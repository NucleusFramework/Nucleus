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
 * GTK has no non-blocking way to serve a synchronous read: every native call
 * used here spins a nested GTK main loop until the selection owner answers.
 * That is only legal on the GTK main thread, so off-thread callers are handed
 * to [fallback] rather than corrupting the loop — as is every call when the
 * native helper is unavailable.
 *
 * [getClip] probes text, then image, then files, because this API has no way
 * to ask what the selection offers before reading it. Each probe is a round
 * trip, so the cost is only paid when the earlier formats are absent.
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
        NativeTaoLinuxClipboardBridge.nativeWaitForTextUtf8()?.let {
            return ClipEntry(StringSelection(it.toString(Charsets.UTF_8)))
        }
        NativeTaoLinuxClipboardBridge.nativeWaitForImagePng()?.let { png ->
            return ClipEntry(GtkClipboardTransferable(text = null, fetchPng = { png }, fetchUriList = null))
        }
        NativeTaoLinuxClipboardBridge.nativeWaitForUriListUtf8()?.let { uris ->
            val uriList = uris.toString(Charsets.UTF_8)
            return ClipEntry(GtkClipboardTransferable(text = null, fetchPng = null, fetchUriList = { uriList }))
        }
        return null
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
        val payload = (clipEntry.nativeClipEntry as? Transferable)?.toGtkPayload()
        if (payload == null) fallback.setClip(clipEntry) else payload.publish()
    }

    override val nativeClipboard: Any
        get() = fallback.nativeClipboard
}

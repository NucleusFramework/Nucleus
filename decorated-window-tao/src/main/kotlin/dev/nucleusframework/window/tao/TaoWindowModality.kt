package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge

/**
 * Native modality of the windows a [DecoratedDialog] is opened over (#832).
 *
 * Windows only: the owner is disabled with `EnableWindow`, the contract
 * `DialogBox` and AWT's modal `JDialog` follow, so the system itself refuses
 * input to it, beeps and flashes the dialog on a click there, and routes its
 * activation (taskbar, Alt+Tab) to the dialog. Counted per owner, so the
 * owner comes back only once its last modal dialog is gone. Every call runs
 * on the event-loop thread (composition apply).
 *
 * The owner must be unblocked *before* its dialog is hidden or destroyed:
 * Windows activates the owner of a disappearing active window only if that
 * owner is enabled, and the keyboard focus is lost otherwise.
 *
 * macOS and Linux rely on the in-scene input blocker driven by
 * [dev.nucleusframework.window.LocalModalDialogCount] instead.
 */
internal object TaoWindowModality {
    private val blockCounts = HashMap<TaoWindow, Int>()

    fun block(owner: TaoWindow) {
        val count = blockCounts.getOrDefault(owner, 0)
        blockCounts[owner] = count + 1
        if (count == 0) setEnabled(owner, false)
    }

    fun unblock(owner: TaoWindow) {
        val count = blockCounts[owner] ?: return
        if (count > 1) {
            blockCounts[owner] = count - 1
        } else {
            blockCounts -= owner
            setEnabled(owner, true)
        }
    }

    private fun setEnabled(
        window: TaoWindow,
        enabled: Boolean,
    ) {
        if (Platform.Current != Platform.Windows || !NativeTaoWindowsDecoBridge.isLoaded) return
        val hwnd = window.nativeHandle
        if (hwnd != 0L) NativeTaoWindowsDecoBridge.nativeSetWindowEnabled(hwnd, enabled)
    }
}

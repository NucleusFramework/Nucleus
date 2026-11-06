package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import java.util.concurrent.atomic.AtomicInteger

/**
 * #832 — a `DecoratedDialog` is modal over the window it was opened from:
 * while it is up, real clicks and keys aimed at the parent reach neither the
 * parent's content nor its focus, and the dialog keeps the keyboard. Once the
 * dialog goes, the parent gets the focus back on its own — no extra click —
 * and takes input again. Real OS input through the AWT Robot.
 *
 * Windows-only: there the parent is disabled natively (`EnableWindow`), the
 * platform's own modal contract. macOS and Linux keep the in-scene blocker.
 */
internal object ModalDialogHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(dialogIsModalOverItsParent())

    @Suppress("LongMethod")
    private fun dialogIsModalOverItsParent(): TaoWindowTestCase {
        val presses = AtomicInteger()
        val keys = AtomicInteger()
        val scene = SceneSize()
        val dialogScene = SceneSize()
        val dialogVisible = mutableStateOf(true)
        return TaoWindowTestCase(
            name = "#832 DecoratedDialog is modal over its parent window",
            skip = ::skipReason,
            paintDefaultBackground = false,
            windowState =
                WindowState(
                    position = WindowPosition.Aligned(Alignment.Center),
                    size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
                ),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            dialogParentedToWindow = true,
            dialogVisible = dialogVisible,
            dialogSize = DpSize(DIALOG_W_DP.dp, DIALOG_H_DP.dp),
            dialogContent = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF3C8D5A))
                        .onGloballyPositioned { dialogScene.value = it.size },
                )
            },
            content = {
                val focus = remember { FocusRequester() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF202A36))
                        .onGloballyPositioned { scene.value = it.size }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) presses.incrementAndGet()
                                }
                            }
                        }.focusRequester(focus)
                        .focusable()
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown) keys.incrementAndGet()
                            false
                        },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            driver = {
                awaitUntil("scenes measured") { scene.value.width > 0 && dialogScene.value.width > 0 }
                val dialog = requireNotNull(dialogWindow) { "dialog window was never published" }
                settle()
                // A real click hands the suite the foreground, which a process
                // started from Gradle does not get on its own.
                val dialogSize = dialogScene.value
                RobotPointerDriver(dialog) { dialogScene.value }
                    .click(Offset(dialogSize.width / 2f, dialogSize.height / 2f))
                awaitUntil("dialog took the focus") { dialog.isFocused }
                settle()
                val driver = RobotPointerDriver(window) { scene.value }
                val parentHwnd = window.nativeHandle
                // Top-left corner of the parent: well clear of the centred dialog.
                val outsideDialog = Offset(CLICK_INSET_PX, CLICK_INSET_PX)

                // ── 1. while the dialog is up, the parent takes nothing ──
                driver.click(outsideDialog)
                // Windows answers the click by flashing the dialog: its caption
                // blinks inactive / active while it stays the active window. The
                // dialog must keep the focus through every blink.
                val watchUntil = System.currentTimeMillis() + FLASH_WATCH_MILLIS
                while (System.currentTimeMillis() < watchUntil) {
                    check(dialog.isFocused) { "the modal dialog dropped the focus while it flashed (#832)" }
                    settle(POLL_MILLIS)
                }
                driver.type('a')
                settle()
                val state =
                    "presses=${presses.get()} keys=${keys.get()} " +
                        "parentFocused=${window.isFocused} dialogFocused=${dialog.isFocused}"
                check(presses.get() == 0) { "a click reached the parent behind a modal dialog (#832): $state" }
                check(keys.get() == 0) { "a key reached the parent behind a modal dialog (#832): $state" }
                check(!window.isFocused) { "the parent took the focus behind a modal dialog (#832): $state" }
                check(dialog.isFocused) { "the modal dialog lost the focus to its parent (#832): $state" }
                check(!NativeTaoWindowsDecoBridge.nativeIsWindowEnabled(parentHwnd)) {
                    "the parent is still enabled behind a modal dialog (#832)"
                }

                // ── 2. closing the dialog hands the focus back to the parent ──
                dialogVisible.value = false
                awaitUntil("parent focused again after the dialog closed") { window.isFocused }
                check(NativeTaoWindowsDecoBridge.nativeIsWindowEnabled(parentHwnd)) {
                    "the parent stayed disabled after its modal dialog closed (#832)"
                }
                settle()

                // ── 3. and the parent takes input again ──
                driver.click(outsideDialog)
                driver.type('a')
                awaitUntil("click reached the parent", detail = { "presses=${presses.get()}" }) {
                    presses.get() == 1
                }
                awaitUntil("key reached the parent", detail = { "keys=${keys.get()}" }) { keys.get() == 1 }
            },
        )
    }

    private fun skipReason(): String? =
        if (Platform.Current != Platform.Windows) "native modality is Windows-only" else robotDriverSkipReason()

    private class SceneSize {
        @Volatile
        var value: IntSize = IntSize.Zero
    }

    private const val PARENT_W_DP = 900
    private const val PARENT_H_DP = 600
    private const val DIALOG_W_DP = 320
    private const val DIALOG_H_DP = 200
    private const val CLICK_INSET_PX = 60f
    private const val FLASH_WATCH_MILLIS = 1_500L
    private const val POLL_MILLIS = 10L
}

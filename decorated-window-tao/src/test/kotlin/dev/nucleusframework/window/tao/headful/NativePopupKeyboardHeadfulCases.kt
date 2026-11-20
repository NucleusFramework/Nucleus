package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.concurrent.atomic.AtomicInteger

/**
 * A focusable `Popup` on native popup layers (`nativePopupLayers = true`) owns
 * the keyboard: with the popup's field focused, typed keys reach it and not
 * the field of the window behind it. The owner window keeps the OS keyboard
 * focus (the popup surface is not activated), so the host has to forward its
 * keys to the popup's scene first. Real keys through the AWT Robot.
 */
internal object NativePopupKeyboardHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(focusablePopupReceivesTheKeyboard())

    @Suppress("LongMethod")
    private fun focusablePopupReceivesTheKeyboard(): TaoWindowTestCase {
        val ownerKeys = AtomicInteger()
        val popupKeys = AtomicInteger()
        val scene = SceneSize()
        val popupShown = mutableStateOf(false)
        val popupFocused = mutableStateOf(false)
        val ownerFocused = mutableStateOf(false)
        return TaoWindowTestCase(
            name = "native popup layer: a focused popup receives the keyboard",
            skip = ::robotDriverSkipReason,
            nativePopupLayers = true,
            paintDefaultBackground = false,
            content = {
                val ownerFocus = remember { FocusRequester() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF203040))
                        .onGloballyPositioned { scene.value = it.size }
                        .focusRequester(ownerFocus)
                        .onFocusChanged { ownerFocused.value = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown) ownerKeys.incrementAndGet()
                            false
                        },
                ) {
                    if (popupShown.value) {
                        Popup(
                            alignment = Alignment.Center,
                            properties = PopupProperties(focusable = true),
                            onDismissRequest = { popupShown.value = false },
                        ) {
                            val popupFocus = remember { FocusRequester() }
                            Box(
                                Modifier
                                    .size(POPUP_DP.dp)
                                    .background(Color(0xFF3C8D5A))
                                    .focusRequester(popupFocus)
                                    .onFocusChanged { popupFocused.value = it.isFocused }
                                    .focusable()
                                    .onPreviewKeyEvent {
                                        // Consumes like a text field does.
                                        if (it.type == KeyEventType.KeyDown) popupKeys.incrementAndGet()
                                        true
                                    },
                            )
                            LaunchedEffect(Unit) { popupFocus.requestFocus() }
                        }
                    }
                }
                LaunchedEffect(Unit) { ownerFocus.requestFocus() }
            },
        ) {
            awaitUntil("scene measured") { scene.value.width > 0 }
            settle()
            val driver = RobotPointerDriver(window) { scene.value }
            // Raised first (a window can open behind the foreground app), then a
            // real click hands the suite the foreground.
            window.focus()
            settle()
            driver.click(Offset(CLICK_INSET_PX, CLICK_INSET_PX))
            awaitUntil("owner window focused") { window.isFocused && ownerFocused.value }
            driver.type('a')
            awaitUntil("the owner field takes keys with no popup open") { ownerKeys.get() == 1 }

            popupShown.value = true
            try {
                awaitUntil("the popup field took the Compose focus") { popupFocused.value }
                settle()
                driver.type('b')
                settle()
                val state = "popupKeys=${popupKeys.get()} ownerKeys=${ownerKeys.get()}"
                awaitUntil("the key reached the focused popup", detail = { state }) { popupKeys.get() == 1 }
                check(ownerKeys.get() == 1) { "the key also reached the window behind the popup: $state" }
            } finally {
                popupShown.value = false
            }
        }
    }

    private class SceneSize {
        @Volatile
        var value: IntSize = IntSize.Zero
    }

    private const val POPUP_DP = 160
    private const val CLICK_INSET_PX = 40f
}

package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.isRepeat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A key that types a character reaches Compose as a key-down, then the
 * character, then the key-up — the order AWT delivers `KEY_PRESSED`,
 * `KEY_TYPED` and `KEY_RELEASED` in, and the one Compose Desktop code is
 * written against.
 *
 * The case is the handler that depends on it: "type anywhere", where a
 * key-down seen while something else has the focus moves the focus to a text
 * field so that the character lands in it. macOS queued the character ahead of
 * its key-down, so the first one went to the old focus owner and was lost
 * (`quit` arrived as `uit`); Linux delivered a printable key as its character
 * alone, with no key-down, so the focus never moved at all. Real keys through
 * the AWT Robot: the order is decided in the native layer, below anything a
 * dispatched event goes through.
 */
internal object KeyTypedOrderHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(keyDownPrecedesTheCharacterItTypes())

    private fun keyDownPrecedesTheCharacterItTypes(): TaoWindowTestCase {
        val seen = CopyOnWriteArrayList<String>()
        val scene = SceneSize()
        val otherFocused = mutableStateOf(false)
        val text = mutableStateOf("")
        return TaoWindowTestCase(
            name = "a typed key is a key-down, then its character: focusing a field on key-down keeps it",
            skip = ::robotDriverSkipReason,
            paintDefaultBackground = false,
            content = {
                val field = remember { FocusRequester() }
                val other = remember { FocusRequester() }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF203040))
                        .onGloballyPositioned { scene.value = it.size }
                        .onPreviewKeyEvent {
                            seen +=
                                when (it.type) {
                                    KeyEventType.KeyDown -> if (it.isRepeat) "repeat" else "down"
                                    KeyEventType.KeyUp -> "up"
                                    else -> "typed ${it.utf16CodePoint.toChar()}"
                                }
                            if (it.type == KeyEventType.KeyDown) field.requestFocus()
                            false
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusRequester(other)
                            .onFocusChanged { otherFocused.value = it.isFocused }
                            .focusable(),
                    )
                    BasicTextField(
                        value = text.value,
                        onValueChange = { text.value = it },
                        modifier = Modifier.fillMaxWidth().background(Color.White).focusRequester(field),
                    )
                }
                LaunchedEffect(Unit) { other.requestFocus() }
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
            awaitUntil("window focused, with the focus outside the field") { window.isFocused && otherFocused.value }

            TYPED.forEach { driver.type(it) }

            val expected = TYPED.flatMap { listOf("down", "typed $it", "up") }
            val state = { "field=\"${text.value}\" events=$seen" }
            awaitUntil("every key arrived as down, typed, up", detail = state) { seen.size >= expected.size }
            settle()
            check(seen == expected) { "expected $expected: ${state()}" }
            check(text.value == TYPED) { "the field did not receive \"$TYPED\": ${state()}" }
        }
    }

    private class SceneSize {
        @Volatile
        var value: IntSize = IntSize.Zero
    }

    private const val TYPED = "quit"
    private const val CLICK_INSET_PX = 40f
}

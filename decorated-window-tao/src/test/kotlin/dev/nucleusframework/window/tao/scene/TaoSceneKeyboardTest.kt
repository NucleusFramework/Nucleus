@file:OptIn(InternalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TaoModifierMask
import dev.nucleusframework.window.tao.event.dispatchNativeKeyEvent
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage-1 keyboard tests: the full production key pipeline
 * ([dispatchNativeKeyEvent] → per-platform vk translation → Compose KeyDown →
 * synthetic KEY_TYPED insertion) drives a real BasicTextField in the
 * offscreen scene.
 */
class TaoSceneKeyboardTest {
    @Test
    fun `typing inserts text into a focused BasicTextField`() =
        runTaoSceneTest {
            var value by mutableStateOf("")
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.size(200.dp, 30.dp).testTag("field"),
                    )
                }
            }
            click(100f, 15f) // focus the field
            typeText("hello")
            assertEquals("hello", value)
        }

    @Test
    fun `backspace removes the last character through the named-key table`() =
        runTaoSceneTest {
            var value by mutableStateOf("")
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.size(200.dp, 30.dp),
                    )
                }
            }
            click(100f, 15f)
            typeText("abc")
            pressKey(TaoSceneTestScope.NamedKey.Backspace)
            assertEquals("ab", value)
        }

    @Test
    fun `backspace then typed accent replaces the last character`() =
        runTaoSceneTest {
            var value by mutableStateOf("")
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.size(200.dp, 30.dp),
                    )
                }
            }
            click(100f, 15f)
            typeText("e")
            pressKey(TaoSceneTestScope.NamedKey.Backspace)
            // Same sequence the macOS PressAndHold path now emits when the
            // user picks é: Backspace the already-committed e, then KEY_TYPED é.
            keyDown(vkCode = 'E'.code, codePoint = 'é'.code)
            assertEquals("é", value)
        }

    @Test
    fun `typed text lands in the semantics tree`() =
        runTaoSceneTest {
            var value by mutableStateOf("")
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.size(200.dp, 30.dp).testTag("field"),
                    )
                }
            }
            click(100f, 15f)
            typeText("tao")
            val node = nodeWithTag("field")
            assertEquals("tao", node.config.getOrNull(SemanticsProperties.EditableText)?.text)
        }

    @Test
    fun `control combos are not inserted as text`() =
        runTaoSceneTest {
            var value by mutableStateOf("")
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.size(200.dp, 30.dp),
                    )
                }
            }
            click(100f, 15f)
            // Ctrl+A: control char code point, must not become visible text.
            pressKey(vkCode = 'A'.code, codePoint = 0x01, modifiers = TaoModifierMask.CONTROL)
            assertEquals("", value)
        }

    @Test
    fun `mac function-key code points are filtered from text insertion`() =
        runTaoSceneTest {
            var value by mutableStateOf("")
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.size(200.dp, 30.dp),
                    )
                }
            }
            click(100f, 15f)
            typeText("x")
            // Arrow keys report PUA code points (U+F700–F8FF) on macOS — no tofu.
            pressKey(TaoSceneTestScope.NamedKey.ArrowLeft)
            typeText("y")
            assertEquals("yx", value)
        }

    @Test
    fun `preview key handler consumes the event before the scene`() =
        runTaoSceneTest {
            var fieldValue by mutableStateOf("")
            var previewed = 0
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        modifier = Modifier.size(200.dp, 30.dp),
                    )
                }
            }
            click(100f, 15f)
            val preview: (KeyEvent) -> Boolean = {
                previewed++
                true // consume everything
            }
            scene.dispatchNativeKeyEvent(
                TaoNativeWireFormat.KEY_DOWN,
                'X'.code,
                'x'.code,
                0,
                onPreviewKeyEvent = preview,
            )
            frame()
            assertTrue(previewed > 0, "preview handler must run")
            assertEquals("", fieldValue, "consumed event must not reach the field")
        }

    @Test
    fun `fallback key handler fires only when the scene does not consume`() =
        runTaoSceneTest {
            var fallback = 0
            setContent {
                Box(Modifier.fillMaxSize()) {
                    // no focusable content — scene won't consume keys
                    Box(Modifier.size(10.dp))
                }
            }
            scene.dispatchNativeKeyEvent(
                TaoNativeWireFormat.KEY_DOWN,
                'K'.code,
                'k'.code,
                0,
                onKeyEvent = {
                    fallback++
                    true
                },
            )
            frame()
            assertEquals(1, fallback)
        }
}

package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.PopupNativeBridge
import java.io.File
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The macOS scroll wire is written by hand in three places that the compiler
 * cannot check against each other: the Rust loop (`events.rs`
 * `SCROLL_GESTURE_*`), the popup panel (`popup_panel.m`, its
 * `NucleusScrollGesture*` enum and the JNI descriptors it resolves with
 * `GetMethodID`) and Kotlin ([TaoScrollGesturePhase], [PopupNativeBridge.EventCallback]).
 * A drift is silent at run time — a mis-numbered phase closes a pan mid-tail,
 * a wrong descriptor leaves the popup callback uninstalled — so compare them
 * here, where it is loud.
 */
class TaoScrollWireDriftTest {
    @Test
    fun `popup_panel m GetMethodID descriptors match the Kotlin callback`() {
        val declared =
            GET_METHOD_ID
                .findAll(POPUP_PANEL.readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
                .filterKeys { it != "onOutsideClick" } // lives on a different listener class
        assertTrue(declared.isNotEmpty(), "no GetMethodID(...) found in ${POPUP_PANEL.path}")

        val callback = PopupNativeBridge.EventCallback::class.java
        declared.forEach { (name, descriptor) ->
            val method =
                callback.methods.singleOrNull { it.name == name }
                    ?: error("popup_panel.m looks up '$name' but EventCallback has no single method of that name")
            assertEquals(descriptor, method.jniDescriptor(), "JNI descriptor of EventCallback.$name")
        }
    }

    @Test
    fun `Rust SCROLL_GESTURE codes match TaoScrollGesturePhase`() {
        val rust =
            RUST_CODE
                .findAll(EVENTS_RS.readText())
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(kotlinWire(), rust, "events.rs SCROLL_GESTURE_* vs TaoScrollGesturePhase.wire")
    }

    @Test
    fun `popup_panel m NucleusScrollGesture codes match TaoScrollGesturePhase`() {
        val objc =
            OBJC_CODE
                .findAll(POPUP_PANEL.readText())
                .associate { it.groupValues[1].toScreamingSnake() to it.groupValues[2].toInt() }
        assertEquals(TaoScrollGesturePhase.NONE_WIRE, objc["NONE"], "NucleusScrollGestureNone")
        assertEquals(kotlinWire(), objc - "NONE", "popup_panel.m NucleusScrollGesture* vs TaoScrollGesturePhase.wire")
    }

    private fun kotlinWire(): Map<String, Int> = TaoScrollGesturePhase.entries.associate { it.name to it.wire }

    /** `MomentumBegan` → `MOMENTUM_BEGAN`, `MayBegin` → `MAY_BEGIN`. */
    private fun String.toScreamingSnake(): String = replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").uppercase()

    private fun Method.jniDescriptor(): String =
        parameterTypes.joinToString(prefix = "(", postfix = ")", separator = "") { it.descriptor() } +
            returnType.descriptor()

    private fun Class<*>.descriptor(): String =
        when (this) {
            Void.TYPE -> "V"
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Integer.TYPE -> "I"
            java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"
            java.lang.Double.TYPE -> "D"
            else -> "L${name.replace('.', '/')};"
        }

    private companion object {
        // Gradle runs tests from the module directory.
        val POPUP_PANEL =
            File("src/main/native/macos/popup_panel.m").also {
                require(it.isFile) { "missing ${it.absolutePath}" }
            }
        val EVENTS_RS =
            File(
                "src/main/native/src/events.rs",
            ).also { require(it.isFile) { "missing ${it.absolutePath}" } }

        val GET_METHOD_ID = Regex("""GetMethodID\(env,\s*\w+,\s*"(\w+)",\s*"([^"]+)"\)""")
        val RUST_CODE = Regex("""pub\(crate\) const SCROLL_GESTURE_(\w+): jint = (\d+);""")
        val OBJC_CODE = Regex("""NucleusScrollGesture(\w+)\s*=\s*(-?\d+)""")
    }
}

package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.event.AWT_PIXEL_TO_ROTATION
import dev.nucleusframework.window.tao.ffi.PopupNativeBridge
import java.io.File
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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
                .findAll(popupPanel().readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
                .filterKeys { it != "onOutsideClick" } // lives on a different listener class
        assertTrue(declared.isNotEmpty(), "no GetMethodID(...) found in popup_panel.m")

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
                .findAll(eventsRs().readText())
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(kotlinWire(), rust, "events.rs SCROLL_GESTURE_* vs TaoScrollGesturePhase.wire")
    }

    @Test
    fun `popup_panel m NucleusScrollGesture codes match TaoScrollGesturePhase`() {
        val objc =
            OBJC_CODE
                .findAll(popupPanel().readText())
                .associate { it.groupValues[1].toScreamingSnake() to it.groupValues[2].toInt() }
        assertEquals(TaoScrollGesturePhase.NONE_WIRE, objc["NONE"], "NucleusScrollGestureNone")
        assertEquals(kotlinWire(), objc - "NONE", "popup_panel.m NucleusScrollGesture* vs TaoScrollGesturePhase.wire")
    }

    @Test
    fun `native_view m kNv codes match TaoNativeViewHost`() {
        val objc =
            NV_CODE
                .findAll(nativeView().readText())
                .associate { it.groupValues[1].toScreamingSnake() to it.groupValues[2].toInt() }
        val kotlin =
            mapOf(
                "SCROLL_WHEEL" to TaoNativeViewHost.SCROLL_WHEEL,
                "PAN_START" to TaoNativeViewHost.PAN_START,
                "PAN_MOVE" to TaoNativeViewHost.PAN_MOVE,
                "PAN_END" to TaoNativeViewHost.PAN_END,
            )
        assertEquals(kotlin, objc, "native_view.m kNv* vs TaoNativeViewHost")
    }

    @Test
    fun `the ten units per wheel factor agrees everywhere it is written down`() {
        val expected = AWT_PIXEL_TO_ROTATION.toDouble()
        assertEquals(expected, firstNumber(RUST_LINE_TO_POINTS, eventsRs()), "events.rs AWT_LINE_TO_POINTS")
        assertEquals(expected, firstNumber(OBJC_PIXEL_TO_ROTATION, nativeView()), "native_view.m kAwtPixelToRotation")
        assertEquals(
            expected,
            firstNumber(DEMO_PAN_FACTOR, demoScrollScreen()),
            "ScrollTestScreen PAN_DP_PER_WHEEL_UNIT",
        )
    }

    private fun firstNumber(
        regex: Regex,
        file: File,
    ): Double =
        regex
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?.toDouble()
            ?: fail("no match for $regex in ${file.path}")

    private fun popupPanel() = sourceFile("src/main/native/macos/popup_panel.m")

    private fun nativeView() = sourceFile("src/main/native/macos/native_view.m")

    private fun demoScrollScreen() =
        sourceFile("../examples/nucleus-demo/src/main/kotlin/com/example/demo/ScrollTestScreen.kt")

    private fun eventsRs() = sourceFile("src/main/native/src/events.rs")

    /**
     * Gradle runs tests from the module directory; an IDE run configuration
     * may use the repository root. Either way the failure names the file.
     */
    private fun sourceFile(relative: String): File {
        // Module directory first (Gradle), then the repository root (IDE).
        val candidates = listOf(File(relative), File("decorated-window-tao", relative))
        return candidates.firstOrNull { it.isFile }
            ?: fail("cannot find $relative from ${File("").absolutePath} (tried ${candidates.map { it.path }})")
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
        val GET_METHOD_ID = Regex("""GetMethodID\(env,\s*\w+,\s*"(\w+)",\s*"([^"]+)"\)""")
        val RUST_CODE = Regex("""pub\(crate\) const SCROLL_GESTURE_(\w+): jint = (\d+);""")
        val OBJC_CODE = Regex("""NucleusScrollGesture(\w+)\s*=\s*(-?\d+)""")
        val NV_CODE = Regex("""kNv(\w+)\s*=\s*(\d+)""")
        val RUST_LINE_TO_POINTS = Regex("""const AWT_LINE_TO_POINTS: f64 = ([0-9.]+);""")
        val OBJC_PIXEL_TO_ROTATION = Regex("""kAwtPixelToRotation = ([0-9.]+)f;""")
        val DEMO_PAN_FACTOR = Regex("""PAN_DP_PER_WHEEL_UNIT = ([0-9.]+)f""")
    }
}

package dev.nucleusframework.window.tao.popup

import dev.nucleusframework.window.tao.ffi.PopupNativeBridge
import java.io.File
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `popup_panel.m` resolves the [PopupNativeBridge.EventCallback] methods by
 * hand-written JNI descriptors (`GetMethodID(..., "onScroll", "(FFFFZI)V")`).
 * A descriptor that drifts from the Kotlin signature fails silently at run
 * time: the lookup throws, the callback cache never initialises and the popup
 * simply stops receiving input. Compare the two here, where it is loud.
 */
class PopupPanelJniSignatureDriftTest {
    @Test
    fun `popup_panel m GetMethodID descriptors match the Kotlin callback`() {
        val source = File("src/main/native/macos/popup_panel.m")
        assertTrue(source.isFile, "expected ${source.absolutePath} (run from the module directory)")
        val declared =
            GET_METHOD_ID
                .findAll(source.readText())
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
    }
}

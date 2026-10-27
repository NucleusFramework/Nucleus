package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.event.TaoDirectManipulationEvent
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The #706 DirectManipulation wire is written by hand on both sides of JNI:
 * the Rust viewport (`direct_manipulation.rs` event kinds, `events.rs`
 * `dispatch_direct_manipulation` descriptor), the GraalVM metadata that keeps
 * the callback reachable, and Kotlin ([TaoDirectManipulationEvent],
 * [NativeTaoBridge.EventCallback.onDirectManipulation]). A drift is silent at
 * run time — a mis-numbered kind reads a status as a transform, a wrong
 * descriptor leaves the callback unresolved and the touchpad dead — so
 * compare them here.
 */
class TaoDirectManipulationWireDriftTest {
    @Test
    fun `Rust event kinds match TaoDirectManipulationEvent`() {
        val rust =
            RUST_KIND
                .findAll(sourceFile("src/main/native/src/platform/windows/direct_manipulation.rs").readText())
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(
            mapOf("STATUS" to TaoDirectManipulationEvent.STATUS, "CONTENT" to TaoDirectManipulationEvent.CONTENT),
            rust,
            "direct_manipulation.rs EVENT_* vs TaoDirectManipulationEvent",
        )
    }

    @Test
    fun `the Rust dispatch descriptor matches the Kotlin callback`() {
        val match =
            RUST_DISPATCH.find(sourceFile("src/main/native/src/events.rs").readText())
                ?: fail("no onDirectManipulation call_method in events.rs")
        val method =
            NativeTaoBridge.EventCallback::class.java.methods.singleOrNull { it.name == "onDirectManipulation" }
                ?: fail("EventCallback has no single onDirectManipulation")
        val descriptor =
            method.parameterTypes.joinToString(prefix = "(", postfix = ")V", separator = "") {
                when (it) {
                    java.lang.Integer.TYPE -> "I"
                    java.lang.Long.TYPE -> "J"
                    java.lang.Float.TYPE -> "F"
                    else -> fail("unexpected parameter type $it")
                }
            }
        assertEquals(descriptor, match.groupValues[1], "events.rs descriptor of onDirectManipulation")
    }

    @Test
    fun `GraalVM metadata keeps the callback reachable on both callback types`() {
        val metadata =
            sourceFile(
                "src/main/resources/META-INF/native-image/dev.nucleusframework/" +
                    "nucleus.decorated-window-tao/reachability-metadata.json",
            ).readText()
        val entries = METADATA_ENTRY.findAll(metadata).map { it.groupValues[1].filterNot(Char::isWhitespace) }.toList()
        assertEquals(2, entries.size, "onDirectManipulation must be listed for EventCallback and EventDispatcher")
        entries.forEach {
            assertEquals("\"long\",\"int\",\"int\",\"int\",\"float\",\"float\",\"float\",\"float\",\"float\"", it)
        }
        assertTrue(metadata.contains("TaoApplication\$EventDispatcher"), "dispatcher type listed")
    }

    /** Gradle runs tests from the module directory; an IDE may use the repository root. */
    private fun sourceFile(relative: String): File {
        val candidates = listOf(File(relative), File("decorated-window-tao", relative))
        return candidates.firstOrNull { it.isFile }
            ?: fail("cannot find $relative from ${File("").absolutePath} (tried ${candidates.map { it.path }})")
    }

    private companion object {
        val RUST_KIND = Regex("""const EVENT_(STATUS|CONTENT): jint = (\d+);""")
        val RUST_DISPATCH = Regex(""""onDirectManipulation",\s*"([^"]+)"""")
        val METADATA_ENTRY =
            Regex(""""name":\s*"onDirectManipulation",\s*"parameterTypes":\s*\[([^\]]*)]""")
    }
}

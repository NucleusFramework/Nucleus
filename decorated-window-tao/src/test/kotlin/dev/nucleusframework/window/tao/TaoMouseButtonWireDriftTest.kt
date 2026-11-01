package dev.nucleusframework.window.tao

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Mouse-button codes are written by hand on both sides of the JNI boundary:
 * `events.rs` `MOUSE_BUTTON_*` and [TaoMouseButton]. A drift is silent — the
 * Rust "other" code once shared its number with [TaoMouseButton.BACK], so
 * every extra button reached Compose as Back — so compare them here.
 */
class TaoMouseButtonWireDriftTest {
    @Test
    fun `Rust MOUSE_BUTTON codes match TaoMouseButton`() {
        val rust =
            RUST_CODE
                .findAll(eventsRs().readText())
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val kotlin =
            TaoMouseButton::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE && it.name != "\$stable" }
                .associate { it.name to it.getInt(null) }
        assertEquals(kotlin, rust, "events.rs MOUSE_BUTTON_* vs TaoMouseButton")
    }

    private fun eventsRs(): File {
        val relative = "src/main/native/src/events.rs"
        // Module directory first (Gradle), then the repository root (IDE).
        val candidates = listOf(File(relative), File("decorated-window-tao", relative))
        return candidates.firstOrNull { it.isFile }
            ?: fail("cannot find $relative from ${File("").absolutePath} (tried ${candidates.map { it.path }})")
    }

    private companion object {
        val RUST_CODE = Regex("""pub\(crate\) const MOUSE_BUTTON_(\w+): jint = (\d+);""")
    }
}

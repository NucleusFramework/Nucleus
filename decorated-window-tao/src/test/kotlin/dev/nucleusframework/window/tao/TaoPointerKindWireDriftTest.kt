package dev.nucleusframework.window.tao

import androidx.compose.ui.input.pointer.PointerType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The pointer-kind wire (#827) is written by hand on both sides of the JNI
 * boundary: `events.rs` `EVENT_POINTER_KIND` / `POINTER_KIND_*` and
 * [TaoEventCode.POINTER_KIND] / [TaoPointerKind]. A drift is silent — a pen
 * would quietly be a mouse again — so compare them here.
 */
class TaoPointerKindWireDriftTest {
    @Test
    fun `Rust POINTER_KIND codes match TaoPointerKind`() {
        val rust =
            RUST_KIND
                .findAll(eventsRs().readText())
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val kotlin =
            mapOf(
                "MOUSE" to TaoPointerKind.MOUSE,
                "STYLUS" to TaoPointerKind.STYLUS,
                "ERASER" to TaoPointerKind.ERASER,
                "TOUCH" to TaoPointerKind.TOUCH,
            )
        assertEquals(kotlin, rust, "events.rs POINTER_KIND_* vs TaoPointerKind")
    }

    @Test
    fun `Rust EVENT_POINTER_KIND matches TaoEventCode`() {
        val match = RUST_EVENT.find(eventsRs().readText()) ?: fail("no EVENT_POINTER_KIND in events.rs")
        val rust = match.groupValues[1].toInt()
        assertEquals(TaoEventCode.POINTER_KIND, rust, "events.rs EVENT_POINTER_KIND vs TaoEventCode.POINTER_KIND")
    }

    @Test
    fun `every kind maps to its Compose pointer type`() {
        assertEquals(PointerType.Mouse, taoPointerType(TaoPointerKind.MOUSE))
        assertEquals(PointerType.Stylus, taoPointerType(TaoPointerKind.STYLUS))
        assertEquals(PointerType.Eraser, taoPointerType(TaoPointerKind.ERASER))
        assertEquals(PointerType.Touch, taoPointerType(TaoPointerKind.TOUCH))
        assertEquals(PointerType.Mouse, taoPointerType(UNKNOWN_KIND), "an unknown kind falls back to the mouse")
    }

    private fun eventsRs(): File {
        val relative = "src/main/native/src/events.rs"
        // Module directory first (Gradle), then the repository root (IDE).
        val candidates = listOf(File(relative), File("decorated-window-tao", relative))
        return candidates.firstOrNull { it.isFile }
            ?: fail("cannot find $relative from ${File("").absolutePath} (tried ${candidates.map { it.path }})")
    }

    private companion object {
        val RUST_KIND = Regex("""pub\(crate\) const POINTER_KIND_(\w+): jint = (\d+);""")
        val RUST_EVENT = Regex("""pub\(crate\) const EVENT_POINTER_KIND: jint = (\d+);""")
        const val UNKNOWN_KIND = 99
    }
}

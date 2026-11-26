package dev.nucleusframework.window.tao

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The decoration-suppression event (#816) is numbered by hand in `events.rs`
 * and in [TaoEventCode]; a drift would route the compositor's answer into
 * another handler without anything failing at run time.
 */
class TaoDecorationEventWireTest {
    @Test
    fun `Rust EVENT_DECORATIONS_SUPPRESSED matches TaoEventCode`() {
        val rust =
            RUST_CODE
                .find(eventsRs().readText())
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: fail("EVENT_DECORATIONS_SUPPRESSED not found in events.rs")
        assertEquals(TaoEventCode.DECORATIONS_SUPPRESSED, rust)
    }

    private fun eventsRs(): File {
        val relative = "src/main/native/src/events.rs"
        val candidates = listOf(File(relative), File("decorated-window-tao", relative))
        return candidates.firstOrNull { it.isFile }
            ?: fail("cannot find $relative from ${File("").absolutePath}")
    }

    private companion object {
        val RUST_CODE = Regex("""pub\(crate\) const EVENT_DECORATIONS_SUPPRESSED: jint = (\d+);""")
    }
}

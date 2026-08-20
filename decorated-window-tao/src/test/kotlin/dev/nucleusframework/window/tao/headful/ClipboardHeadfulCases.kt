@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import dev.nucleusframework.window.tao.clipboard.TaoLinuxClipboard
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxClipboardBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.concurrent.TimeUnit

/**
 * #582 — the Tao clipboard must be the *window's* clipboard (GTK / the current
 * GDK backend), not AWT's X11-only one. Both directions are covered:
 *
 *  1. what another process publishes is readable by the app — the actual bug,
 *     since on a Wayland session AWT sees an empty X11 selection;
 *  2. what the app publishes is visible to the rest of the desktop, and reads
 *     back byte-identical (emoji included: the JNI boundary carries UTF-8, not
 *     modified UTF-8).
 *
 * Both cases focus the window first and then poll. That is not test scaffolding
 * being lenient: on Wayland the compositor only sends selection offers to the
 * **focused** client, and only accepts `set_selection` with a serial from a
 * real input event. An unfocused window neither sees nor can take the
 * selection — the same reason `wl-paste` needs the `data-control` protocol.
 *
 * The AWT path is deliberately unreachable: the clipboard is built with a
 * fallback that fails the case if it is ever consulted.
 */
internal object ClipboardHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            gtkClipboardReadsForeignSelection(),
            gtkClipboardPublishesToTheDesktop(),
        )

    /** Text with a non-BMP character: modified UTF-8 would corrupt it. */
    private const val PROBE_SUFFIX = " ✅ 🍕 café"

    private fun gtkClipboardReadsForeignSelection(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#582 GTK clipboard reads a selection owned by another process",
            skip = { linuxWithNativeClipboard() ?: requireTool("wl-copy") },
        ) {
            focusAndSettle()

            val text = "nucleus-582-foreign$PROBE_SUFFIX"
            check(publishWithWlCopy(text)) { "wl-copy failed to take the selection" }

            val clipboard = TaoLinuxClipboard(fallback = FailingClipboard)
            awaitClipboard("GTK to report wl-copy's selection ($text)") {
                clipboard.getClipEntry()?.plainText() == text
            }
        }

    private fun gtkClipboardPublishesToTheDesktop(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#582 GTK clipboard publishes the app's selection to the desktop",
            skip = { linuxWithNativeClipboard() ?: requireTool("wl-paste") },
        ) {
            focusAndSettle()

            val clipboard = TaoLinuxClipboard(fallback = FailingClipboard)
            val text = "nucleus-582-app$PROBE_SUFFIX"
            val written = ClipEntry(StringSelection(text))
            clipboard.setClipEntry(written)

            val readBack = clipboard.getClipEntry()
            check(readBack != null) { "getClipEntry() returned null right after publishing" }
            check(readBack.plainText() == text) {
                "GTK read back '${readBack.plainText()}', expected '$text'"
            }
            // Same instance: the entry cache is what preserves Compose's
            // JVM-local AnnotatedString flavor across an in-app copy/paste.
            check(readBack === written) { "expected the published ClipEntry instance back" }

            awaitClipboard("wl-paste to see the app's selection ($text)") {
                readClipboardWith("wl-paste", "--no-newline") == text
            }
        }

    /**
     * Wayland hands selection offers to the focused client only, so a case that
     * skips this measures nothing but GTK's local cache.
     */
    private suspend fun TaoWindowTestScope.focusAndSettle() {
        awaitUntil("window mapped") { bounds() != null }
        window.focus()
        settle(FOCUS_SETTLE_MILLIS)
    }

    /** [predicate] can suspend (a clipboard read is asynchronous), hence not `awaitUntil`. */
    private suspend fun awaitClipboard(
        description: String,
        predicate: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + CLIPBOARD_TIMEOUT_MILLIS
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $description" }
            delay(POLL_MILLIS)
        }
    }

    private fun ClipEntry.plainText(): String? =
        (nativeClipEntry as? Transferable)?.let {
            if (it.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                it.getTransferData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        }

    /** Any consultation of the AWT fallback means the native path silently gave up. */
    private object FailingClipboard : Clipboard {
        override suspend fun getClipEntry(): ClipEntry? = error("fell back to the AWT clipboard")

        override suspend fun setClipEntry(clipEntry: ClipEntry?) = error("fell back to the AWT clipboard")
    }

    /**
     * `wl-copy` / `wl-paste` are driven off the Tao main thread on purpose: while
     * the app owns the selection, the GTK main loop is what answers the reader's
     * data request, so blocking that thread on the tool deadlocks both sides.
     */
    private suspend fun publishWithWlCopy(text: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val process =
                    ProcessBuilder("wl-copy", "--type", "text/plain;charset=utf-8")
                        .redirectErrorStream(true)
                        .start()
                process.outputStream.use { it.write(text.toByteArray()) }
                process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
        }

    /** Selection contents according to [command], or null when it is unusable. See [publishWithWlCopy]. */
    private suspend fun readClipboardWith(vararg command: String): String? =
        withContext(Dispatchers.IO) { runProcess(*command) }

    private fun runProcess(vararg command: String): String? =
        runCatching {
            val process = ProcessBuilder(*command).start()
            val output = process.inputStream.use { it.readBytes() }
            if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                null
            } else {
                output.toString(Charsets.UTF_8)
            }
        }.getOrNull()

    /** Runs at case-selection time, outside any coroutine, so it stays blocking. */
    private fun requireTool(name: String): String? =
        if (runProcess("which", name).isNullOrBlank()) "$name not installed" else null

    private fun linuxWithNativeClipboard(): String? {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win") || os.contains("mac") || os.contains("darwin")) {
            return "Linux only — GTK clipboard"
        }
        return if (NativeTaoLinuxClipboardBridge.isAvailable) null else "GTK clipboard unavailable"
    }

    private const val TOOL_TIMEOUT_SECONDS = 5L
    private const val FOCUS_SETTLE_MILLIS = 800L
    private const val CLIPBOARD_TIMEOUT_MILLIS = 5_000L
    private const val POLL_MILLIS = 100L
}

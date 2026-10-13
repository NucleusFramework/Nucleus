@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.headful

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalWindowInfo
import dev.nucleusframework.window.tao.clipboard.TaoLinuxClipboard
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxClipboardBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * #582 — the Tao clipboard must be the *window's* clipboard (GTK / the current
 * GDK backend), not AWT's X11-only one, and must carry everything AWT carried:
 * text, images and file lists. Each format is exercised in both directions
 * against real applications (`wl-copy` / `wl-paste`) rather than against
 * ourselves.
 *
 * Two Wayland rules shape every case here, and getting them wrong makes a
 * clipboard test measure nothing:
 *
 *  - a client is handed the selection when it **gains keyboard focus**, so the
 *    external tool has to own the selection *before* the window is focused —
 *    which is also the user flow the issue describes: copy elsewhere, come
 *    back, paste;
 *  - `wl-copy` returns as soon as it has forked, so its ownership is confirmed
 *    through `wl-paste` (which goes through `data-control` and is therefore not
 *    focus-gated) instead of assumed. Skipping that confirmation is what made
 *    these cases flaky: the window could take focus while the *previous*
 *    selection was still current, and nothing would refresh it afterwards.
 *
 * The AWT path is deliberately unreachable: the clipboard is built with a
 * fallback that fails the case if it is ever consulted, so anything that
 * passes here passed through GTK.
 */
internal object ClipboardHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            gtkClipboardReadsForeignSelection(),
            gtkClipboardPublishesToTheDesktop(),
            gtkClipboardReadsForeignImage(),
            gtkClipboardPublishesAnImage(),
            gtkClipboardRoundTripsAFileList(),
        )

    /** Text with a non-BMP character: modified UTF-8 would corrupt it. */
    private const val PROBE_SUFFIX = " ✅ 🍕 café"

    // ── Text ────────────────────────────────────────────────────────────

    private fun gtkClipboardReadsForeignSelection(): TaoWindowTestCase =
        clipboardCase(
            name = "#582 GTK clipboard reads a selection owned by another process",
            skip = { clipboardSkipReason("wl-copy") },
        ) { focusWindow ->
            val text = "nucleus-582-foreign$PROBE_SUFFIX"
            publishExternally(text.toByteArray(), "text/plain;charset=utf-8")
            focusWindow()

            val clipboard = TaoLinuxClipboard(fallback = FailingClipboard)
            awaitClipboard("GTK to report wl-copy's selection ($text)") {
                clipboard.getClipEntry()?.read(DataFlavor.stringFlavor) == text
            }
        }

    private fun gtkClipboardPublishesToTheDesktop(): TaoWindowTestCase =
        clipboardCase(
            name = "#582 GTK clipboard publishes the app's selection to the desktop",
            skip = { clipboardSkipReason("wl-paste") },
        ) { focusWindow ->
            focusWindow()

            val clipboard = TaoLinuxClipboard(fallback = FailingClipboard)
            val text = "nucleus-582-app$PROBE_SUFFIX"
            val written = ClipEntry(StringSelection(text))
            clipboard.setClipEntry(written)

            val readBack = clipboard.getClipEntry()
            check(readBack != null) { "getClipEntry() returned null right after publishing" }
            check(readBack.read(DataFlavor.stringFlavor) == text) {
                "GTK read back '${readBack.read(DataFlavor.stringFlavor)}', expected '$text'"
            }
            // Same instance: the entry cache is what preserves Compose's
            // JVM-local AnnotatedString flavor across an in-app copy/paste.
            check(readBack === written) { "expected the published ClipEntry instance back" }

            awaitClipboard("wl-paste to see the app's selection ($text)") {
                readClipboard("--no-newline")?.toString(Charsets.UTF_8) == text
            }
        }

    // ── Images ──────────────────────────────────────────────────────────

    private fun gtkClipboardReadsForeignImage(): TaoWindowTestCase =
        clipboardCase(
            name = "#582 GTK clipboard reads an image published by another process",
            skip = { clipboardSkipReason("wl-copy") },
        ) { focusWindow ->
            publishExternally(probePng(), "image/png")
            focusWindow()

            val clipboard = TaoLinuxClipboard(fallback = FailingClipboard)
            awaitClipboard("the clip entry to expose the image") {
                val image = clipboard.getClipEntry()?.read(DataFlavor.imageFlavor) as? Image
                image?.getWidth(null) == PROBE_IMAGE_WIDTH && image.getHeight(null) == PROBE_IMAGE_HEIGHT
            }
        }

    private fun gtkClipboardPublishesAnImage(): TaoWindowTestCase =
        clipboardCase(
            name = "#582 GTK clipboard publishes an image to the desktop",
            skip = { clipboardSkipReason("wl-paste") },
        ) { focusWindow ->
            focusWindow()

            val clipboard = TaoLinuxClipboard(fallback = FailingClipboard)
            clipboard.setClipEntry(ClipEntry(ImageTransferable(probeImage())))

            awaitClipboard("wl-paste to offer image/png") { desktopOffers("image/png") }
            val png = readClipboard("--type", "image/png")
            check(png != null) { "wl-paste returned no image bytes" }
            val decoded = ImageIO.read(ByteArrayInputStream(png))
            check(decoded != null) { "wl-paste's bytes are not a decodable image" }
            check(decoded.width == PROBE_IMAGE_WIDTH && decoded.height == PROBE_IMAGE_HEIGHT) {
                "wl-paste saw ${decoded.width}x${decoded.height}, expected $PROBE_IMAGE_WIDTH x $PROBE_IMAGE_HEIGHT"
            }
        }

    // ── Files ───────────────────────────────────────────────────────────

    private fun gtkClipboardRoundTripsAFileList(): TaoWindowTestCase =
        clipboardCase(
            name = "#582 GTK clipboard round-trips a file list",
            skip = { clipboardSkipReason("wl-copy") },
        ) { focusWindow ->
            val file = withContext(Dispatchers.IO) { File.createTempFile("nucleus-582-", ".txt") }
            file.deleteOnExit()

            publishExternally("${file.toURI()}\n".toByteArray(), "text/uri-list")
            focusWindow()

            val clipboard = TaoLinuxClipboard(fallback = FailingClipboard)
            awaitClipboard("the clip entry to expose ${file.name}") {
                val files = clipboard.getClipEntry()?.read(DataFlavor.javaFileListFlavor) as? List<*>
                files?.filterIsInstance<File>()?.map { it.canonicalPath } == listOf(file.canonicalPath)
            }

            clipboard.setClipEntry(ClipEntry(FileListTransferable(listOf(file))))
            awaitClipboard("wl-paste to see the published file list") {
                readClipboard("--type", "text/uri-list")
                    ?.toString(Charsets.UTF_8)
                    ?.contains(file.toURI().toString()) == true
            }
        }

    // ── Harness ─────────────────────────────────────────────────────────

    /**
     * A clipboard case over a mapped window, plus a `focusWindow` step the
     * driver places where the flow needs it: after an external copy for the
     * read cases, before publishing for the write ones.
     *
     * `window.focus()` is only a request — the compositor decides — so the wait
     * is on what the window actually reports. Without real focus the selection
     * never reaches us and the case would be measuring GTK's local cache.
     */
    private fun clipboardCase(
        name: String,
        skip: () -> String?,
        driver: suspend TaoWindowTestScope.(focusWindow: suspend () -> Unit) -> Unit,
    ): TaoWindowTestCase {
        val focused = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = name,
            skip = skip,
            content = {
                val windowInfo = LocalWindowInfo.current
                LaunchedEffect(windowInfo) {
                    snapshotFlow { windowInfo.isWindowFocused }.collect(focused::set)
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                driver {
                    window.focus()
                    awaitUntil("window focused") { focused.get() }
                    settle(FOCUS_SETTLE_MILLIS)
                }
            },
        )
    }

    /**
     * Hands the selection to `wl-copy` and does not return until the desktop
     * confirms the handover — see the class doc for why assuming it makes the
     * case flaky.
     *
     * Retried rather than confirmed once: the session's clipboard manager
     * re-asserts the previous selection when its owning window goes away, which
     * is exactly what the preceding case does, and it can win the race against
     * `wl-copy`. A third party fighting over the selection says nothing about
     * the code under test, so the handover is repeated until it sticks.
     */
    private suspend fun publishExternally(
        payload: ByteArray,
        type: String,
    ) {
        val offered = type.substringBefore(';')
        val deadline = System.currentTimeMillis() + CLIPBOARD_TIMEOUT_MILLIS
        while (true) {
            check(publishWithWlCopy(payload, type)) { "wl-copy failed to run" }
            repeat(CONFIRM_ATTEMPTS) {
                if (desktopOffers(offered)) return
                delay(POLL_MILLIS)
            }
            check(System.currentTimeMillis() < deadline) { "timed out handing $type to wl-copy" }
        }
    }

    /** What the desktop sees, through `data-control` — unlike a window, not focus-gated. */
    private suspend fun desktopOffers(type: String): Boolean =
        readClipboard("--list-types")?.toString(Charsets.UTF_8)?.contains(type) == true

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

    /** Reads a flavor off the entry's transferable, or null when it is not offered. */
    private suspend fun ClipEntry.read(flavor: DataFlavor): Any? {
        val transferable = nativeClipEntry as? Transferable ?: return null
        // Off the GTK thread on purpose: the lazy image / file fetchers block,
        // and this is the path real application code takes.
        return withContext(Dispatchers.IO) {
            runCatching {
                if (transferable.isDataFlavorSupported(flavor)) transferable.getTransferData(flavor) else null
            }.getOrNull()
        }
    }

    /** Any consultation of the AWT fallback means the native path silently gave up. */
    private object FailingClipboard : Clipboard {
        override suspend fun getClipEntry(): ClipEntry? = error("fell back to the AWT clipboard")

        override suspend fun setClipEntry(clipEntry: ClipEntry?) = error("fell back to the AWT clipboard")
    }

    private class ImageTransferable(
        private val image: BufferedImage,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
    }

    private class FileListTransferable(
        private val files: List<File>,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
    }

    private fun probeImage(): BufferedImage =
        BufferedImage(PROBE_IMAGE_WIDTH, PROBE_IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)

    private fun probePng(): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(probeImage(), "png", it) }.toByteArray()

    /**
     * `wl-copy` / `wl-paste` are driven off the Tao main thread on purpose:
     * while the app owns the selection, the GTK main loop is what answers the
     * reader's data request, so blocking that thread on the tool deadlocks
     * both sides.
     */
    private suspend fun publishWithWlCopy(
        payload: ByteArray,
        type: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val process =
                    ProcessBuilder("wl-copy", "--type", type)
                        .redirectErrorStream(true)
                        .start()
                process.outputStream.use { it.write(payload) }
                process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
        }

    private suspend fun readClipboard(vararg arguments: String): ByteArray? =
        withContext(Dispatchers.IO) { runProcess("wl-paste", *arguments) }

    private fun runProcess(vararg command: String): ByteArray? =
        runCatching {
            val process = ProcessBuilder(*command).start()
            val output = process.inputStream.use { it.readBytes() }
            if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                null
            } else {
                output
            }
        }.getOrNull()

    /**
     * Why this case cannot run here: no GTK clipboard, no [tool], or a peer
     * that would not share a selection with the app.
     *
     * `wl-copy` / `wl-paste` own the *Wayland* selection, so they only speak
     * to the app when the app is on Wayland too. Forcing the window backend
     * onto XWayland (`NUCLEUS_TAO_LINUX_RENDERER=x11`) inside a Wayland
     * session — which is how the X11 leg is run on a developer machine —
     * leaves the two on different selections, and the case can only time out.
     * The environment is read rather than the window's own surface kind: the
     * skip is evaluated before any window exists.
     */
    private fun clipboardSkipReason(tool: String): String? {
        linuxWithNativeClipboard()?.let { return it }
        val forcedX11 = System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
        if (forcedX11 && !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()) {
            return "app forced onto XWayland: $tool owns the Wayland selection"
        }
        return requireTool(tool)
    }

    /** Runs at case-selection time, outside any coroutine, so it stays blocking. */
    private fun requireTool(name: String): String? =
        if (runProcess("which", name)?.isNotEmpty() != true) "$name not installed" else null

    private fun linuxWithNativeClipboard(): String? {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win") || os.contains("mac") || os.contains("darwin")) {
            return "Linux only — GTK clipboard"
        }
        return if (NativeTaoLinuxClipboardBridge.isAvailable) null else "GTK clipboard unavailable"
    }

    private const val PROBE_IMAGE_WIDTH = 32
    private const val PROBE_IMAGE_HEIGHT = 16
    private const val TOOL_TIMEOUT_SECONDS = 5L
    private const val FOCUS_SETTLE_MILLIS = 300L
    private const val CLIPBOARD_TIMEOUT_MILLIS = 10_000L
    private const val POLL_MILLIS = 100L

    /** Polls per `wl-copy` attempt before handing the selection over again. */
    private const val CONFIRM_ATTEMPTS = 10
}

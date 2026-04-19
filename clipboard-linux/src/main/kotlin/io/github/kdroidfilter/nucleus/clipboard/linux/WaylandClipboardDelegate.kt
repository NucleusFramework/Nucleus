package io.github.kdroidfilter.nucleus.clipboard.linux

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val PROCESS_WRITE_TIMEOUT_SECONDS: Long = 3L
private const val STREAM_DRAIN_MS: Long = 200L

/**
 * Wayland backend implemented by delegating to `wl-copy` and `wl-paste` from the
 * `wl-clipboard` project. These binaries already cover the three protocol
 * variants (`ext-data-control-v1`, `zwlr_data_control_v1`, focus-stealing core)
 * and the GNOME-specific fallbacks, so we ride on top of them rather than
 * re-implementing the protocol state machines.
 *
 * `wl-paste --watch` drives the change counter in [startWatcher].
 */
@Suppress("TooManyFunctions")
internal class WaylandClipboardDelegate {
    private val wlCopy: String? = findBinary("wl-copy")
    private val wlPaste: String? = findBinary("wl-paste")

    private val counter = AtomicLong(0L)

    @Volatile
    private var watcher: Thread? = null

    val isAvailable: Boolean get() = wlCopy != null && wlPaste != null

    fun changeCount(): Long = counter.get()

    fun bumpCounter() {
        counter.incrementAndGet()
    }

    fun startWatcher() {
        val paste = wlPaste ?: return
        synchronized(this) {
            if (watcher != null) return
            val t =
                Thread({ runWatcher(paste) }, "nucleus-wl-clipboard-watch").apply {
                    isDaemon = true
                }
            watcher = t
            t.start()
        }
    }

    private fun runWatcher(paste: String) {
        // `wl-paste --watch CMD` spawns CMD on every selection change. We use
        // /bin/echo to emit a known marker line; parsing stdout is enough to
        // drive the change counter without a second roundtrip.
        val cmd = listOf(paste, "--watch", "echo", "CLIP_CHANGED")
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().use { r ->
                while (!Thread.currentThread().isInterrupted) {
                    val line = r.readLine() ?: break
                    if (line.contains("CLIP_CHANGED")) counter.incrementAndGet()
                }
            }
            p.destroy()
        } catch (_: IOException) {
            // wl-paste unavailable — counter stays stuck, watcher silently stops.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // --- Reads ---

    fun listTypes(): List<String> {
        val paste = wlPaste ?: return emptyList()
        val out = runCaptureText(listOf(paste, "--list-types"), timeoutMs = 1500) ?: return emptyList()
        return out
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun readText(): String? {
        val paste = wlPaste ?: return null
        return runCaptureText(listOf(paste, "-n", "-t", "text/plain;charset=utf-8"), timeoutMs = 2000)
            ?: runCaptureText(listOf(paste, "-n", "-t", "text/plain"), timeoutMs = 2000)
            ?: runCaptureText(listOf(paste, "-n", "-t", "UTF8_STRING"), timeoutMs = 2000)
    }

    fun readHtml(): String? {
        val paste = wlPaste ?: return null
        return runCaptureText(listOf(paste, "-n", "-t", "text/html"), timeoutMs = 2000)
    }

    fun readRtf(): String? {
        val paste = wlPaste ?: return null
        return runCaptureText(listOf(paste, "-n", "-t", "text/rtf"), timeoutMs = 2000)
            ?: runCaptureText(listOf(paste, "-n", "-t", "application/rtf"), timeoutMs = 2000)
    }

    fun readImagePng(): ByteArray? {
        val paste = wlPaste ?: return null
        return runCaptureBytes(listOf(paste, "-n", "-t", "image/png"), timeoutMs = 3000)
    }

    fun readFiles(): List<Path> {
        val paste = wlPaste ?: return emptyList()
        val gnome =
            runCaptureText(listOf(paste, "-n", "-t", "x-special/gnome-copied-files"), timeoutMs = 1500)
        if (gnome != null) {
            val lines = gnome.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            // First line is "copy" or "cut"; remaining lines are file:// URIs.
            val uris = if (lines.isNotEmpty() && (lines[0] == "copy" || lines[0] == "cut")) lines.drop(1) else lines
            return uris.mapNotNull(::uriToPath)
        }
        val uriList = runCaptureText(listOf(paste, "-n", "-t", "text/uri-list"), timeoutMs = 1500)
        if (uriList != null) {
            return uriList
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull(::uriToPath)
        }
        return emptyList()
    }

    // --- Writes ---

    fun write(
        text: String?,
        html: String?,
        rtf: String?,
        imagePng: ByteArray?,
        files: List<Path>?,
    ): Boolean {
        val copy = wlCopy ?: return false
        // wl-copy can only publish one MIME at a time. When multiple
        // representations are supplied we pick the richest; callers who
        // need true multi-format atomic writes on Wayland should run the
        // X11 backend under Xwayland or wait for a native ext-data-control
        // implementation.
        return when {
            !files.isNullOrEmpty() -> writeFiles(copy, files)
            imagePng != null -> writeBytes(copy, "image/png", imagePng)
            html != null -> writeBytes(copy, "text/html", html.toByteArray(StandardCharsets.UTF_8))
            rtf != null -> writeBytes(copy, "text/rtf", rtf.toByteArray(StandardCharsets.UTF_8))
            text != null -> writeBytes(copy, "text/plain;charset=utf-8", text.toByteArray(StandardCharsets.UTF_8))
            else -> false
        }.also { if (it) counter.incrementAndGet() }
    }

    fun clear(): Boolean {
        val copy = wlCopy ?: return false
        val ok =
            runSilently(listOf(copy, "--clear"), timeoutMs = 1500) ||
                runSilently(listOf(copy, "-c"), timeoutMs = 1500)
        if (ok) counter.incrementAndGet()
        return ok
    }

    private fun writeBytes(
        copy: String,
        mime: String,
        bytes: ByteArray,
    ): Boolean {
        val cmd = listOf(copy, "-t", mime)
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val p = pb.start()
            p.outputStream.use { it.write(bytes) }
            p.waitFor(PROCESS_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (p.isAlive) {
                p.destroy()
                false
            } else {
                p.exitValue() == 0
            }
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun writeFiles(
        copy: String,
        paths: List<Path>,
    ): Boolean {
        val uriList = paths.joinToString(separator = "\r\n") { pathToFileUri(it) }
        if (!writeBytes(copy, "text/uri-list", uriList.toByteArray(StandardCharsets.UTF_8))) return false
        // Best-effort GNOME side-channel so Nautilus shows "paste" instead of plain text.
        val gnomeBody =
            buildString {
                append("copy\n")
                append(paths.joinToString(separator = "\n") { pathToFileUri(it) })
            }
        writeBytes(copy, "x-special/gnome-copied-files", gnomeBody.toByteArray(StandardCharsets.UTF_8))
        return true
    }

    // --- Helpers ---

    private fun runCaptureBytes(
        cmd: List<String>,
        timeoutMs: Long,
    ): ByteArray? {
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            val p = pb.start()
            val buf = ByteArrayOutputStream()
            val t =
                Thread {
                    p.inputStream.use { it.copyTo(buf) }
                }.apply {
                    isDaemon = true
                    start()
                }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                t.join(STREAM_DRAIN_MS)
                return null
            }
            t.join(STREAM_DRAIN_MS)
            if (p.exitValue() != 0) return null
            val out = buf.toByteArray()
            if (out.isEmpty()) null else out
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun runCaptureText(
        cmd: List<String>,
        timeoutMs: Long,
    ): String? = runCaptureBytes(cmd, timeoutMs)?.toString(StandardCharsets.UTF_8)

    private fun runSilently(
        cmd: List<String>,
        timeoutMs: Long,
    ): Boolean =
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                false
            } else {
                p.exitValue() == 0
            }
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun findBinary(name: String): String? {
        val envPath = System.getenv("PATH") ?: return null
        for (dir in envPath.split(File.pathSeparatorChar)) {
            if (dir.isEmpty()) continue
            val candidate = File(dir, name)
            if (candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }

    private fun pathToFileUri(path: Path): String = path.toAbsolutePath().toUri().toString()

    private fun uriToPath(uri: String): Path? =
        try {
            val parsed = URI(uri)
            if (parsed.scheme == "file") {
                Paths.get(parsed)
            } else {
                // Some clients emit bare paths in text/uri-list (non-conformant).
                Paths.get(URLDecoder.decode(uri, StandardCharsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
}

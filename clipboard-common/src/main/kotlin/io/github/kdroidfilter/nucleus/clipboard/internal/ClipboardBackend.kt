package io.github.kdroidfilter.nucleus.clipboard.internal

import io.github.kdroidfilter.nucleus.clipboard.AccessBehavior
import io.github.kdroidfilter.nucleus.clipboard.ClipboardFormat
import java.nio.file.Path

/**
 * SPI implemented by each platform clipboard backend (macOS, Windows, Linux X11, Wayland).
 * Discovered via [java.util.ServiceLoader].
 *
 * All methods are side-effect-free on non-matching platforms (the backend's
 * [isAvailable] returns `false`). The facade in [io.github.kdroidfilter.nucleus.clipboard.Clipboard]
 * picks the first available backend and delegates.
 */
@Suppress("TooManyFunctions")
interface ClipboardBackend {
    /** Human-readable backend name, for logging. */
    val name: String

    /** True when the backend can service requests on this OS and process. */
    fun isAvailable(): Boolean

    suspend fun readText(): String?

    suspend fun readHtml(): String?

    suspend fun readRtf(): String?

    /** PNG-encoded bytes, or null if no image is available. */
    suspend fun readImagePng(): ByteArray?

    suspend fun readFiles(): List<Path>

    /** Formats currently advertised without reading bytes — safe under macOS 15.4+ privacy. */
    suspend fun availableFormats(): Set<ClipboardFormat>

    /** Writes the payload atomically. Returns true on success. */
    suspend fun write(payload: ClipboardWritePayload): Boolean

    /** Clears the clipboard. */
    suspend fun clear(): Boolean

    /**
     * Monotonic change counter, used by the watcher. Must not open/read the clipboard —
     * on macOS this is `NSPasteboard.changeCount`, a cheap Mach IPC call.
     */
    fun changeCount(): Long

    /** Sets the platform privacy policy (macOS 15.4+). No-op elsewhere. */
    fun setAccessBehavior(behavior: AccessBehavior)

    /**
     * Reads the currently effective privacy policy.
     *
     * Returns `null` when the host OS has no such concept (macOS &lt; 15.4,
     * Windows, Linux) — callers should interpret this as "unrestricted access".
     */
    fun accessBehavior(): AccessBehavior?

    /** True when [setAccessBehavior] and [accessBehavior] are honored by the backend. */
    fun isAccessBehaviorSupported(): Boolean
}

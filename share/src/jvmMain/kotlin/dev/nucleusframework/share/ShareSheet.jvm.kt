package dev.nucleusframework.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal actual val isPlatformShareSupported: Boolean
    get() = NativeShareBridge.isLoaded

internal actual suspend fun platformShare(request: ShareRequest): Unit = shareFromDesktop(request, ShareParent.Auto)

/**
 * [ShareSheet.share], attached to [parent]. A [ShareParent.Linux.keepAlive] is closed
 * exactly once, whether the share succeeds, fails or is cancelled.
 *
 * @throws ShareException when the request is invalid or the UI could not be shown
 */
public suspend fun ShareSheet.share(
    request: ShareRequest,
    parent: ShareParent,
): Unit = shareFromDesktop(request, parent)

// The native calls block (WinRT file resolution, D-Bus round trips, the hop to the
// AppKit main thread), so they never run on the caller's thread — which may well be
// the UI thread they need to hop to.
private suspend fun shareFromDesktop(
    request: ShareRequest,
    parent: ShareParent,
) {
    // Owned here until the native side takes it over (and closes it once the portal
    // dialog is gone); anything that fails or is cancelled before closes it instead.
    val owned = parent.closingKeepAliveOnce()
    var presented = false
    try {
        request.validate()
        if (!NativeShareBridge.isLoaded) {
            throw ShareException(ShareError.Unsupported, "The nucleus_share native library is unavailable")
        }
        withContext(Dispatchers.IO) {
            NativeShareBridge.share(request, owned)
            // Inside the block: a cancellation landing as withContext returns must
            // not close a lease the portal dialog is still using.
            presented = true
        }
    } finally {
        if (!presented) (owned as? ShareParent.Linux)?.keepAlive?.close()
    }
}

private fun ShareParent.closingKeepAliveOnce(): ShareParent {
    if (this !is ShareParent.Linux || keepAlive == null) return this
    val lease = keepAlive
    val closed = AtomicBoolean(false)
    return copy(keepAlive = AutoCloseable { if (closed.compareAndSet(false, true)) lease.close() })
}

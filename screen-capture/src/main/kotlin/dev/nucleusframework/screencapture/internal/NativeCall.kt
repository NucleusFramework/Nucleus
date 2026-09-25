package dev.nucleusframework.screencapture.internal

import dev.nucleusframework.screencapture.CaptureFailure
import dev.nucleusframework.screencapture.CapturePermission
import dev.nucleusframework.screencapture.CaptureRegion
import dev.nucleusframework.screencapture.ScreenCaptureException
import dev.nucleusframework.screencapture.ScreenImage

/** The out-parameters of one [NativeScreenCapture] call, turned into a result or a [ScreenCaptureException]. */
internal class NativeCall(
    private val what: String,
) {
    /** `[status, width, height]`. */
    val result = IntArray(RESULT_SIZE)

    /** A diagnostic from the native side, on failure. */
    val message = arrayOfNulls<String>(1)

    fun check(status: Int) {
        if (status == NativeScreenCapture.STATUS_OK) return
        val failure = failureOf(status)
        val detail = message[0]?.let { ": $it" }.orEmpty()
        throw ScreenCaptureException(failure, "Cannot $what ($failure)$detail")
    }

    fun image(
        pixels: IntArray?,
        scaleFactor: Float,
    ): ScreenImage {
        // A null array without an error status means the JVM could not allocate it.
        val lost = pixels == null && result[0] == NativeScreenCapture.STATUS_OK
        check(if (lost) NativeScreenCapture.STATUS_FAILED else result[0])
        return ScreenImage(result[1], result[2], checkNotNull(pixels), scaleFactor)
    }

    private fun failureOf(status: Int): CaptureFailure =
        when (status) {
            NativeScreenCapture.STATUS_UNSUPPORTED -> CaptureFailure.Unsupported
            NativeScreenCapture.STATUS_PERMISSION_DENIED -> CaptureFailure.PermissionDenied
            NativeScreenCapture.STATUS_DISPLAY_NOT_FOUND -> CaptureFailure.DisplayNotFound
            NativeScreenCapture.STATUS_WINDOW_NOT_FOUND -> CaptureFailure.WindowNotFound
            NativeScreenCapture.STATUS_CANCELLED -> CaptureFailure.Cancelled
            NativeScreenCapture.STATUS_TIMEOUT -> CaptureFailure.Timeout
            NativeScreenCapture.STATUS_INVALID_REGION -> CaptureFailure.InvalidRegion
            else -> CaptureFailure.Failed
        }

    private companion object {
        const val RESULT_SIZE = 3
    }
}

internal fun permissionOf(value: Int): CapturePermission =
    when (value) {
        NativeScreenCapture.PERMISSION_GRANTED -> CapturePermission.Granted
        NativeScreenCapture.PERMISSION_DENIED -> CapturePermission.Denied
        NativeScreenCapture.PERMISSION_NOT_DETERMINED -> CapturePermission.NotDetermined
        else -> CapturePermission.NotRequired
    }

/** [region] clipped to a `width` × `height` image; [CaptureFailure.InvalidRegion] when nothing is left. */
internal fun clip(
    region: CaptureRegion,
    width: Int,
    height: Int,
): CaptureRegion {
    val left = region.x.coerceAtLeast(0)
    val top = region.y.coerceAtLeast(0)
    val right = region.right.coerceAtMost(width)
    val bottom = region.bottom.coerceAtMost(height)
    if (right <= left || bottom <= top) {
        throw ScreenCaptureException(CaptureFailure.InvalidRegion, "$region is outside the ${width}x$height desktop")
    }
    return CaptureRegion(left, top, right - left, bottom - top)
}

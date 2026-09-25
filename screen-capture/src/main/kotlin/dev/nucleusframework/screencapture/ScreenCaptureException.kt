package dev.nucleusframework.screencapture

/** Why a capture failed. */
public enum class CaptureFailure {
    /** The platform or session has no capture backend (e.g. Wayland without a portal). */
    Unsupported,

    /** The user or the system denied screen recording (macOS TCC, portal permission store). */
    PermissionDenied,

    /** The user dismissed the platform's capture dialog (interactive portal screenshot). */
    Cancelled,

    /** No display matches the requested one — it was unplugged or the id is stale. */
    DisplayNotFound,

    /** No window matches the requested id, or it is not capturable (minimized, unmapped). */
    WindowNotFound,

    /** The requested region does not intersect the display. */
    InvalidRegion,

    /** The platform did not answer in time. */
    Timeout,

    /** Any other platform error. */
    Failed,
}

/** Thrown by [ScreenCapture] when a capture cannot be produced; see [failure]. */
public class ScreenCaptureException(
    public val failure: CaptureFailure,
    message: String,
) : RuntimeException(message)

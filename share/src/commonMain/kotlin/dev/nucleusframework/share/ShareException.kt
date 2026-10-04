package dev.nucleusframework.share

/**
 * Why [ShareSheet.share] failed. The desktop bridge reports these by ordinal
 * (`src/main/native/src/error.rs`): only ever append.
 */
public enum class ShareError {
    /** The request has no item. */
    Empty,

    /** An item is blank or malformed (an unparsable URL, a MIME type of spaces…). */
    InvalidItem,

    /** No app or system service can receive this payload. */
    NoHandler,

    /** A share sheet is already on screen (iOS). */
    AlreadyOpen,

    /** The platform shares, but not this kind of item (a `content://` URI on desktop…). */
    UnsupportedItem,

    /** No window or activity to present the share UI from. */
    NoWindow,

    /** This platform has no share UI, or the native bridge is unavailable. */
    Unsupported,

    /** Reading or staging a file failed. */
    Io,

    /** The platform API failed; see the message. */
    Platform,
}

/** Thrown by [ShareSheet.share]; [error] says why. */
public class ShareException(
    public val error: ShareError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: error.name, cause)

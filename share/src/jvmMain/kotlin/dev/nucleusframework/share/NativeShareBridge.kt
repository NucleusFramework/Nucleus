package dev.nucleusframework.share

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_share"

// Item kinds and error codes are mirrored in src/main/native/src/{lib,error}.rs.
private const val KIND_TEXT = 0
private const val KIND_URL = 1
private const val KIND_FILE = 2
private const val KIND_FILE_URI = 3

internal object NativeShareBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeShareBridge::class.java)

    val isLoaded: Boolean
        get() = loaded

    fun share(
        request: ShareRequest,
        parent: ShareParent,
    ) {
        val kinds = IntArray(request.items.size)
        val values =
            Array(request.items.size) { index ->
                when (val item = request.items[index]) {
                    is ShareItem.Text -> item.text.also { kinds[index] = KIND_TEXT }
                    is ShareItem.Url -> item.url.also { kinds[index] = KIND_URL }
                    is ShareItem.File -> item.path.also { kinds[index] = KIND_FILE }
                    is ShareItem.FileUri -> item.uri.also { kinds[index] = KIND_FILE_URI }
                }
            }
        val message = arrayOfNulls<String>(1)
        val code =
            nativeShare(
                title = request.title,
                subject = request.subject,
                kinds = kinds,
                values = values,
                parentHandle =
                    when (parent) {
                        is ShareParent.Windows -> parent.hwnd
                        is ShareParent.MacOs -> parent.nsWindow
                        else -> 0L
                    },
                parentPortal = (parent as? ShareParent.Linux)?.portalParent,
                anchor =
                    (parent as? ShareParent.MacOs)?.anchor?.let {
                        doubleArrayOf(it.x, it.y, it.width, it.height)
                    },
                onParentReleased = (parent as? ShareParent.Linux)?.keepAlive?.let { lease -> Runnable(lease::close) },
                message = message,
            )
        if (code != 0) throw ShareException(errorOf(code), message[0])
    }

    /** The native codes are the [ShareError] ordinals plus one, `0` being success. */
    private fun errorOf(code: Int): ShareError = ShareError.entries.getOrElse(code - 1) { ShareError.Platform }

    @Suppress("LongParameterList")
    @JvmStatic
    private external fun nativeShare(
        title: String?,
        subject: String?,
        kinds: IntArray,
        values: Array<String>,
        parentHandle: Long,
        parentPortal: String?,
        anchor: DoubleArray?,
        onParentReleased: Runnable?,
        message: Array<String?>,
    ): Int
}

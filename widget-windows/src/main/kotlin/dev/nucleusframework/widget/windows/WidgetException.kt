package dev.nucleusframework.widget.windows

// HRESULT_FROM_WIN32(ERROR_NOT_SUPPORTED)
internal const val E_NOT_AVAILABLE: Int = 0x80070032.toInt()

/**
 * A failed Windows widget API call.
 *
 * @property hresult The failing `HRESULT`.
 */
public class WidgetException(
    message: String,
    public val hresult: Int,
) : RuntimeException("$message (HRESULT 0x%08X)".format(hresult))

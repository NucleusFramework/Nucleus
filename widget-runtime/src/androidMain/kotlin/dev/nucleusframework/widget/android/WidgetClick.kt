package dev.nucleusframework.widget.android

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import java.security.MessageDigest

/**
 * What a widget click carries back to its receiver. It travels in the intent's data URI, not in
 * extras: two clicks with different data are two distinct `PendingIntent`s, so a launcher still
 * showing an older render never fires the extras of a newer one.
 */
internal class WidgetClick(
    val appWidgetId: Int,
    /** The action key (`a0`, …), or `null` for an input alone. */
    val key: String?,
    /** The [digest] of what [key] stood for when drawn (see `WidgetActionSignatures`). */
    val signature: String?,
    /** The input id and the value the click gives it. */
    val input: Pair<String, String>?,
) {
    fun toIntent(): Intent =
        Intent(AndroidWidgetHost.ACTION_CLICK, toUri()).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun toUri(): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(appWidgetId.toString())
            .apply {
                key?.let { appendQueryParameter(KEY, it) }
                signature?.let { appendQueryParameter(SIGNATURE, it) }
                input?.let { (id, value) ->
                    appendQueryParameter(INPUT, id)
                    appendQueryParameter(VALUE, value)
                }
            }.build()

    companion object {
        /**
         * A signature as it travels in the URI: a digest of its UTF-16 code units. The text itself
         * does not survive the URI's UTF-8 encoding when it holds a lone surrogate (it comes back as
         * `?`, and every click on it would be dropped as outdated), and could be kilobytes long.
         */
        fun digest(signature: String): String {
            val sha = MessageDigest.getInstance("SHA-256")
            for (c in signature) {
                sha.update((c.code shr BYTE_BITS).toByte())
                sha.update(c.code.toByte())
            }
            return sha.digest().take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
        }

        private const val BYTE_BITS = 8
        private const val DIGEST_BYTES = 12
        private const val SCHEME = "nucleus-widget"
        private const val KEY = "k"
        private const val SIGNATURE = "s"
        private const val INPUT = "i"
        private const val VALUE = "v"

        fun parse(intent: Intent): WidgetClick? {
            val uri = intent.data?.takeIf { it.scheme == SCHEME } ?: return null
            val appWidgetId = uri.authority?.toIntOrNull() ?: return null
            val input = uri.getQueryParameter(INPUT)
            val value = uri.getQueryParameter(VALUE)
            return WidgetClick(
                appWidgetId = appWidgetId,
                key = uri.getQueryParameter(KEY),
                signature = uri.getQueryParameter(SIGNATURE),
                input = if (input != null && value != null) input to value else null,
            )
        }
    }
}

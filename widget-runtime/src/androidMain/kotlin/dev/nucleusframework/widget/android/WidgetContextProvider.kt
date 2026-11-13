package dev.nucleusframework.widget.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * Captures the application context at process start, before `Application.onCreate` runs (the same
 * mechanism as `androidx.startup`, without the dependency). Serves no data.
 */
internal class WidgetContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { application = it }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        @Volatile
        var application: Context? = null
            private set
    }
}

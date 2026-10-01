package dev.nucleusframework.share

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.lang.ref.WeakReference

/**
 * Declared by the library manifest; not meant to be used directly.
 *
 * Serves the files [ShareSheet] stages under `cacheDir/nucleus-share/<id>/<name>`,
 * read-only and nothing else, to the apps it grants them to — the job `FileProvider`
 * does, without the AndroidX dependency. Created before `Application.onCreate`, it
 * also tracks the resumed activity, which is what the chooser is started from.
 */
public class NucleusShareProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return true
        AndroidShareContext.install(application)
        return true
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Shared files are read-only")
        val file = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        if (file != null) {
            cursor.addRow(
                columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                },
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = uri.lastPathSegment?.let(::guessMimeType)

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("Shared files are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /** `content://<authority>/<id>/<name>` → the staged file, never anything outside the staging root. */
    private fun resolve(uri: Uri): File? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val root = stagingRoot(context).canonicalFile
        val file = File(File(root, segments[0]), segments[1]).canonicalFile
        return file.takeIf { it.parentFile?.parentFile == root && it.isFile }
    }
}

internal fun stagingRoot(context: Context): File = File(context.cacheDir, "nucleus-share")

internal fun shareAuthority(context: Context): String = "${context.packageName}.nucleus.share"

internal fun guessMimeType(fileName: String): String =
    fileName
        .substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
        ?: "application/octet-stream"

/**
 * The application and the activity resumed last, known once [NucleusShareProvider] has
 * run. The activity is kept until destroyed, not paused: in multi-window mode a paused
 * activity is still on screen, and a stopped one can still start the chooser.
 */
internal object AndroidShareContext {
    @Volatile
    var application: Application? = null
        private set

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    val currentActivity: Activity?
        get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun install(application: Application) {
        if (this.application != null) return
        this.application = application
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumed = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (resumed?.get() === activity) resumed = null
                }
            },
        )
    }
}

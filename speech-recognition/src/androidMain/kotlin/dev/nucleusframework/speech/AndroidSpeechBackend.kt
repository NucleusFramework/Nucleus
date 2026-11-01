package dev.nucleusframework.speech

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import java.lang.ref.WeakReference

private const val TAG = "NucleusSpeech"

internal actual fun platformSpeechBackend(): SpeechBackend = AndroidSpeechBackend

internal actual fun reportListenerFailure(failure: Throwable) {
    Log.e(TAG, "Speech recognition listener threw", failure)
}

internal object AndroidSpeechBackend : SpeechBackend {
    override val isSupported: Boolean
        get() {
            // Querying the service neither starts it nor asks for any permission.
            val context = SpeechActivityTracker.application ?: return false
            return SpeechRecognizer.isRecognitionAvailable(context) || isOnDeviceRecognitionAvailable(context)
        }

    override val engineName: String = "android-speechrecognizer"

    override fun start(
        id: Long,
        options: SpeechRecognitionOptions,
    ) {
        val activity =
            SpeechActivityTracker.foreground
                ?: throw SpeechException(SpeechErrorKind.Other, "Speech input needs an active application window.")
        AndroidSpeechSession.start(activity, id, options)
    }

    override fun stop(
        id: Long,
        cancel: Boolean,
    ) {
        AndroidSpeechSession.stop(id, cancel)
    }
}

internal fun isOnDeviceRecognitionAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

/**
 * Keeps track of the resumed Activity, which recognition and the permission prompt are bound to,
 * so an app needs no Android-specific setup. Declared in the library manifest, so it starts with
 * the app; Kotlin-internal, but public in bytecode, which is all the framework needs.
 */
internal class SpeechActivityTracker : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        application = app
        app.registerActivityLifecycleCallbacks(Callbacks)
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

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumed.get() === activity) resumed = WeakReference(null)
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ): Unit = Unit

        override fun onActivityStarted(activity: Activity): Unit = Unit

        override fun onActivityPaused(activity: Activity): Unit = Unit

        override fun onActivityStopped(activity: Activity): Unit = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ): Unit = Unit
    }

    companion object {
        @Volatile
        var application: Application? = null
            private set

        @Volatile
        private var resumed = WeakReference<Activity>(null)

        val foreground: Activity?
            get() = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    }
}

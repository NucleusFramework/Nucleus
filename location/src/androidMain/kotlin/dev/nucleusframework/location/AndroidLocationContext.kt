package dev.nucleusframework.location

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The application context and the resumed activity, captured by [LocationContextProvider] at
 * process start — the same mechanism as `androidx.startup`, without the dependency.
 */
internal object AndroidLocationContext {
    @Volatile
    private var application: Application? = null

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    private val observers = CopyOnWriteArrayList<ActivityObserver>()

    val applicationOrNull: Application?
        get() = application

    val requireApplication: Application
        get() =
            checkNotNull(application) {
                "dev.nucleusframework.location is not initialized: its LocationContextProvider was " +
                    "removed from the merged manifest"
            }

    val resumedActivity: Activity?
        get() = resumed?.get()

    fun install(application: Application) {
        if (this.application != null) return
        this.application = application
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumed = WeakReference(activity)
                    observers.forEach { it.onResumed(activity) }
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumed?.get() === activity) resumed = null
                    observers.forEach { it.onPaused(activity) }
                }

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

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    fun addObserver(observer: ActivityObserver) {
        observers += observer
    }

    fun removeObserver(observer: ActivityObserver) {
        observers -= observer
    }

    interface ActivityObserver {
        fun onResumed(activity: Activity)

        fun onPaused(activity: Activity)
    }
}

/** Declared in the library manifest; runs before `Application.onCreate`. */
internal class LocationContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let(AndroidLocationContext::install)
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
}

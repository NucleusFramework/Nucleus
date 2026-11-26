package dev.nucleusframework.location

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Shows the permission dialog and receives its answer in `onRequestPermissionsResult`, which only
 * the requesting activity gets — the app's activity is not ours, and the library uses no androidx.
 * Translucent and declared with `configChanges`, so the dialog causing a configuration change, or
 * a rotation while it is up, does not recreate it.
 */
internal class PermissionRequestActivity : Activity() {
    private val requestId: Long
        get() = intent.getLongExtra(EXTRA_REQUEST_ID, -1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (permissions.isNullOrEmpty()) {
            finish()
        } else if (savedInstanceState == null) {
            requestPermissions(permissions, REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        PermissionRequests.complete(
            requestId,
            permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED },
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        // Finished without an answer (the system tore it down): the request must not wait forever.
        PermissionRequests.complete(requestId, emptyList())
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PERMISSIONS = "dev.nucleusframework.location.PERMISSIONS"
        const val EXTRA_REQUEST_ID = "dev.nucleusframework.location.REQUEST_ID"
        private const val REQUEST_CODE = 0x4E4C // "NL"
    }
}

/** Requests in flight, completed by [PermissionRequestActivity]. */
internal object PermissionRequests {
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    /**
     * Permissions the dialog reported granted. `checkSelfPermission` can lag behind the result
     * (seen on Android 10); a revocation kills the process, so this never goes stale.
     */
    private val granted = ConcurrentHashMap.newKeySet<String>()

    fun wasGranted(permission: String): Boolean = permission in granted

    /** Shows the dialog over [activity] and suspends until it is answered. */
    suspend fun request(
        activity: Activity,
        permissions: Array<String>,
    ) {
        val id = nextId.incrementAndGet()
        val answered = CompletableDeferred<Unit>()
        pending[id] = answered
        try {
            val intent =
                Intent(activity, PermissionRequestActivity::class.java)
                    .putExtra(PermissionRequestActivity.EXTRA_PERMISSIONS, permissions)
                    .putExtra(PermissionRequestActivity.EXTRA_REQUEST_ID, id)
            val started =
                withContext(Dispatchers.Main) {
                    try {
                        activity.startActivity(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        // Removed from the merged manifest: nothing can be asked.
                        false
                    }
                }
            if (started) answered.await()
        } finally {
            pending.remove(id)
        }
    }

    fun complete(
        id: Long,
        grantedPermissions: List<String>,
    ) {
        granted += grantedPermissions
        pending.remove(id)?.complete(Unit)
    }
}

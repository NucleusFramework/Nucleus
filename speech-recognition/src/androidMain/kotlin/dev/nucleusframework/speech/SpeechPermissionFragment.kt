@file:Suppress("DEPRECATION")

package dev.nucleusframework.speech

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.content.pm.PackageManager

// Must fit in 16 bits: android.app.Fragment encodes its index in the upper half.
private const val REQUEST_CODE = 0x5350
private const val TAG = "dev.nucleusframework.speech.SpeechPermissionFragment"

/**
 * Headless fragment asking for `RECORD_AUDIO`. `Fragment.requestPermissions` routes the result
 * here rather than to the Activity's `onRequestPermissionsResult`, so an app needs no permission
 * plumbing of its own. The framework fragment is the only way to do that without the Activity
 * registering an `ActivityResultLauncher` before it starts.
 *
 * Kotlin-internal, but public in bytecode, which is all the framework needs to recreate it.
 */
internal class SpeechPermissionFragment : Fragment() {
    enum class Outcome { Granted, Denied, Cancelled }

    // Null after delivery or cancellation; the OS dialog can outlive its caller.
    private var callback: ((Outcome) -> Unit)? = null
    private var launched = false
    private var completed = false

    /** Releases this session's callback only: a dialog already shown may still return. */
    fun cancel() {
        callback = null
        if (!launched) {
            completed = true
            removeSelf()
        }
    }

    override fun onResume() {
        super.onResume()
        // A fragment restored by the framework has no callback: nothing is waiting for it.
        if (completed || (!launched && callback == null)) {
            removeSelf()
            return
        }
        if (!launched) {
            launched = true
            try {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
            } catch (_: RuntimeException) {
                deliver(Outcome.Cancelled)
                removeSelf()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != REQUEST_CODE) return
        // An empty result means the request was interrupted, which counts as denied.
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        deliver(if (granted) Outcome.Granted else Outcome.Denied)
        removeSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Not retained: end the old Activity's session, a later grant must never resume
        // recognition against a destroyed Activity.
        deliver(Outcome.Cancelled)
    }

    private fun deliver(outcome: Outcome) {
        if (completed) return
        completed = true
        val pending = callback
        callback = null
        pending?.invoke(outcome)
    }

    private fun removeSelf() {
        if (isAdded) fragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    companion object {
        /** Asks without blocking, on the main thread. The callback runs once, unless cancelled first. */
        fun request(
            activity: Activity,
            callback: (Outcome) -> Unit,
        ): SpeechPermissionFragment? {
            if (activity.isFinishing || activity.isDestroyed) {
                callback(Outcome.Cancelled)
                return null
            }
            val manager = activity.fragmentManager
            val existing = manager.findFragmentByTag(TAG) as? SpeechPermissionFragment
            if (existing != null && !existing.completed) {
                if (existing.callback != null) {
                    callback(Outcome.Cancelled)
                    return null
                }
                // Cancelling a session cannot dismiss the OS dialog: hand its pending result
                // over instead of showing a second one.
                existing.callback = callback
                return existing
            }
            val fragment = SpeechPermissionFragment().also { it.callback = callback }
            return try {
                val transaction = manager.beginTransaction()
                existing?.let(transaction::remove)
                transaction.add(fragment, TAG).commitNowAllowingStateLoss()
                fragment.takeUnless { it.completed }
            } catch (_: RuntimeException) {
                fragment.deliver(Outcome.Cancelled)
                null
            }
        }
    }
}

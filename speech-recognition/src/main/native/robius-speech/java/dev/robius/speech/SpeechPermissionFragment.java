package dev.robius.speech;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * Headless fragment that asks for RECORD_AUDIO. `Fragment.requestPermissions` routes the
 * result back here rather than to the activity's own `onRequestPermissionsResult`, so an
 * application needs no permission plumbing of its own. The request belongs to the current
 * Activity and is cancelled if that Activity is recreated or destroyed.
 */
public final class SpeechPermissionFragment extends Fragment {
    public enum Outcome { GRANTED, DENIED, CANCELLED }
    /** Delivered once on the UI thread, unless the caller cancels first. */
    public interface Result { void onResult(Outcome outcome); }

    // Must be <= 0xffff: android.app.Fragment encodes its index in the upper 16 bits.
    private static final int REQUEST_CODE = 0x5350;
    private static final String HOST_TAG = "dev.robius.speech.PermissionHost";
    private static final String TAG = "dev.robius.speech.SpeechPermissionFragment";
    private static final String[] PERMISSIONS = { android.Manifest.permission.RECORD_AUDIO };

    // Null after delivery or cancellation; the OS dialog can outlive its caller.
    private Result callback;
    private boolean launched;
    private boolean completed;
    private Fragment host;
    private FragmentManager manager;
    private FragmentManager.FragmentLifecycleCallbacks lifecycle;

    /** Required for Fragment transactions; this helper is never saved for restoration. */
    public SpeechPermissionFragment() {}

    private SpeechPermissionFragment(Result callback) {
        this.callback = callback;
    }

    /** Asks without blocking. Called on the UI thread by NativeSpeech. */
    public static SpeechPermissionFragment request(Activity activity, Result callback) {
        SpeechPermissionFragment fragment = null;
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                callback.onResult(Outcome.CANCELLED);
                return null;
            }
            FragmentManager manager = activity.getFragmentManager();
            Fragment existing = manager.findFragmentByTag(HOST_TAG);
            if (existing != null) {
                Fragment child = existing.getChildFragmentManager().findFragmentByTag(TAG);
                if (child instanceof SpeechPermissionFragment) {
                    SpeechPermissionFragment pending = (SpeechPermissionFragment) child;
                    if (!pending.completed) {
                        if (pending.callback != null) {
                            callback.onResult(Outcome.CANCELLED);
                            return null;
                        }
                        // Cancelling a session cannot dismiss the OS dialog. Transfer
                        // its pending result instead of launching a duplicate request.
                        pending.callback = callback;
                        return pending;
                    }
                }
                // Includes an empty framework host restored after process death.
                manager.beginTransaction().remove(existing).commitNowAllowingStateLoss();
            }
            fragment = new SpeechPermissionFragment(callback);
            fragment.manager = manager;
            fragment.host = new Fragment();
            fragment.lifecycle = fragment.hostLifecycle();
            manager.registerFragmentLifecycleCallbacks(fragment.lifecycle, false);
            manager.beginTransaction().add(fragment.host, HOST_TAG).commitNowAllowingStateLoss();
            fragment.host.getChildFragmentManager().beginTransaction()
                .add(fragment, TAG).commitNowAllowingStateLoss();
            return fragment.completed ? null : fragment;
        } catch (RuntimeException error) {
            if (fragment == null) {
                callback.onResult(Outcome.CANCELLED);
            } else {
                fragment.deliver(Outcome.CANCELLED);
                if (fragment.host.isAdded()) fragment.removeSelf();
                else fragment.unregisterLifecycle();
            }
            return null;
        }
    }

    private FragmentManager.FragmentLifecycleCallbacks hostLifecycle() {
        return new FragmentManager.FragmentLifecycleCallbacks() {
            @Override public void onFragmentSaveInstanceState(FragmentManager fm, Fragment f, Bundle state) {
                // The helper comes from an embedded child DEX, which Android's Activity
                // class loader cannot restore. Persist only our plain framework host.
                // This callback runs after child state is saved. The host is exclusively
                // ours and has no other state; never modify another fragment's bundle.
                if (f == host) state.clear();
            }
            @Override public void onFragmentDetached(FragmentManager fm, Fragment f) {
                if (f == host) unregisterLifecycle();
            }
        };
    }

    private void unregisterLifecycle() {
        if (lifecycle != null) {
            manager.unregisterFragmentLifecycleCallbacks(lifecycle);
            lifecycle = null;
        }
    }

    /** Release only this session's callback; a launched dialog may still return. */
    public void cancel() {
        callback = null;
        if (!launched) {
            completed = true;
            removeSelf();
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (completed || (!launched && callback == null)) {
            removeSelf();
            return;
        }
        if (!launched) {
            launched = true;
            try {
                requestPermissions(PERMISSIONS, REQUEST_CODE);
            } catch (RuntimeException error) {
                deliver(Outcome.CANCELLED);
                removeSelf();
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_CODE) return;
        // An empty array means the request was interrupted, which counts as denied.
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        deliver(granted ? Outcome.GRANTED : Outcome.DENIED);
        removeSelf();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        // Neither fragment is retained. End the old Activity's session now; a
        // later grant must never resume recognition against its destroyed Activity.
        deliver(Outcome.CANCELLED);
    }

    private void deliver(Outcome outcome) {
        if (completed) return;
        completed = true;
        Result pending = callback;
        callback = null;
        if (pending != null) pending.onResult(outcome);
    }

    private void removeSelf() {
        // Removal can be requested from within a Fragment lifecycle callback.
        // Keep the save-state guard until the host actually detaches.
        if (host != null && host.isAdded()) {
            manager.beginTransaction().remove(host).commitAllowingStateLoss();
        }
    }
}

package dev.robius.speech;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * One dictation session, driving the system SpeechRecognizer.
 *
 * This is loaded from the crate's embedded DEX, so an app doesn't need a custom
 * Activity or any Java of its own. Everything here runs on the main looper.
 */
public final class NativeSpeech implements RecognitionListener, Application.ActivityLifecycleCallbacks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HashMap<Long, NativeSpeech> SESSIONS = new HashMap<>();
    private static native void event(long id, int kind, String text, float level);
    // Error kinds, matching `codes` in lib.rs.
    private static final int ERR_OTHER = 5;
    private static final int ERR_PERMISSION = 6;
    private static final int ERR_UNAVAILABLE = 7;
    private static final int ERR_LANGUAGE = 8;
    private static final int ERR_AUDIO = 9;

    private final Activity activity;
    private final long id;
    private final String locale;
    private final boolean preferOnDevice;
    private SpeechRecognizer recognizer;
    private SpeechPermissionFragment permissionRequest;
    private boolean listening = true;
    private boolean awaitingResult;
    private boolean started;
    private boolean disposed;
    private boolean triedSystemFallback;
    private boolean askedForPermission;
    private int recognizerGeneration;
    private int transientFailures;
    private static final int MAX_TRANSIENT_RETRIES = 3;
    private String pending = "";
    private final Runnable restart = () -> listen();
    private final Runnable finalTimeout = () -> finish();

    private NativeSpeech(Activity activity, long id, String locale, boolean preferOnDevice) {
        this.activity = activity;
        this.id = id;
        this.locale = locale;
        this.preferOnDevice = preferOnDevice;
    }

    public static boolean supported(Activity activity) {
        // Querying the service does not start it or request any permission.
        return SpeechRecognizer.isRecognitionAvailable(activity) || onDeviceAvailable(activity);
    }

    private static boolean onDeviceAvailable(Context context) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            return (Boolean) SpeechRecognizer.class.getMethod("isOnDeviceRecognitionAvailable", Context.class)
                .invoke(null, context);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    public static void start(Activity activity, long id, String locale, boolean preferOnDevice) {
        MAIN.post(() -> {
            NativeSpeech session = new NativeSpeech(activity, id, locale, preferOnDevice);
            SESSIONS.put(id, session);
            session.begin();
        });
    }

    public static void stop(long id, boolean cancel) {
        MAIN.post(() -> {
            NativeSpeech session = SESSIONS.get(id);
            if (session == null) return;
            session.listening = false;
            MAIN.removeCallbacks(session.restart);
            if (cancel) {
                session.dispose();
            } else if (!session.awaitingResult) {
                session.finish();
            } else {
                try {
                    session.recognizer.stopListening();
                    MAIN.postDelayed(session.finalTimeout, 3000);
                } catch (RuntimeException error) {
                    session.finish();
                }
            }
        });
    }

    private void begin() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            // Bound to this discarded Activity, not to the device.
            fail("The application is no longer active.");
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Ask once, then resume from the top. The guard also stops a recognizer
            // that reports the permission as missing even after a grant from looping.
            if (askedForPermission) {
                fail("Allow microphone access in Android settings to use speech input.", ERR_PERMISSION);
                return;
            }
            askedForPermission = true;
            permissionRequest = SpeechPermissionFragment.request(activity, outcome -> {
                if (disposed) return;
                permissionRequest = null;
                if (outcome == SpeechPermissionFragment.Outcome.GRANTED) {
                    begin();
                } else if (outcome == SpeechPermissionFragment.Outcome.CANCELLED) {
                    finish();
                } else {
                    fail("Microphone access is needed for speech input.", ERR_PERMISSION);
                }
            });
            return;
        }
        try {
            activity.getApplication().registerActivityLifecycleCallbacks(this);
            listen();
        } catch (RuntimeException error) {
            fail("Unable to start the system speech recognition service.", ERR_UNAVAILABLE);
        }
    }

    private SpeechRecognizer createRecognizer() {
        if (preferOnDevice && !triedSystemFallback && onDeviceAvailable(activity)) {
            try {
                SpeechRecognizer deviceRecognizer = (SpeechRecognizer) SpeechRecognizer.class
                    .getMethod("createOnDeviceSpeechRecognizer", Context.class).invoke(null, activity);
                if (deviceRecognizer != null) return deviceRecognizer;
            } catch (ReflectiveOperationException | RuntimeException error) {
                // Some vendor services advertise on-device support without providing it.
            }
        }
        return SpeechRecognizer.isRecognitionAvailable(activity)
            ? SpeechRecognizer.createSpeechRecognizer(activity) : null;
    }

    private void listen() {
        if (disposed || !listening) return;
        pending = "";
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (locale != null && !locale.isEmpty()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
        // On older devices this is a preference: the installed system service may ignore it.
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice && !triedSystemFallback);
        try {
            if (recognizer == null) recognizer = createRecognizer();
            if (recognizer == null) {
                fail("No speech recognition service is installed on this device.", ERR_UNAVAILABLE);
                return;
            }
            // Each attempt gets a new listener generation. Late callbacks from
            // an utterance or cancelled client must not affect its successor.
            installListener();
            awaitingResult = true;
            recognizer.startListening(intent);
        } catch (RuntimeException error) {
            fail("Unable to start microphone recording for speech input.", ERR_AUDIO);
        }
    }

    private void installListener() {
        // Destroyed services can still have Binder callbacks queued. Keep those
        // from committing an old hypothesis after switching to the fallback.
        final int generation = ++recognizerGeneration;
        recognizer.setRecognitionListener(new RecognitionListener() {
            private boolean current() { return !disposed && generation == recognizerGeneration; }
            @Override public void onReadyForSpeech(Bundle params) {
                if (current()) NativeSpeech.this.onReadyForSpeech(params);
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {
                if (current()) NativeSpeech.this.onRmsChanged(rms);
            }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                if (current()) NativeSpeech.this.onEndOfSpeech();
            }
            @Override public void onError(int error) {
                if (current()) NativeSpeech.this.onError(error);
            }
            @Override public void onResults(Bundle results) {
                if (current()) NativeSpeech.this.onResults(results);
            }
            @Override public void onPartialResults(Bundle results) {
                if (current()) NativeSpeech.this.onPartialResults(results);
            }
            @Override public void onEvent(int type, Bundle params) {}
        });
    }

    private String transcript(Bundle results) {
        ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() || values.get(0) == null ? "" : values.get(0);
    }

    // Whether `text` is `previous` with words cut off the end, comparing words loosely.
    private static boolean isShortenedRevision(String previous, String text) {
        List<String> old = looseWords(previous), cut = looseWords(text);
        return !cut.isEmpty() && cut.size() < old.size() && old.subList(0, cut.size()).equals(cut);
    }

    // Words ignoring case and surrounding punctuation, as the Rust side compares them.
    private static List<String> looseWords(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.trim().split("\\s+")) {
            String loose = word.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "").toLowerCase(Locale.ROOT);
            if (!loose.isEmpty()) words.add(loose);
        }
        return words;
    }

    private void commitPending() {
        if (!pending.isEmpty()) {
            event(id, 2, pending, 0);
            pending = "";
        }
    }

    private void nextUtterance() {
        awaitingResult = false;
        event(id, 3, null, 0);
        if (listening) MAIN.postDelayed(restart, 150);
        else finish();
    }

    private void retryTransientError(int error) {
        commitPending();
        if (transientFailures >= MAX_TRANSIENT_RETRIES) {
            fail("The system speech service is still unavailable. Try again in a moment.");
            return;
        }
        ++recognizerGeneration;
        // A client error can mean its Binder connection has died. Recreate
        // that client after releasing the old one, preserving on-device choice.
        if (error == SpeechRecognizer.ERROR_CLIENT) {
            try { recognizer.cancel(); } catch (RuntimeException ignored) {}
            try { recognizer.destroy(); } catch (RuntimeException ignored) {}
            recognizer = null;
        }
        event(id, 3, null, 0);
        MAIN.removeCallbacks(restart);
        // Do not reset this on onReadyForSpeech: a broken service can report
        // ready and fail repeatedly without ever completing an utterance.
        MAIN.postDelayed(restart, 500L << transientFailures++);
    }

    private void finish() {
        if (disposed) return;
        commitPending();
        dispose();
        event(id, 4, null, 0);
    }

    private void fail(String message) {
        fail(message, ERR_OTHER);
    }

    private void fail(String message, int kind) {
        if (disposed) return;
        // Keep the words already shown before reporting the error.
        commitPending();
        dispose();
        event(id, kind, message, 0);
    }

    private void dispose() {
        if (disposed) return;
        disposed = true;
        listening = false;
        MAIN.removeCallbacks(restart);
        MAIN.removeCallbacks(finalTimeout);
        SESSIONS.remove(id);
        if (permissionRequest != null) {
            permissionRequest.cancel();
            permissionRequest = null;
        }
        activity.getApplication().unregisterActivityLifecycleCallbacks(this);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (RuntimeException ignored) {}
            try { recognizer.destroy(); } catch (RuntimeException ignored) {}
            recognizer = null;
        }
    }

    @Override public void onReadyForSpeech(Bundle params) {
        if (disposed || !listening || !awaitingResult) return;
        if (!started) {
            started = true;
            event(id, 0, null, 0);
        }
    }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rms) {
        if (!disposed && listening) event(id, 3, null, Float.isNaN(rms) ? 0 : Math.max(0, Math.min(1, rms / 10.0f)));
    }
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {
        if (!disposed) event(id, 3, null, 0);
    }
    @Override public void onPartialResults(Bundle results) {
        if (disposed || !awaitingResult || results == null) return;
        String text = transcript(results);
        if (!text.isEmpty() && !text.equals(pending)) {
            pending = text;
            event(id, 1, text, 0);
        }
    }
    @Override public void onResults(Bundle results) {
        if (disposed || !awaitingResult) return;
        awaitingResult = false;
        transientFailures = 0;
        String text = results == null ? "" : transcript(results);
        // A final that only cuts words off the end of the last partial keeps them.
        if (!text.isEmpty() && !isShortenedRevision(pending, text)) pending = text;
        commitPending();
        nextUtterance();
    }
    @Override public void onError(int error) {
        if (disposed || !awaitingResult) return;
        awaitingResult = false;
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            transientFailures = 0;
            commitPending();
            nextUtterance();
            return;
        }
        if (!listening) {
            finish();
            return;
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            retryTransientError(error);
            return;
        }
        // On-device recognition may exist without a model for the requested
        // language. Let the regular native service handle it when available.
        if ((error == 12 || error == 13) && preferOnDevice && !triedSystemFallback
                && SpeechRecognizer.isRecognitionAvailable(activity)) {
            triedSystemFallback = true;
            try {
                ++recognizerGeneration;
                recognizer.destroy();
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                installListener();
                commitPending();
                nextUtterance();
                return;
            } catch (RuntimeException ignored) {
                // Continue with the actionable language error below.
            }
        }
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                fail("Allow microphone access in Android settings to use speech input.", ERR_PERMISSION); break;
            case SpeechRecognizer.ERROR_AUDIO:
                fail("The microphone is unavailable. Check whether another app is using it.", ERR_AUDIO); break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                fail("The system speech service needs a network connection. Check your connection and try again."); break;
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED (API 31)
            case 13: // ERROR_LANGUAGE_UNAVAILABLE (API 31)
                fail("The system speech service does not have recognition support for this language.", ERR_LANGUAGE); break;
            default:
                fail("The system speech service stopped (error " + error + "). Try again.");
        }
    }
    @Override public void onEvent(int type, Bundle params) {}
    @Override public void onActivityPaused(Activity other) {
        if (other == activity) finish();
    }
    @Override public void onActivityDestroyed(Activity other) {
        if (other == activity) finish();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}

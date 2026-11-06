//! JNI bridge between `dev.nucleusframework.speech.NativeSpeechBridge` and `robius-speech`.
//!
//! The recognition work (SAPI on Windows, SFSpeechRecognizer on macOS) lives in
//! `robius-speech`; this crate only owns the sessions on behalf of the JVM and
//! forwards their events to Kotlin. Session lifecycle rules (one session at a
//! time, late events dropped) are enforced again on the Kotlin side, which is
//! the source of truth for every platform.

use std::collections::{HashMap, HashSet};
use std::ffi::c_void;
use std::sync::{Mutex, OnceLock};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};
use robius_speech::{
    NativeSpeechEvent, NativeSpeechOptions, NativeSpeechSession, SpeechError, SpeechErrorKind,
};

// Event codes, mirrored by `NativeSpeechBridge` on the Kotlin side.
const EVENT_STARTED: jint = 0;
const EVENT_PARTIAL: jint = 1;
const EVENT_FINAL: jint = 2;
const EVENT_LEVEL: jint = 3;
const EVENT_STOPPED: jint = 4;
const ERROR_OTHER: jint = 5;
const ERROR_PERMISSION: jint = 6;
const ERROR_UNAVAILABLE: jint = 7;
const ERROR_LANGUAGE: jint = 8;
const ERROR_AUDIO: jint = 9;
const ERROR_BUSY: jint = 10;

const REPORTER_CLASS: &str = "dev/nucleusframework/core/runtime/JniExceptionReporter";

static JVM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGE_CLASS: OnceLock<GlobalRef> = OnceLock::new();
static REPORTER: OnceLock<Option<GlobalRef>> = OnceLock::new();
static SESSIONS: Mutex<Option<Sessions>> = Mutex::new(None);

#[derive(Default)]
struct Sessions {
    live: HashMap<i64, NativeSpeechSession>,
    /// Sessions whose terminal event beat `nativeStart` storing them (a backend failing at once).
    ended: HashSet<i64>,
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_8
}

fn with_sessions<R>(f: impl FnOnce(&mut Sessions) -> R) -> R {
    let mut guard = SESSIONS
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    f(guard.get_or_insert_with(Sessions::default))
}

/// Class lookups must happen on a JVM thread: `FindClass` on an attached native
/// thread only sees the bootstrap loader.
fn cache_classes(env: &mut JNIEnv, class: &JClass) {
    if BRIDGE_CLASS.get().is_none() {
        if let Ok(global) = env.new_global_ref(class) {
            let _ = BRIDGE_CLASS.set(global);
        }
    }
    REPORTER.get_or_init(|| {
        let found = env
            .find_class(REPORTER_CLASS)
            .ok()
            .and_then(|reporter| env.new_global_ref(reporter).ok());
        if found.is_none() {
            let _ = env.exception_clear();
        }
        found
    });
}

/// Reports a pending exception through `JniExceptionReporter` (JUL), falling
/// back to `ExceptionDescribe`, so a throwing listener never vanishes silently.
fn clear_exception(env: &mut JNIEnv) {
    if !env.exception_check().unwrap_or(false) {
        return;
    }
    let thrown = env.exception_occurred().ok();
    let _ = env.exception_clear();
    let reported = match (REPORTER.get().and_then(Option::as_ref), thrown.as_ref()) {
        (Some(reporter), Some(thrown)) => env
            .call_static_method(
                reporter,
                "report",
                "(Ljava/lang/Throwable;)V",
                &[JValue::Object(thrown)],
            )
            .is_ok(),
        _ => false,
    };
    if !reported {
        let _ = env.exception_clear();
        if let Some(thrown) = thrown {
            if env.throw(thrown).is_ok() {
                let _ = env.exception_describe();
            }
        }
        let _ = env.exception_clear();
    }
}

fn error_code(error: &SpeechError) -> jint {
    match error.kind() {
        SpeechErrorKind::PermissionDenied => ERROR_PERMISSION,
        SpeechErrorKind::Unavailable => ERROR_UNAVAILABLE,
        SpeechErrorKind::Language => ERROR_LANGUAGE,
        SpeechErrorKind::Audio => ERROR_AUDIO,
        SpeechErrorKind::Busy => ERROR_BUSY,
        _ => ERROR_OTHER,
    }
}

/// Runs on the backend's own thread (SAPI worker on Windows, main queue on macOS).
fn deliver(id: i64, event: NativeSpeechEvent) {
    let (kind, text, level) = match event {
        NativeSpeechEvent::Started => (EVENT_STARTED, None, 0.0),
        NativeSpeechEvent::Transcript { text, is_final } => (
            if is_final { EVENT_FINAL } else { EVENT_PARTIAL },
            Some(text),
            0.0,
        ),
        NativeSpeechEvent::AudioLevel(level) => (EVENT_LEVEL, None, level),
        NativeSpeechEvent::Stopped => (EVENT_STOPPED, None, 0.0),
        NativeSpeechEvent::Error(error) => {
            (error_code(&error), Some(error.message().to_owned()), 0.0)
        }
    };
    if kind == EVENT_STOPPED || kind >= ERROR_OTHER {
        // Terminal: the session is over, release it. Dropped outside the lock,
        // since dropping cancels through the backend.
        let finished = with_sessions(|sessions| {
            let finished = sessions.live.remove(&id);
            if finished.is_none() {
                sessions.ended.insert(id);
            }
            finished
        });
        drop(finished);
    }
    let (Some(vm), Some(bridge)) = (JVM.get(), BRIDGE_CLASS.get()) else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.with_local_frame(8, |env| -> jni::errors::Result<JObject<'_>> {
        let text = match text {
            Some(text) => JObject::from(env.new_string(text)?),
            None => JObject::null(),
        };
        let _ = env.call_static_method(
            bridge,
            "onNativeEvent",
            "(JILjava/lang/String;F)V",
            &[
                JValue::Long(id),
                JValue::Int(kind),
                JValue::Object(&text),
                JValue::Float(level),
            ],
        );
        clear_exception(env);
        Ok(JObject::null())
    });
    clear_exception(&mut env);
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_speech_NativeSpeechBridge_nativeIsSupported(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if NativeSpeechSession::is_supported() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_speech_NativeSpeechBridge_nativeEngineName(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(robius_speech::engine_name())
        .map(|name| name.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Returns null once the session is started, else `"<error code>\n<message>"`.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_speech_NativeSpeechBridge_nativeStart(
    mut env: JNIEnv,
    class: JClass,
    id: jlong,
    locale: JString,
    prefer_on_device: jboolean,
) -> jstring {
    cache_classes(&mut env, &class);
    let locale = if locale.is_null() {
        None
    } else {
        env.get_string(&locale).ok().map(String::from)
    };
    let options = NativeSpeechOptions {
        locale,
        prefer_on_device: prefer_on_device == JNI_TRUE,
    };
    let failure = match NativeSpeechSession::start(options, move |event| deliver(id, event)) {
        Ok(session) => {
            let already_ended = with_sessions(|sessions| {
                if sessions.ended.remove(&id) {
                    Some(session)
                } else {
                    sessions.live.insert(id, session);
                    None
                }
            });
            drop(already_ended);
            return std::ptr::null_mut();
        }
        Err(error) => format!("{}\n{}", error_code(&error), error.message()),
    };
    env.new_string(failure)
        .map(|text| text.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_speech_NativeSpeechBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
    cancel: jboolean,
) {
    if cancel == JNI_TRUE {
        // Dropping the session cancels it and discards whatever is pending.
        let session = with_sessions(|sessions| sessions.live.remove(&id));
        drop(session);
    } else {
        with_sessions(|sessions| {
            if let Some(session) = sessions.live.get(&id) {
                session.stop();
            }
        });
    }
}

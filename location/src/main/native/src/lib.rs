//! Desktop geolocation JNI bridge for `dev.nucleusframework.location`.
//!
//! Modelled on robius-location (Windows `Geolocator`, Core Location, XDG portal / GeoClue), with
//! one difference in the split of responsibilities: this crate only runs *sessions* — a stream of
//! fixes and errors, started and stopped by the Kotlin side — and answers authorization queries.
//! One-shot semantics (cached-first, age filtering, timeouts) and the coroutine / `Flow` surface
//! live in Kotlin, where they are shared with the Android and iOS implementations.

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jdouble, jint, jlong, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};
use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::{Arc, Mutex, OnceLock};

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "windows")]
mod windows;

#[cfg(target_os = "linux")]
use linux as platform;
#[cfg(target_os = "macos")]
use macos as platform;
#[cfg(target_os = "windows")]
use windows as platform;

#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
mod platform {
    use super::{AuthCallback, ErrorKind, SessionOptions, Sink};
    use std::sync::Arc;

    pub struct Session;

    pub fn is_available() -> bool {
        false
    }

    pub fn authorization_status() -> i32 {
        super::AUTH_RESTRICTED
    }

    pub fn request_authorization(_: bool, _: bool, _: String, done: AuthCallback) {
        done(super::AUTH_RESTRICTED)
    }

    pub fn start(_: SessionOptions, _: Arc<dyn Sink>) -> Result<Session, (ErrorKind, String)> {
        Err((ErrorKind::PermanentlyUnavailable, "unsupported platform".into()))
    }
}

// Mirrors `NativeLocationBridge` on the Kotlin side.
pub(crate) const AUTH_NOT_DETERMINED: i32 = 0;
pub(crate) const AUTH_DENIED: i32 = 1;
#[cfg_attr(target_os = "linux", allow(dead_code))]
pub(crate) const AUTH_RESTRICTED: i32 = 2;
pub(crate) const AUTH_FOREGROUND: i32 = 3;
pub(crate) const AUTH_BACKGROUND: i32 = 4;

/// Error categories, mirroring `LocationError` on the Kotlin side.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub(crate) enum ErrorKind {
    AuthorizationDenied = 1,
    TemporarilyUnavailable = 2,
    PermanentlyUnavailable = 3,
    #[cfg_attr(target_os = "windows", allow(dead_code))]
    Network = 4,
    Unknown = 5,
}

/// One position fix. Optional fields are `None` when the platform does not report them.
#[derive(Clone, Debug, Default)]
pub(crate) struct Fix {
    pub latitude: f64,
    pub longitude: f64,
    pub altitude: Option<f64>,
    pub horizontal_accuracy: Option<f64>,
    pub vertical_accuracy: Option<f64>,
    pub bearing: Option<f64>,
    pub speed: Option<f64>,
    /// Wall-clock time of the fix, milliseconds since the Unix epoch.
    pub time_millis: i64,
    /// Whether the platform handed back a previously acquired fix rather than a new one.
    pub cached: bool,
}

/// What a session was started for.
#[derive(Clone, Debug)]
pub(crate) struct SessionOptions {
    pub precise: bool,
    /// `false` for a one-shot: the platform's cached fix (if any) then one fresh fix.
    pub continuous: bool,
    #[cfg_attr(target_os = "macos", allow(dead_code))]
    pub interval_millis: u64,
    pub distance_meters: f64,
    /// Upper bound on the age of a cached fix worth asking the platform for; 0 = none.
    #[cfg_attr(target_os = "linux", allow(dead_code))]
    pub max_cached_age_millis: u64,
    /// Linux only: the `.desktop` id GeoClue attributes the request to.
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    pub desktop_id: String,
}

/// Receives what a session produces. Implementations must be cheap: they are called from the
/// platform's own callback threads.
pub(crate) trait Sink: Send + Sync {
    fn location(&self, fix: Fix);
    fn error(&self, kind: ErrorKind, message: &str);
}

pub(crate) type AuthCallback = Box<dyn FnOnce(i32) + Send>;

// ---------------------------------------------------------------------------------------------
// JNI plumbing
// ---------------------------------------------------------------------------------------------

static JVM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGE_CLASS: OnceLock<GlobalRef> = OnceLock::new();
/// Live sessions. An entry is reserved (`None`) before the platform starts, so a stop that races
/// the start — a one-shot can finish before `nativeStart` returns — removes the reservation and
/// the start then drops its session instead of leaking it.
static SESSIONS: OnceLock<Mutex<HashMap<i64, Option<platform::Session>>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<i64, Option<platform::Session>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_8
}

fn cache_bridge_class(env: &mut JNIEnv, class: &JClass) {
    if BRIDGE_CLASS.get().is_some() {
        return;
    }
    if let Ok(global) = env.new_global_ref(class) {
        let _ = BRIDGE_CLASS.set(global);
    }
}

/// Runs `f` with a JNIEnv for the current thread, attaching it as a daemon if needed. Platform
/// callback threads (the WinRT thread pool, the Core Location run loop, the D-Bus readers) live
/// as long as the process, so a permanent attachment is the cheap choice.
fn with_env(f: impl FnOnce(&mut JNIEnv, &JClass)) {
    let (Some(vm), Some(class)) = (JVM.get(), BRIDGE_CLASS.get()) else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.with_local_frame(16, |env| -> jni::errors::Result<JObject<'_>> {
        f(env, <&JClass>::from(class.as_obj()));
        report_pending_exception(env);
        Ok(JObject::null())
    });
}

/// Hands a pending exception to `JniExceptionReporter` instead of dropping it silently — the
/// Rust counterpart of `nucleus_jni_clear_exception` in `native-common/nucleus_jni.h`.
fn report_pending_exception(env: &mut JNIEnv) {
    if !env.exception_check().unwrap_or(false) {
        return;
    }
    let Ok(thrown) = env.exception_occurred() else {
        let _ = env.exception_clear();
        return;
    };
    let _ = env.exception_clear();
    let reported = env
        .call_static_method(
            "dev/nucleusframework/core/runtime/JniExceptionReporter",
            "report",
            "(Ljava/lang/Throwable;)V",
            &[JValue::Object(&thrown)],
        )
        .is_ok();
    if !reported || env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
        let _ = env.throw(thrown);
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

fn opt(value: Option<f64>) -> f64 {
    value.filter(|v| v.is_finite()).unwrap_or(f64::NAN)
}

/// Forwards one session's output to `NativeLocationBridge.onLocation` / `onError`.
struct JniSink {
    session_id: i64,
}

impl Sink for JniSink {
    fn location(&self, fix: Fix) {
        with_env(|env, class| {
            let _ = env.call_static_method(
                class,
                "onLocation",
                "(JDDDDDDDJZ)V",
                &[
                    JValue::Long(self.session_id),
                    JValue::Double(fix.latitude),
                    JValue::Double(fix.longitude),
                    JValue::Double(opt(fix.altitude)),
                    JValue::Double(opt(fix.horizontal_accuracy)),
                    JValue::Double(opt(fix.vertical_accuracy)),
                    JValue::Double(opt(fix.bearing)),
                    JValue::Double(opt(fix.speed)),
                    JValue::Long(fix.time_millis),
                    JValue::Bool(if fix.cached { JNI_TRUE } else { JNI_FALSE }),
                ],
            );
        });
    }

    fn error(&self, kind: ErrorKind, message: &str) {
        emit_error(self.session_id, kind, message);
    }
}

fn emit_error(session_id: i64, kind: ErrorKind, message: &str) {
    with_env(|env, class| {
        let Ok(message) = env.new_string(message) else {
            return;
        };
        let _ = env.call_static_method(
            class,
            "onError",
            "(JILjava/lang/String;)V",
            &[
                JValue::Long(session_id),
                JValue::Int(kind as i32),
                JValue::Object(&JObject::from(message)),
            ],
        );
    });
}

fn emit_authorization(request_id: i64, status: i32) {
    with_env(|env, class| {
        let _ = env.call_static_method(
            class,
            "onAuthorization",
            "(JI)V",
            &[JValue::Long(request_id), JValue::Int(status)],
        );
    });
}

fn java_string(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value).map(Into::into).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_location_NativeLocationBridge_nativeIsAvailable(
    mut env: JNIEnv,
    class: JClass,
) -> jboolean {
    cache_bridge_class(&mut env, &class);
    if platform::is_available() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_location_NativeLocationBridge_nativeAuthorizationStatus(
    mut env: JNIEnv,
    class: JClass,
) -> jint {
    cache_bridge_class(&mut env, &class);
    platform::authorization_status()
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_location_NativeLocationBridge_nativeRequestAuthorization(
    mut env: JNIEnv,
    class: JClass,
    request_id: jlong,
    background: jboolean,
    precise: jboolean,
    desktop_id: JString,
) {
    cache_bridge_class(&mut env, &class);
    let desktop_id = java_string(&mut env, &desktop_id);
    platform::request_authorization(
        background == JNI_TRUE,
        precise == JNI_TRUE,
        desktop_id,
        Box::new(move |status| emit_authorization(request_id, status)),
    );
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_nucleusframework_location_NativeLocationBridge_nativeStart(
    mut env: JNIEnv,
    class: JClass,
    session_id: jlong,
    precise: jboolean,
    continuous: jboolean,
    interval_millis: jlong,
    distance_meters: jdouble,
    max_cached_age_millis: jlong,
    desktop_id: JString,
) {
    cache_bridge_class(&mut env, &class);
    let options = SessionOptions {
        precise: precise == JNI_TRUE,
        continuous: continuous == JNI_TRUE,
        interval_millis: interval_millis.max(0) as u64,
        distance_meters: if distance_meters.is_finite() { distance_meters.max(0.0) } else { 0.0 },
        max_cached_age_millis: max_cached_age_millis.max(0) as u64,
        desktop_id: java_string(&mut env, &desktop_id),
    };
    if let Ok(mut sessions) = sessions().lock() {
        sessions.insert(session_id, None);
    }
    match platform::start(options, Arc::new(JniSink { session_id })) {
        Ok(session) => {
            let orphan = match sessions().lock() {
                Ok(mut sessions) => match sessions.get_mut(&session_id) {
                    Some(slot) => {
                        *slot = Some(session);
                        None
                    }
                    None => Some(session),
                },
                Err(_) => Some(session),
            };
            // Stopped while starting; dropped outside the lock since stopping may block.
            drop(orphan);
        }
        Err((kind, message)) => {
            if let Ok(mut sessions) = sessions().lock() {
                sessions.remove(&session_id);
            }
            emit_error(session_id, kind, &message);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_location_NativeLocationBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) {
    // Dropped outside the lock: stopping may join a platform thread.
    let session = sessions().lock().ok().and_then(|mut sessions| sessions.remove(&session_id)).flatten();
    drop(session);
}

// ---------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------

#[cfg_attr(target_os = "macos", allow(dead_code))]
pub(crate) fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// A coordinate pair the platform could plausibly have measured.
pub(crate) fn valid_coordinates(latitude: f64, longitude: f64) -> bool {
    latitude.is_finite()
        && longitude.is_finite()
        && (-90.0..=90.0).contains(&latitude)
        && (-180.0..=180.0).contains(&longitude)
}

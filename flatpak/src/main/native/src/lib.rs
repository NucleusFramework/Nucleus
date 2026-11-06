//! JNI bridge of `dev.nucleusframework.flatpak`: the Flatpak portal seen from inside the sandbox
//! (update monitors, `Update`, `Spawn`) and the OpenURI portal. Linux only.
//!
//! Every call returns `null` on success or `"<D-Bus error name>\n<message>"`; the Kotlin side
//! owns the ids, the validation and the mapping of errors. Monitor signals go to
//! `NativeFlatpakBridge.onUpdateAvailable` / `onProgress` / `onMonitorClosed` with the monitor id.

use jni::objects::{GlobalRef, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::{jint, jlong, jstring, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};
use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::{Mutex, OnceLock};

mod portal;

static JVM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGE_CLASS: OnceLock<GlobalRef> = OnceLock::new();
static MONITORS: OnceLock<Mutex<HashMap<i64, portal::Monitor>>> = OnceLock::new();

fn monitors() -> &'static Mutex<HashMap<i64, portal::Monitor>> {
    MONITORS.get_or_init(|| Mutex::new(HashMap::new()))
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

/// Runs `f` with a JNIEnv for the current thread. The monitor reader threads end with their
/// monitor, so each callback attaches with a scoped guard and detaches on return: a thread that
/// exits attached as a daemon leaves a dangling JavaThread behind.
fn with_env(f: impl FnOnce(&mut JNIEnv, &JClass)) {
    let (Some(vm), Some(class)) = (JVM.get(), BRIDGE_CLASS.get()) else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread() else {
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

fn java_string(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value).map(String::from).unwrap_or_default()
}

fn result_string(env: &mut JNIEnv, result: Result<(), portal::PortalError>) -> jstring {
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(error) => env
            .new_string(format!("{}\n{}", error.name, error.message))
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

fn not_started(id: i64) -> portal::PortalError {
    portal::PortalError { name: portal::ERROR_BUS.to_string(), message: format!("no update monitor {id}") }
}

/// Forwards one monitor's signals to the Kotlin bridge.
struct JniEvents {
    id: i64,
}

impl portal::Events for JniEvents {
    fn update_available(&self, running: &str, local: &str, remote: &str) {
        let id = self.id;
        with_env(|env, class| {
            let (Ok(running), Ok(local), Ok(remote)) =
                (env.new_string(running), env.new_string(local), env.new_string(remote))
            else {
                return;
            };
            let _ = env.call_static_method(
                class,
                "onUpdateAvailable",
                "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Long(id), JValue::Object(&running), JValue::Object(&local), JValue::Object(&remote)],
            );
        });
    }

    fn progress(&self, progress: portal::Progress) {
        let id = self.id;
        with_env(|env, class| {
            let error = match progress.error.as_deref() {
                Some(e) => env.new_string(e).map(JObject::from).unwrap_or(JObject::null()),
                None => JObject::null(),
            };
            let message = match progress.error_message.as_deref() {
                Some(m) => env.new_string(m).map(JObject::from).unwrap_or(JObject::null()),
                None => JObject::null(),
            };
            let _ = env.call_static_method(
                class,
                "onProgress",
                "(JIIIILjava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Long(id),
                    JValue::Int(progress.n_ops as i32),
                    JValue::Int(progress.op as i32),
                    JValue::Int(progress.progress as i32),
                    JValue::Int(progress.status as i32),
                    JValue::Object(&error),
                    JValue::Object(&message),
                ],
            );
        });
    }

    fn closed(&self, reason: &str) {
        let id = self.id;
        with_env(|env, class| {
            let Ok(reason) = env.new_string(reason) else { return };
            let _ = env.call_static_method(
                class,
                "onMonitorClosed",
                "(JLjava/lang/String;)V",
                &[JValue::Long(id), JValue::Object(&reason)],
            );
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_flatpak_internal_NativeFlatpakBridge_nativePortalVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    portal::portal_version() as jint
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_flatpak_internal_NativeFlatpakBridge_nativeStartMonitor(
    mut env: JNIEnv,
    class: JClass,
    id: jlong,
) -> jstring {
    cache_bridge_class(&mut env, &class);
    let result = portal::Monitor::start(JniEvents { id }).map(|monitor| {
        if let Ok(mut monitors) = monitors().lock() {
            monitors.insert(id, monitor);
        }
    });
    result_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_flatpak_internal_NativeFlatpakBridge_nativeUpdate(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    parent_window: JString,
) -> jstring {
    let parent_window = java_string(&mut env, &parent_window);
    let result = match monitors().lock() {
        Ok(monitors) => match monitors.get(&id) {
            Some(monitor) => monitor.update(&parent_window),
            None => Err(not_started(id)),
        },
        Err(_) => Err(not_started(id)),
    };
    result_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_flatpak_internal_NativeFlatpakBridge_nativeCloseMonitor(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    let monitor = monitors().lock().ok().and_then(|mut monitors| monitors.remove(&id));
    if let Some(monitor) = monitor {
        monitor.close();
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_flatpak_internal_NativeFlatpakBridge_nativeSpawnLatest(
    mut env: JNIEnv,
    _class: JClass,
    cwd: JString,
    argv: JObjectArray,
) -> jstring {
    let cwd = java_string(&mut env, &cwd);
    let length = env.get_array_length(&argv).unwrap_or(0);
    let mut args = Vec::with_capacity(length as usize);
    for index in 0..length {
        let Ok(element) = env.get_object_array_element(&argv, index) else { continue };
        let arg = JString::from(element);
        args.push(java_string(&mut env, &arg));
    }
    let result = portal::spawn_latest(&cwd, &args).map(|_| ());
    result_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_flatpak_internal_NativeFlatpakBridge_nativeOpenUri(
    mut env: JNIEnv,
    _class: JClass,
    parent_window: JString,
    uri: JString,
) -> jstring {
    let parent_window = java_string(&mut env, &parent_window);
    let uri = java_string(&mut env, &uri);
    let result = portal::open_uri(&parent_window, &uri);
    result_string(&mut env, result)
}

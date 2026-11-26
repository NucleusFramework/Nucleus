//! JNI bridge behind `dev.nucleusframework.authentication.Authenticator` on desktop.
//!
//! The per-platform strategies follow `robius-authentication`
//! (https://github.com/project-robius/robius/tree/main/crates/authentication, MIT):
//! macOS goes through LocalAuthentication (`LAContext`), Windows through Windows Hello
//! (`UserConsentVerifier`) with a CredUI password fallback, Linux through polkit.
//! Unlike robius, every request can be cancelled by the caller, Windows parents the
//! prompt to the caller's window, and Linux talks to polkit over a hand-rolled D-Bus
//! client. Validation happens on the Kotlin side; this crate only maps and presents.

// Each platform reads a different subset of the prompt and error set.
#![allow(dead_code)]

mod error;
mod platform;

use std::ffi::c_void;
use std::sync::OnceLock;

use jni::objects::{GlobalRef, JClass, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jint, jlong, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};

pub(crate) use error::{Error, Failure, Result};

/// Which authentication methods satisfy a request, mirrored from `AuthenticationPolicy`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Policy {
    /// Biometrics, or the device credential (password / PIN) as a fallback.
    DeviceOwner,
    /// Biometrics only.
    Biometrics,
}

impl Policy {
    fn from_code(code: jint) -> Option<Self> {
        match code {
            0 => Some(Policy::DeviceOwner),
            1 => Some(Policy::Biometrics),
            _ => None,
        }
    }
}

/// The biometric sensor a device offers, mirrored from `BiometryType` (by ordinal).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Biometry {
    None = 0,
    Fingerprint = 1,
    Face = 2,
    Iris = 3,
    /// Biometrics may be present, but the platform does not say which.
    Unknown = 4,
}

/// The text of a prompt, mirrored from `AuthenticationPrompt`.
#[derive(Clone, Debug, Default)]
pub(crate) struct Prompt {
    pub(crate) reason: String,
    pub(crate) title: Option<String>,
    pub(crate) cancel_label: Option<String>,
    pub(crate) linux_action_id: Option<String>,
}

/// Receives the outcome of one request, exactly once, on any thread.
pub(crate) type Done = Box<dyn FnOnce(Result<()>) + Send>;

static JVM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGE_CLASS: OnceLock<GlobalRef> = OnceLock::new();

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_8
}

/// Probes whether [policy] can be evaluated, without any UI. Writes the biometry
/// ordinal into `out[0]` and returns `0` when available, otherwise an [Error] code with
/// a human-readable message in `message[0]`. May block (Windows awaits WinRT).
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_authentication_NativeAuthenticationBridge_nativeAvailability<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    policy: jint,
    out: JIntArray<'local>,
    message: JObjectArray<'local>,
) -> jint {
    let Some(policy) = Policy::from_code(policy) else {
        return report(&mut env, &message, Failure::new(Error::Platform, "unknown policy"));
    };
    let (biometry, result) = platform::availability(policy);
    let _ = env.set_int_array_region(&out, 0, &[biometry as jint]);
    match result {
        Ok(()) => 0,
        Err(failure) => report(&mut env, &message, failure),
    }
}

/// Starts request [request_id]. Returns `0` once the prompt is (or will be) shown, the
/// outcome then arriving through `NativeAuthenticationBridge.onResult`; otherwise an
/// [Error] code with a message in `message[0]`, and no callback follows.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_nucleusframework_authentication_NativeAuthenticationBridge_nativeAuthenticate<
    'local,
>(
    mut env: JNIEnv<'local>,
    class: JClass<'local>,
    request_id: jlong,
    policy: jint,
    reason: JString<'local>,
    title: JString<'local>,
    cancel_label: JString<'local>,
    linux_action_id: JString<'local>,
    parent_handle: jlong,
    message: JObjectArray<'local>,
) -> jint {
    cache_bridge_class(&mut env, &class);
    let result = Policy::from_code(policy)
        .ok_or_else(|| Failure::new(Error::Platform, "unknown policy"))
        .and_then(|policy| {
            let prompt = Prompt {
                reason: optional_string(&mut env, &reason)?.unwrap_or_default(),
                title: optional_string(&mut env, &title)?,
                cancel_label: optional_string(&mut env, &cancel_label)?,
                linux_action_id: optional_string(&mut env, &linux_action_id)?,
            };
            let done: Done = Box::new(move |result| emit_result(request_id, result));
            platform::authenticate(request_id, policy, prompt, parent_handle, done)
        });
    match result {
        Ok(()) => 0,
        Err(failure) => report(&mut env, &message, failure),
    }
}

/// Dismisses request [request_id] if it is still on screen. Its outcome is still
/// delivered (as `AppCanceled` unless the user answered first).
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_authentication_NativeAuthenticationBridge_nativeCancel<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    request_id: jlong,
) {
    platform::cancel(request_id);
}

fn cache_bridge_class(env: &mut JNIEnv, class: &JClass) {
    if BRIDGE_CLASS.get().is_some() {
        return;
    }
    if let Ok(global) = env.new_global_ref(class) {
        let _ = BRIDGE_CLASS.set(global);
    }
}

fn report(env: &mut JNIEnv, message: &JObjectArray, failure: Failure) -> jint {
    if let Ok(text) = env.new_string(failure.to_string()) {
        let _ = env.set_object_array_element(message, 0, text);
    }
    failure.kind.code()
}

/// Calls `NativeAuthenticationBridge.onResult(requestId, code, message)` from whatever
/// thread the platform answered on. Those threads (the LocalAuthentication queue, the
/// per-request Windows and Linux threads) are attached for the call only.
fn emit_result(request_id: i64, result: Result<()>) {
    let (Some(vm), Some(class)) = (JVM.get(), BRIDGE_CLASS.get()) else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread() else {
        return;
    };
    let (code, text) = match result {
        Ok(()) => (0, None),
        Err(failure) => (failure.kind.code(), Some(failure.to_string())),
    };
    let _ = env.with_local_frame(8, |env| -> jni::errors::Result<JObject<'_>> {
        let text = match text {
            Some(text) => JObject::from(env.new_string(text)?),
            None => JObject::null(),
        };
        let _ = env.call_static_method(
            <&JClass>::from(class.as_obj()),
            "onResult",
            "(JILjava/lang/String;)V",
            &[JValue::Long(request_id), JValue::Int(code), JValue::Object(&text)],
        );
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

fn optional_string(env: &mut JNIEnv, string: &JString) -> Result<Option<String>> {
    if string.is_null() {
        return Ok(None);
    }
    let value: String = env
        .get_string(string)
        .map_err(|error| Failure::new(Error::Platform, format!("JNI: {error}")))?
        .into();
    Ok(Some(value))
}

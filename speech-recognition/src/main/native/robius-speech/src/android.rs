//! Android dictation, bridged to the system `SpeechRecognizer`.
//!
//! The Java side in `java/dev/robius/speech/` does the real work, including the
//! runtime permission request; this module just loads it and calls into it. That
//! Java is compiled to DEX and embedded in the library, so an app doesn't need to
//! add anything to its own Activity.

use std::sync::{Mutex, OnceLock};
use jni::{JNIEnv, NativeMethod, objects::{GlobalRef, JClass, JObject, JString, JValue}, sys::{jfloat, jint, jlong}};
use crate::{codes, error_kind_from_code, NativeSpeechEvent, NativeSpeechOptions, SpeechError, SpeechErrorKind};

static BRIDGE: OnceLock<Mutex<Option<GlobalRef>>> = OnceLock::new();

fn with_env<T>(run: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T, SpeechError> {
    // The environment provider selects the host toolkit's current Activity.
    // ndk-context can panic before initialization; report unavailable instead
    // of unwinding through a caller merely checking for speech support.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        robius_android_env::with_activity(|env, activity| {
            let result = env.with_local_frame(32, |env| run(env, activity));
            if env.exception_check().unwrap_or(false) {
                // Never leave an exception pending on the application's thread.
                let _ = env.exception_clear();
            }
            result
        })
    })).map_err(|_| SpeechError::new(
        SpeechErrorKind::Unavailable,
        "Unable to access the Android application context. Initialize it before using speech recognition.",
    ))?;
    result.and_then(|inner| inner).map_err(|error| SpeechError::new(
        SpeechErrorKind::Unavailable,
        format!("Unable to access Android speech recognition: {error}"),
    ))
}

pub(super) const ENGINE: &str = "android-speechrecognizer";

fn bridge(env: &mut JNIEnv, activity: &JObject) -> jni::errors::Result<GlobalRef> {
    let mut cached = BRIDGE.get_or_init(|| Mutex::new(None)).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some(class) = cached.as_ref() {
        return Ok(class.clone());
    }
    if activity.is_null() {
        return Err(jni::errors::Error::NullPtr("Android Activity"));
    }
    // Borrow the current Activity without caching it: Android can replace it
    // after rotation or recreation. Only the loaded bridge class is retained.
    let parent = env.call_method(activity, "getClassLoader", "()Ljava/lang/ClassLoader;", &[])?.l()?;
    let dex = env.byte_array_from_slice(include_bytes!(concat!(env!("OUT_DIR"), "/classes.dex")))?;
    let bytes = env.call_static_method("java/nio/ByteBuffer", "wrap", "([B)Ljava/nio/ByteBuffer;", &[JValue::Object(&dex)])?.l()?;
    let loader = env.new_object("dalvik/system/InMemoryDexClassLoader", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V",
        &[JValue::Object(&bytes), JValue::Object(&parent)])?;
    let name = env.new_string("dev.robius.speech.NativeSpeech")?;
    let class = JClass::from(env.call_method(loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", &[JValue::Object(&name)])?.l()?);
    env.register_native_methods(&class, &[NativeMethod {
        name: "event".into(),
        sig: "(JILjava/lang/String;F)V".into(),
        fn_ptr: native_event as *mut std::ffi::c_void,
    }])?;
    let global = env.new_global_ref(class)?;
    *cached = Some(global.clone());
    Ok(global)
}

extern "system" fn native_event(mut env: JNIEnv, _: JClass, id: jlong, kind: jint, text: JString, level: jfloat) {
    // Java invokes this on the main thread. Decoding/callback failures must not
    // unwind through JNI, and app callbacks are isolated by crate::emit as well.
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let text = if text.is_null() { String::new() }
            else { env.get_string(&text).map(String::from).unwrap_or_default() };
        let event = match kind {
            codes::STARTED => NativeSpeechEvent::Started,
            codes::PARTIAL | codes::FINAL => NativeSpeechEvent::Transcript { text, is_final: kind == codes::FINAL },
            codes::LEVEL => NativeSpeechEvent::AudioLevel(if level.is_finite() { level.clamp(0.0, 1.0) } else { 0.0 }),
            codes::STOPPED => NativeSpeechEvent::Stopped,
            codes::ERROR..=codes::ERROR_AUDIO => {
                NativeSpeechEvent::Error(SpeechError::new(error_kind_from_code(kind), text))
            }
            _ => return,
        };
        crate::emit(id as u64, event);
    }));
    if env.exception_check().unwrap_or(false) { let _ = env.exception_clear(); }
}

pub(super) fn is_supported() -> bool {
    with_env(|env, activity| {
        if activity.is_null() { return Ok(false); }
        let class = bridge(env, activity)?;
        let class: &JClass = class.as_obj().into();
        env.call_static_method(class, "supported", "(Landroid/app/Activity;)Z", &[JValue::Object(activity)])?.z()
    }).unwrap_or(false)
}

pub(super) fn start(id: u64, options: &NativeSpeechOptions) -> Result<(), SpeechError> {
    with_env(|env, activity| {
        if activity.is_null() { return Err(jni::errors::Error::NullPtr("Android Activity")); }
        let class = bridge(env, activity)?;
        let class: &JClass = class.as_obj().into();
        let locale = env.new_string(options.locale.as_deref().unwrap_or(""))?;
        env.call_static_method(class, "start", "(Landroid/app/Activity;JLjava/lang/String;Z)V", &[
            JValue::Object(activity), JValue::Long(id as jlong), JValue::Object(&locale),
            JValue::Bool(options.prefer_on_device.into()),
        ])?;
        Ok(())
    })
}

pub(super) fn stop(id: u64, cancel: bool) {
    let result = with_env(|env, activity| {
        let class = bridge(env, activity)?;
        let class: &JClass = class.as_obj().into();
        env.call_static_method(class, "stop", "(JZ)V", &[JValue::Long(id as jlong), JValue::Bool(cancel.into())])?;
        Ok(())
    });
    if let Err(error) = result {
        crate::emit(id, NativeSpeechEvent::Error(error));
    }
}

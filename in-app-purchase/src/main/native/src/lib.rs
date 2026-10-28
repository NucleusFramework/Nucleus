//! JNI bridge of `in-app-purchase` on Windows: `NativeIapBridge.nativeRequest(id, method,
//! argsJson)` runs `method` on a worker thread and answers once through
//! `NativeIapBridge.onResponse(id, envelopeJson)`; out-of-band Store events go through
//! `NativeIapBridge.onEvent(json)`. See `protocol.rs` for the payloads.

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use jni::objects::{GlobalRef, JClass, JString, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};
use serde_json::{json, Value};

pub mod protocol;
pub mod requests;
#[cfg(windows)]
mod windows_store;

use protocol::{error_envelope, ok_envelope, StoreError, StoreResult};
use requests::Request;

static JVM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGE_CLASS: OnceLock<GlobalRef> = OnceLock::new();

/// A response or event sink: the JVM in production, a closure in the Rust tests.
pub trait Sink: Send + Sync {
    fn respond(&self, id: i64, envelope: String);
    fn event(&self, json: String);
}

struct JvmSink;

impl JvmSink {
    fn call(&self, method: &str, signature: &str, id: Option<i64>, payload: &str) {
        let (Some(vm), Some(class)) = (JVM.get(), BRIDGE_CLASS.get()) else {
            return;
        };
        // Workers are short-lived, so they must detach before exiting: a thread that exits
        // attached (`attach_current_thread_as_daemon`) leaves a dangling JavaThread that
        // crashes the VM later (0xC0000005, no hs_err), and detaching from a TLS destructor
        // (`attach_current_thread_permanently`) deadlocks on the loader lock. The guard
        // detaches when this call returns — and leaves a thread the JVM owns attached.
        let Ok(mut env) = vm.attach_current_thread() else {
            return;
        };
        let _ = env.with_local_frame(8, |env| -> jni::errors::Result<()> {
            let text = env.new_string(payload)?;
            let result = match id {
                Some(id) => env.call_static_method(
                    class,
                    method,
                    signature,
                    &[JValue::Long(id), JValue::Object(&text)],
                ),
                None => env.call_static_method(class, method, signature, &[JValue::Object(&text)]),
            };
            if result.is_err() && env.exception_check().unwrap_or(false) {
                // The Kotlin callbacks catch everything themselves; anything reaching here is
                // a bug worth a stack trace rather than a silently cleared exception.
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
            Ok(())
        });
    }
}

impl Sink for JvmSink {
    fn respond(&self, id: i64, envelope: String) {
        self.call("onResponse", "(JLjava/lang/String;)V", Some(id), &envelope);
    }

    fn event(&self, json: String) {
        self.call("onEvent", "(Ljava/lang/String;)V", None, &json);
    }
}

fn sink() -> &'static dyn Sink {
    static SINK: JvmSink = JvmSink;
    &SINK
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let _ = JVM.set(vm);
    install_panic_hook();
    JNI_VERSION_1_8
}

thread_local! {
    static LAST_PANIC: std::cell::RefCell<Option<String>> = const { std::cell::RefCell::new(None) };
}

/// Every panic is caught and answered as an `internal` error; the default hook would also print
/// it to the app's stderr. This one keeps the message and location for that answer instead.
/// The hook is this library's own: every cdylib links its own copy of `std`.
fn install_panic_hook() {
    static INSTALLED: std::sync::Once = std::sync::Once::new();
    INSTALLED.call_once(|| {
        std::panic::set_hook(Box::new(|info| {
            LAST_PANIC.with(|last| *last.borrow_mut() = Some(info.to_string()));
        }));
    });
}

fn cache_bridge_class(env: &mut JNIEnv, class: &JClass) {
    if BRIDGE_CLASS.get().is_none() {
        if let Ok(global) = env.new_global_ref(class) {
            let _ = BRIDGE_CLASS.set(global);
        }
    }
}

/// Starts the out-of-band listener (Windows: `StoreContext.OfflineLicensesChanged`).
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_iap_internal_NativeIapBridge_nativeInit(
    mut env: JNIEnv,
    class: JClass,
) -> jboolean {
    cache_bridge_class(&mut env, &class);
    let started = catch_unwind(|| {
        #[cfg(windows)]
        {
            windows_store::start_listener(sink())
        }
        #[cfg(not(windows))]
        {
            false
        }
    });
    if started.unwrap_or(false) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_iap_internal_NativeIapBridge_nativeRequest(
    mut env: JNIEnv,
    class: JClass,
    id: jlong,
    method: JString,
    args: JString,
) {
    cache_bridge_class(&mut env, &class);
    let method: Option<String> = env.get_string(&method).ok().map(Into::into);
    let args: Option<String> = env.get_string(&args).ok().map(Into::into);
    match (method, args) {
        (Some(method), Some(args)) => submit(sink(), id, method, args),
        _ => sink().respond(
            id,
            error_envelope(&StoreError::new(
                "invalidArgument",
                "method and arguments must be non-null strings",
            )),
        ),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_iap_internal_NativeIapBridge_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    requests::cancel(id);
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_iap_internal_NativeIapBridge_nativeInFlight(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    jint::try_from(requests::in_flight()).unwrap_or(jint::MAX)
}

/// Runs `method` on a worker thread and answers `id` exactly once, whatever happens —
/// including a duplicate id, a thread that cannot be spawned, or a panic.
pub fn submit(sink: &'static dyn Sink, id: i64, method: String, args: String) {
    let Some(request) = requests::begin(id) else {
        sink.respond(
            id,
            error_envelope(&StoreError::new(
                "invalidArgument",
                format!("request {id} is already in flight"),
            )),
        );
        return;
    };
    let worker = request.clone();
    let spawned = std::thread::Builder::new()
        .name(format!("nucleus-iap-{id}"))
        .spawn(move || {
            let envelope = run(&worker, &method, &args);
            requests::finish(worker.id);
            sink.respond(worker.id, envelope);
        });
    if let Err(e) = spawned {
        requests::finish(id);
        sink.respond(
            id,
            error_envelope(&StoreError::new(
                "internal",
                format!("cannot start a worker thread: {e}"),
            )),
        );
    }
}

fn run(request: &Arc<Request>, method: &str, args: &str) -> String {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> StoreResult<Value> {
        request.check_cancelled()?;
        let args: Value = serde_json::from_str(args).map_err(|e| {
            StoreError::new("invalidArgument", format!("arguments are not JSON: {e}"))
        })?;
        dispatch(request, method, &args)
    }));
    match outcome {
        Ok(Ok(payload)) => ok_envelope(payload),
        Ok(Err(error)) => error_envelope(&error),
        Err(panic) => {
            let detail = LAST_PANIC
                .with(|last| last.borrow_mut().take())
                .or_else(|| panic.downcast_ref::<&str>().map(|s| (*s).to_owned()))
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_owned());
            error_envelope(&StoreError::new(
                "internal",
                format!("native panic in {method}: {detail}"),
            ))
        }
    }
}

fn dispatch(request: &Request, method: &str, args: &Value) -> StoreResult<Value> {
    match method {
        "diag.echo" => Ok(args.clone()),
        "diag.delay" => {
            let millis = args["millis"].as_u64().unwrap_or(0);
            if request.sleep(Duration::from_millis(millis)) {
                Ok(args["value"].clone())
            } else {
                Err(requests::cancelled_error())
            }
        }
        "diag.fail" => Err(StoreError {
            code: "internal",
            message: args["message"].as_str().unwrap_or("diag.fail").to_owned(),
            hresult: args["hresult"].as_i64().and_then(|h| i32::try_from(h).ok()),
        }),
        "diag.panic" => panic!("{}", args["message"].as_str().unwrap_or("diag.panic")),
        "diag.info" => Ok(json!({ "backend": backend_name(), "inFlight": requests::in_flight() })),
        #[cfg(windows)]
        _ => windows_store::dispatch(request, method, args),
        #[cfg(not(windows))]
        _ => Err(StoreError::new(
            "unsupported",
            format!("{method} is not available on this platform"),
        )),
    }
}

fn backend_name() -> &'static str {
    if cfg!(windows) {
        "windows.services.store"
    } else {
        "none"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::{Condvar, Mutex};

    #[derive(Default)]
    struct Recorder {
        responses: Mutex<HashMap<i64, Vec<String>>>,
        ready: Condvar,
    }

    impl Sink for Recorder {
        fn respond(&self, id: i64, envelope: String) {
            self.responses
                .lock()
                .unwrap()
                .entry(id)
                .or_default()
                .push(envelope);
            self.ready.notify_all();
        }
        fn event(&self, _json: String) {}
    }

    fn recorder() -> &'static Recorder {
        Box::leak(Box::default())
    }

    fn wait_for(recorder: &Recorder, count: usize) -> HashMap<i64, Vec<String>> {
        let mut map = recorder.responses.lock().unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        while map.values().map(Vec::len).sum::<usize>() < count {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            assert!(!left.is_zero(), "timed out waiting for {count} responses");
            map = recorder.ready.wait_timeout(map, left).unwrap().0;
        }
        map.clone()
    }

    fn parse(envelope: &str) -> Value {
        serde_json::from_str(envelope).unwrap()
    }

    #[test]
    fn echo_round_trips_arbitrary_json() {
        let rec = recorder();
        let payload =
            json!({ "s": "é\"\\\u{0}😀", "n": [1, 2.5, null, true], "deep": { "a": { "b": {} } } });
        submit(rec, 10, "diag.echo".into(), payload.to_string());
        let out = wait_for(rec, 1);
        assert_eq!(parse(&out[&10][0])["ok"], payload);
    }

    #[test]
    fn malformed_arguments_are_an_error_not_a_crash() {
        let rec = recorder();
        submit(rec, 20, "diag.echo".into(), "{not json".into());
        let out = wait_for(rec, 1);
        assert_eq!(parse(&out[&20][0])["error"]["code"], "invalidArgument");
    }

    #[test]
    fn panic_becomes_an_internal_error() {
        let rec = recorder();
        submit(
            rec,
            30,
            "diag.panic".into(),
            json!({ "message": "boom" }).to_string(),
        );
        let out = wait_for(rec, 1);
        let error = &parse(&out[&30][0])["error"];
        assert_eq!(error["code"], "internal");
        assert!(error["message"].as_str().unwrap().contains("boom"));
    }

    #[test]
    fn duplicate_in_flight_id_is_refused_and_the_original_still_answers() {
        let rec = recorder();
        submit(
            rec,
            40,
            "diag.delay".into(),
            json!({ "millis": 300, "value": 1 }).to_string(),
        );
        submit(rec, 40, "diag.echo".into(), "2".into());
        let out = wait_for(rec, 2);
        let answers: Vec<Value> = out[&40].iter().map(|e| parse(e)).collect();
        assert!(answers
            .iter()
            .any(|a| a["error"]["code"] == "invalidArgument"));
        assert!(answers.iter().any(|a| a["ok"] == 1));
    }

    #[test]
    fn cancelled_delay_answers_cancelled_promptly() {
        let rec = recorder();
        submit(
            rec,
            50,
            "diag.delay".into(),
            json!({ "millis": 60_000 }).to_string(),
        );
        std::thread::sleep(Duration::from_millis(50));
        requests::cancel(50);
        let out = wait_for(rec, 1);
        assert_eq!(parse(&out[&50][0])["error"]["code"], "cancelled");
    }

    #[test]
    fn a_thousand_concurrent_requests_each_answer_once() {
        let rec = recorder();
        for i in 0..1000 {
            let method = if i % 3 == 0 {
                "diag.fail"
            } else {
                "diag.delay"
            };
            submit(
                rec,
                100_000 + i,
                method.into(),
                json!({ "millis": i % 20, "value": i }).to_string(),
            );
            if i % 7 == 0 {
                requests::cancel(100_000 + i);
            }
        }
        let out = wait_for(rec, 1000);
        assert_eq!(out.len(), 1000);
        for (id, answers) in out {
            assert_eq!(
                answers.len(),
                1,
                "request {id} answered {} times",
                answers.len()
            );
        }
    }

    #[test]
    fn unknown_method_is_answered() {
        let rec = recorder();
        submit(rec, 60, "no.such.method".into(), "{}".into());
        let out = wait_for(rec, 1);
        assert!(parse(&out[&60][0])["error"]["code"].is_string());
    }
}

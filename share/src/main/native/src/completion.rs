use std::fmt;

use jni::objects::{GlobalRef, JMethodID, JObject};
use jni::signature::{Primitive, ReturnType};
use jni::{JNIEnv, JavaVM};

/// A `java.lang.Runnable` run once the share UI no longer needs the caller's parent
/// window (the Wayland `xdg_foreign` export Kotlin keeps alive for the portal).
///
/// It runs when dropped, so every path releases it: right after `share` returns on
/// platforms with nothing to wait for, on errors, or once a portal dialog answered.
#[derive(Default)]
pub(crate) struct Completion(Option<(JavaVM, GlobalRef)>);

impl Completion {
    pub(crate) fn new(env: &mut JNIEnv, runnable: &JObject) -> jni::errors::Result<Self> {
        if runnable.is_null() {
            return Ok(Self(None));
        }
        Ok(Self(Some((env.get_java_vm()?, env.new_global_ref(runnable)?))))
    }
}

impl Drop for Completion {
    fn drop(&mut self) {
        let Some((vm, runnable)) = self.0.take() else {
            return;
        };
        // Attaching a thread that is already attached (the JNI caller) is a no-op.
        let Ok(mut env) = vm.attach_current_thread() else {
            return;
        };
        // Resolved on the interface: the lambda's own class needs no JNI registration
        // in a native image, `java.lang.Runnable` is declared once.
        let method: jni::errors::Result<JMethodID> = env.get_method_id("java/lang/Runnable", "run", "()V");
        if let Ok(method) = method {
            let args: [jni::sys::jvalue; 0] = [];
            let _ = unsafe {
                env.call_method_unchecked(runnable.as_obj(), method, ReturnType::Primitive(Primitive::Void), &args)
            };
        }
        if env.exception_check().unwrap_or(false) {
            // The runnable only closes a lease; a throw there must not leak into the caller.
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
    }
}

impl fmt::Debug for Completion {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(if self.0.is_some() { "Completion(pending)" } else { "Completion(none)" })
    }
}

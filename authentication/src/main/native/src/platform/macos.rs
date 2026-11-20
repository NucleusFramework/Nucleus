//! LocalAuthentication: `LAContext.evaluatePolicy` shows the Touch ID / password panel.

use std::collections::BTreeMap;
use std::sync::Mutex;

use block2::RcBlock;
use objc2::rc::Retained;
use objc2::runtime::Bool;
use objc2_foundation::{NSError, NSString};
use objc2_local_authentication::{LABiometryType, LAContext, LAPolicy};

use crate::{Biometry, Done, Error, Failure, Policy, Prompt, Result};

/// A context still waiting for its reply, kept so [cancel] can invalidate it.
struct Pending {
    context: Retained<LAContext>,
    canceled: bool,
}

// `LAContext` is documented as usable from any thread; `invalidate` is meant to be
// called while an evaluation runs on the framework's own queue.
unsafe impl Send for Pending {}

static PENDING: Mutex<BTreeMap<i64, Pending>> = Mutex::new(BTreeMap::new());

pub(crate) fn availability(policy: Policy) -> (Biometry, Result<()>) {
    // A fresh context per probe: a context remembers past evaluations.
    let context = unsafe { LAContext::new() };
    let result = unsafe { context.canEvaluatePolicy_error(la_policy(policy)) }.map_err(|error| failure(&error));
    // Only meaningful once canEvaluatePolicy ran, whatever it answered.
    (biometry(&context), result)
}

pub(crate) fn authenticate(id: i64, policy: Policy, prompt: Prompt, _parent: i64, done: Done) -> Result<()> {
    // An empty reason makes evaluatePolicy raise NSInvalidArgumentException.
    if prompt.reason.is_empty() {
        return Err(Failure::new(Error::InvalidPrompt, "the reason is empty"));
    }
    // One context per request: a context that already succeeded skips the prompt.
    let context = unsafe { LAContext::new() };
    let la_policy = la_policy(policy);
    unsafe { context.canEvaluatePolicy_error(la_policy) }.map_err(|error| failure(&error))?;

    if let Some(label) = prompt.cancel_label.as_deref().filter(|label| !label.is_empty()) {
        unsafe { context.setLocalizedCancelTitle(Some(&NSString::from_str(label))) };
    }
    if policy == Policy::Biometrics {
        // An empty title hides "Use Password…", which would otherwise end a
        // biometrics-only request with LAErrorUserFallback.
        unsafe { context.setLocalizedFallbackTitle(Some(&NSString::from_str(""))) };
    }

    if let Ok(mut pending) = PENDING.lock() {
        pending.insert(
            id,
            Pending {
                context: context.clone(),
                canceled: false,
            },
        );
    }

    let done = Mutex::new(Some(done));
    let reply = RcBlock::new(move |success: Bool, error: *mut NSError| {
        let canceled = PENDING
            .lock()
            .ok()
            .and_then(|mut pending| pending.remove(&id))
            .is_some_and(|pending| pending.canceled);
        let result = if success.as_bool() {
            Ok(())
        } else if canceled {
            Err(Error::AppCanceled.into())
        } else if let Some(error) = unsafe { error.as_ref() } {
            Err(failure(error))
        } else {
            Err(Error::Platform.into())
        };
        let done = done.lock().ok().and_then(|mut done| done.take());
        if let Some(done) = done {
            // This runs on a framework queue: a panic must not unwind into it.
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| done(result)));
        }
    });

    unsafe { context.evaluatePolicy_localizedReason_reply(la_policy, &NSString::from_str(&prompt.reason), &reply) };
    Ok(())
}

pub(crate) fn cancel(id: i64) {
    // Invalidated outside the lock: the reply it triggers takes the lock itself.
    let context = PENDING.lock().ok().and_then(|mut pending| {
        pending.get_mut(&id).map(|pending| {
            pending.canceled = true;
            pending.context.clone()
        })
    });
    if let Some(context) = context {
        unsafe { context.invalidate() };
    }
}

fn la_policy(policy: Policy) -> LAPolicy {
    match policy {
        // Touch ID, an Apple Watch, or the login password.
        Policy::DeviceOwner => LAPolicy::DeviceOwnerAuthentication,
        Policy::Biometrics => LAPolicy::DeviceOwnerAuthenticationWithBiometrics,
    }
}

fn biometry(context: &LAContext) -> Biometry {
    match unsafe { context.biometryType() } {
        LABiometryType::TouchID => Biometry::Fingerprint,
        LABiometryType::FaceID => Biometry::Face,
        LABiometryType::OpticID => Biometry::Iris,
        LABiometryType::None => Biometry::None,
        _ => Biometry::Unknown,
    }
}

/// Maps an `LAError` (by raw code, stable since the framework shipped).
fn failure(error: &NSError) -> Failure {
    let kind = match error.code() {
        -1 => Error::Failed,             // authenticationFailed
        -2 | -3 => Error::UserCanceled,  // userCancel, userFallback
        -4 => Error::SystemCanceled,     // systemCancel
        -5 => Error::NoCredential,       // passcodeNotSet
        -6 => Error::Unavailable,        // biometryNotAvailable
        -7 => Error::NotEnrolled,        // biometryNotEnrolled
        -8 => Error::Lockout,            // biometryLockout
        -9 => Error::AppCanceled,        // appCancel
        -13..=-11 => Error::Unavailable, // biometryDisconnected, biometryNotPaired, companionNotAvailable
        -1004 => Error::NotInteractive,  // notInteractive
        -1003 => Error::SystemCanceled,  // undocumented: unanswered for ~5 minutes
        _ => Error::Platform,
    };
    let description = error.localizedDescription().to_string();
    // An unmapped code is the one detail worth reporting: LocalAuthentication adds undocumented ones.
    if kind == Error::Platform {
        return Failure::new(kind, format!("{description} ({} {})", error.domain(), error.code()));
    }
    Failure::new(kind, description)
}

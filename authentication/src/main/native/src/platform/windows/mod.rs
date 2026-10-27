//! Windows Hello through `UserConsentVerifier`'s desktop-window interop, with a CredUI
//! username/password prompt when Hello is not set up.

mod fallback;

use std::collections::BTreeMap;
use std::sync::Mutex;
use std::time::Duration;

use windows::core::{factory, BOOL, HSTRING};
use windows::Security::Credentials::UI::{
    UserConsentVerificationResult, UserConsentVerifier, UserConsentVerifierAvailability,
};
use windows::Win32::Foundation::{HWND, LPARAM, RPC_E_CHANGED_MODE, TRUE, WPARAM};
use windows::Win32::System::Com::{CoInitializeEx, CoUninitialize, COINIT_MULTITHREADED};
use windows::Win32::System::Threading::GetCurrentProcessId;
use windows::Win32::System::WinRT::IUserConsentVerifierInterop;
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, FindWindowW, GetAncestor, GetClassNameW, GetForegroundWindow, GetWindow, GetWindowThreadProcessId,
    IsWindow, IsWindowVisible, PostMessageW, SetForegroundWindow, GA_ROOT, GW_OWNER, WM_CLOSE,
};
use windows_future::IAsyncOperation;

use crate::{Biometry, Done, Error, Failure, Policy, Prompt, Result};

/// The window class of both the Windows Hello dialog and the CredUI prompt.
const CREDENTIAL_DIALOG_CLASS: &str = "Credential Dialog Xaml Host";

/// A request still running, kept so [cancel] can reach it.
#[derive(Default)]
struct Pending {
    canceled: bool,
    hello: Option<IAsyncOperation<UserConsentVerificationResult>>,
}

static PENDING: Mutex<BTreeMap<i64, Pending>> = Mutex::new(BTreeMap::new());

pub(crate) fn availability(policy: Policy) -> (Biometry, Result<()>) {
    let _com = ComGuard::new_multithreaded();
    let hello = hello_available();
    let biometry = if hello { Biometry::Unknown } else { Biometry::None };
    match policy {
        // The CredUI password prompt is always there when Hello is not.
        Policy::DeviceOwner => (biometry, Ok(())),
        Policy::Biometrics => (biometry, Err(biometrics_only())),
    }
}

pub(crate) fn authenticate(id: i64, policy: Policy, prompt: Prompt, parent: i64, done: Done) -> Result<()> {
    if policy == Policy::Biometrics {
        return Err(biometrics_only());
    }
    let hwnd = resolve_window(parent)?;
    let hwnd_value = hwnd.0 as isize;
    if let Ok(mut pending) = PENDING.lock() {
        pending.insert(id, Pending::default());
    }
    // Every step blocks until the user answers: never on the caller's thread.
    let spawned = std::thread::Builder::new()
        .name("nucleus-authentication".into())
        .spawn(move || {
            // A new thread has no apartment, and blocking on IAsyncOperation::get needs an MTA.
            let com = ComGuard::new_multithreaded();
            let result = authenticate_blocking(id, HWND(hwnd_value as *mut _), &prompt);
            drop(com);
            let canceled = PENDING
                .lock()
                .ok()
                .and_then(|mut pending| pending.remove(&id))
                .is_some_and(|pending| pending.canceled);
            done(match result {
                Err(_) if canceled => Err(Error::AppCanceled.into()),
                result => result,
            });
        });
    if spawned.is_err() {
        if let Ok(mut pending) = PENDING.lock() {
            pending.remove(&id);
        }
        return Err(Failure::new(Error::Platform, "cannot start the authentication thread"));
    }
    Ok(())
}

pub(crate) fn cancel(id: i64) {
    let hello = PENDING.lock().ok().and_then(|mut pending| {
        pending.get_mut(&id).map(|pending| {
            pending.canceled = true;
            pending.hello.clone()
        })
    });
    match hello {
        Some(Some(operation)) => {
            let _ = operation.Cancel();
        }
        // The CredUI prompt runs in this process and has no cancel API: close its dialog.
        Some(None) => close_own_credential_dialog(),
        None => {}
    }
}

fn biometrics_only() -> Failure {
    Failure::new(
        Error::UnsupportedPolicy,
        "Windows Hello always accepts its PIN, biometrics cannot be required alone",
    )
}

fn hello_available() -> bool {
    UserConsentVerifier::CheckAvailabilityAsync()
        .and_then(|operation| operation.join())
        .is_ok_and(|availability| availability == UserConsentVerifierAvailability::Available)
}

fn authenticate_blocking(id: i64, hwnd: HWND, prompt: &Prompt) -> Result<()> {
    // NOTE: RequestVerification hangs when Hello is unavailable, so check first.
    if !hello_available() {
        return fallback::authenticate(hwnd, prompt);
    }
    let interop = factory::<UserConsentVerifier, IUserConsentVerifierInterop>()?;
    let operation: IAsyncOperation<UserConsentVerificationResult> =
        unsafe { interop.RequestVerificationForWindowAsync(hwnd, &HSTRING::from(prompt.reason.as_str())) }?;

    let canceled = PENDING
        .lock()
        .ok()
        .and_then(|mut pending| {
            pending.get_mut(&id).map(|pending| {
                pending.hello = Some(operation.clone());
                pending.canceled
            })
        })
        .unwrap_or(false);
    if canceled {
        let _ = operation.Cancel();
    } else {
        focus_credential_dialog();
    }
    convert(operation.join()?)
}

fn convert(result: UserConsentVerificationResult) -> Result<()> {
    match result {
        UserConsentVerificationResult::Verified => Ok(()),
        UserConsentVerificationResult::DeviceNotPresent => Err(Error::Unavailable.into()),
        UserConsentVerificationResult::NotConfiguredForUser => Err(Error::NoCredential.into()),
        UserConsentVerificationResult::DisabledByPolicy => Err(Error::DisabledByPolicy.into()),
        UserConsentVerificationResult::DeviceBusy => Err(Error::Busy.into()),
        UserConsentVerificationResult::RetriesExhausted => Err(Error::Lockout.into()),
        UserConsentVerificationResult::Canceled => Err(Error::UserCanceled.into()),
        other => Err(Failure::new(
            Error::Platform,
            format!("UserConsentVerificationResult({})", other.0),
        )),
    }
}

/// The Hello dialog opens behind the app on some builds: bring it forward. This process
/// owns the foreground (the user just acted in it), so `SetForegroundWindow` is allowed.
fn focus_credential_dialog() {
    let class = HSTRING::from(CREDENTIAL_DIALOG_CLASS);
    for _ in 0..4 {
        if let Ok(hwnd) = unsafe { FindWindowW(&class, None) } {
            if !hwnd.is_invalid() {
                let _ = unsafe { SetForegroundWindow(hwnd) };
                return;
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

fn close_own_credential_dialog() {
    let _ = unsafe { EnumWindows(Some(close_if_own_dialog), LPARAM(0)) };
}

unsafe extern "system" fn close_if_own_dialog(hwnd: HWND, _lparam: LPARAM) -> BOOL {
    let mut pid = 0u32;
    GetWindowThreadProcessId(hwnd, Some(&mut pid));
    if pid == GetCurrentProcessId() {
        let mut class = [0u16; 64];
        let len = GetClassNameW(hwnd, &mut class) as usize;
        if String::from_utf16_lossy(&class[..len]) == CREDENTIAL_DIALOG_CLASS {
            let _ = PostMessageW(Some(hwnd), WM_CLOSE, WPARAM(0), LPARAM(0));
        }
    }
    TRUE
}

/// The top-level window that owns the prompt: the caller's, else the process's
/// frontmost one, else none (the desktop).
fn resolve_window(handle: i64) -> Result<HWND> {
    if handle != 0 {
        let hwnd = HWND(handle as isize as *mut _);
        if !unsafe { IsWindow(Some(hwnd)) }.as_bool() {
            return Err(Failure::new(Error::Platform, "the parent window is gone"));
        }
        let root = unsafe { GetAncestor(hwnd, GA_ROOT) };
        return Ok(if root.is_invalid() { hwnd } else { root });
    }

    let foreground = unsafe { GetForegroundWindow() };
    if !foreground.is_invalid() && is_own_top_level(foreground) {
        return Ok(foreground);
    }
    // EnumWindows walks top-level windows in Z order: the first match is frontmost.
    let mut found = HWND::default();
    let _ = unsafe { EnumWindows(Some(find_own_window), LPARAM(&mut found as *mut HWND as isize)) };
    Ok(found)
}

unsafe extern "system" fn find_own_window(hwnd: HWND, lparam: LPARAM) -> BOOL {
    if is_own_top_level(hwnd) {
        *(lparam.0 as *mut HWND) = hwnd;
        return BOOL(0);
    }
    TRUE
}

fn is_own_top_level(hwnd: HWND) -> bool {
    let mut pid = 0u32;
    unsafe { GetWindowThreadProcessId(hwnd, Some(&mut pid)) };
    pid == unsafe { GetCurrentProcessId() }
        && unsafe { IsWindowVisible(hwnd) }.as_bool()
        && unsafe { GetWindow(hwnd, GW_OWNER) }.map_or(true, |owner| owner.is_invalid())
}

/// Joins the multithreaded apartment for this thread and leaves it on drop, unless the
/// thread already lives in another apartment.
struct ComGuard {
    should_uninit: bool,
}

impl ComGuard {
    fn new_multithreaded() -> Self {
        let hr = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
        // S_OK / S_FALSE owe a CoUninitialize; RPC_E_CHANGED_MODE means another apartment.
        Self {
            should_uninit: hr != RPC_E_CHANGED_MODE,
        }
    }
}

impl Drop for ComGuard {
    fn drop(&mut self) {
        if self.should_uninit {
            unsafe { CoUninitialize() };
        }
    }
}

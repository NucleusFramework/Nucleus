//! macOS backend: Core Location.
//!
//! Core Location calls a delegate on the run loop of the thread that created its
//! `CLLocationManager`, and that thread must keep running its loop. The JVM's main thread is not
//! ours to rely on (a headless app has no loop there; a Tao app runs AppKit on it, but JNI calls
//! arrive from any thread), so every manager lives on one dedicated `nucleus-location` thread
//! running a `CFRunLoop`; the rest of the crate talks to it by posting blocks.
//!
//! Authorization needs a usage description in the app's `Info.plist`
//! (`NSLocationWhenInUseUsageDescription`, plus `NSLocationAlwaysAndWhenInUseUsageDescription` for
//! background access), and — under the hardened runtime, sandboxed or not — the
//! `com.apple.security.personal-information.location` entitlement. Without either — a bare `java`
//! launched from a terminal or an IDE, a notarizable build signed without the entitlement —
//! locationd never shows the prompt and never answers, so nothing waits for that answer.
//!
//! A new `CLLocationManager` reports `NotDetermined` until it has synchronised with locationd;
//! the true status is the one its delegate receives right after `setDelegate`. Every operation
//! here therefore starts from that first callback, never from `authorizationStatus()` read on a
//! manager just created.

use crate::{
    valid_coordinates, AuthCallback, ErrorKind, Fix, SessionOptions, Sink, AUTH_BACKGROUND, AUTH_DENIED,
    AUTH_FOREGROUND, AUTH_NOT_DETERMINED, AUTH_RESTRICTED,
};
use block2::RcBlock;
use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2::{define_class, msg_send, AllocAnyThread, DefinedClass};
use objc2_core_foundation::{kCFRunLoopDefaultMode, CFRetained, CFRunLoop, CFRunLoopTimer};
use objc2_core_location::{
    kCLDistanceFilterNone, kCLLocationAccuracyBest, kCLLocationAccuracyKilometer, CLAuthorizationStatus,
    CLError, CLLocation, CLLocationManager, CLLocationManagerDelegate,
};
use objc2_foundation::{NSArray, NSBundle, NSError, NSObject, NSObjectProtocol, NSString};
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::Duration;

const QUERY_TIMEOUT: Duration = Duration::from_secs(5);

// ---------------------------------------------------------------------------------------------
// The Core Location thread
// ---------------------------------------------------------------------------------------------

struct LoopHandle(CFRetained<CFRunLoop>);

// SAFETY: only `CFRunLoopPerformBlock` and `CFRunLoopWakeUp` are called through the handle from
// other threads, and both are documented as thread-safe.
unsafe impl Send for LoopHandle {}
unsafe impl Sync for LoopHandle {}

static LOOP: OnceLock<Option<LoopHandle>> = OnceLock::new();
static NEXT_KEY: AtomicU64 = AtomicU64::new(1);

thread_local! {
    /// Every live manager, keyed by session (or authorization request). Only the loop thread
    /// touches it, so the Objective-C objects never leave that thread.
    static ENTRIES: RefCell<HashMap<u64, Entry>> = RefCell::new(HashMap::new());
}

struct Entry {
    manager: Retained<CLLocationManager>,
    _delegate: Retained<Delegate>,
}

fn run_loop() -> Option<&'static LoopHandle> {
    LOOP.get_or_init(|| {
        let (sender, receiver) = mpsc::channel();
        thread::Builder::new()
            .name("nucleus-location".into())
            .spawn(move || {
                let Some(run_loop) = CFRunLoop::current() else {
                    let _ = sender.send(None);
                    return;
                };
                // A run loop without a source returns at once; a timer that never fires keeps it up.
                let keep_alive = keep_alive_timer();
                if let Some(timer) = keep_alive.as_deref() {
                    run_loop.add_timer(Some(timer), unsafe { kCFRunLoopDefaultMode });
                }
                let _ = sender.send(Some(LoopHandle(run_loop)));
                loop {
                    CFRunLoop::run();
                }
            })
            .ok()?;
        receiver.recv().ok().flatten()
    })
    .as_ref()
}

fn keep_alive_timer() -> Option<CFRetained<CFRunLoopTimer>> {
    let block = RcBlock::new(|_: *mut CFRunLoopTimer| {});
    // ~300 years out, repeating at the same pace: the loop sleeps on it and nothing else.
    unsafe { CFRunLoopTimer::with_handler(None, 1.0e10, 1.0e10, 0, 0, Some(&block)) }
}

/// Runs `task` on the Core Location thread. Returns `false` when that thread is unavailable.
fn on_loop(task: impl FnOnce() + Send + 'static) -> bool {
    let Some(handle) = run_loop() else {
        return false;
    };
    let task = Mutex::new(Some(task));
    let block = RcBlock::new(move || {
        if let Some(task) = task.lock().ok().and_then(|mut task| task.take()) {
            task();
        }
    });
    unsafe {
        handle.0.perform_block(kCFRunLoopDefaultMode.map(|mode| mode.as_ref()), Some(&block));
    }
    handle.0.wake_up();
    true
}

// ---------------------------------------------------------------------------------------------
// Delegate
// ---------------------------------------------------------------------------------------------

#[derive(Copy, Clone, PartialEq, Eq)]
enum Phase {
    /// Waiting for the delegate's first callback, which carries the true status.
    Resolving,
    /// Waiting for the user to answer the authorization prompt.
    AwaitingAuthorization,
    Running,
}

/// What a manager was created for, acted on once its true status is known.
enum Purpose {
    /// `authorization_status`: report the status, then drop the manager.
    Status(Option<mpsc::Sender<i32>>),
    /// `request_authorization`: prompt if nothing is decided yet, report the answer.
    Authorization { background: bool, done: Option<AuthCallback> },
    /// A location session.
    Session { sink: Arc<dyn Sink>, options: SessionOptions },
}

struct Ivars {
    key: u64,
    purpose: RefCell<Purpose>,
    phase: Cell<Phase>,
}

define_class!(
    #[unsafe(super(NSObject))]
    #[name = "NucleusLocationDelegate"]
    #[ivars = Ivars]
    struct Delegate;

    unsafe impl NSObjectProtocol for Delegate {}

    unsafe impl CLLocationManagerDelegate for Delegate {
        #[unsafe(method(locationManager:didUpdateLocations:))]
        #[allow(non_snake_case)]
        fn locationManager_didUpdateLocations(
            &self,
            _manager: &CLLocationManager,
            locations: &NSArray<CLLocation>,
        ) {
            for location in locations.iter() {
                self.deliver(&location, false);
            }
        }

        #[unsafe(method(locationManager:didFailWithError:))]
        #[allow(non_snake_case)]
        fn locationManager_didFailWithError(&self, _manager: &CLLocationManager, error: &NSError) {
            let Some(sink) = self.sink() else {
                return;
            };
            let kind = match CLError(error.code()) {
                CLError::LocationUnknown => ErrorKind::TemporarilyUnavailable,
                CLError::Denied => ErrorKind::AuthorizationDenied,
                CLError::Network => ErrorKind::Network,
                _ => ErrorKind::Unknown,
            };
            sink.error(kind, &error.localizedDescription().to_string());
        }

        // Fires once with the initial state right after `setDelegate`, then on every change.
        #[unsafe(method(locationManagerDidChangeAuthorization:))]
        #[allow(non_snake_case)]
        fn locationManagerDidChangeAuthorization(&self, manager: &CLLocationManager) {
            self.authorization_changed(manager, unsafe { manager.authorizationStatus() });
        }
    }
);

impl Delegate {
    fn new(key: u64, purpose: Purpose) -> Retained<Self> {
        let this = Self::alloc().set_ivars(Ivars {
            key,
            purpose: RefCell::new(purpose),
            phase: Cell::new(Phase::Resolving),
        });
        unsafe { msg_send![super(this), init] }
    }

    fn sink(&self) -> Option<Arc<dyn Sink>> {
        match &*self.ivars().purpose.borrow() {
            Purpose::Session { sink, .. } => Some(sink.clone()),
            _ => None,
        }
    }

    /// Drops this manager — on the next turn, not from inside a callback that borrows it.
    fn release(&self) {
        let key = self.ivars().key;
        on_loop(move || drop(ENTRIES.with(|entries| entries.borrow_mut().remove(&key))));
    }

    // Every fix goes to Kotlin, which decides whether a cached one is recent enough. A fix Core
    // Location answers a request with is the platform's current answer even when its timestamp
    // predates the request (a Mac that has not moved gets its last fix back).
    fn deliver(&self, location: &CLLocation, cached: bool) {
        let Some(sink) = self.sink() else {
            return;
        };
        if let Some(fix) = to_fix(location, cached) {
            sink.location(fix);
        }
    }

    fn begin(&self, manager: &CLLocationManager) {
        self.ivars().phase.set(Phase::Running);
        let (continuous, max_cached_age_millis) = match &*self.ivars().purpose.borrow() {
            Purpose::Session { options, .. } => (options.continuous, options.max_cached_age_millis),
            _ => return,
        };
        if continuous {
            unsafe { manager.startUpdatingLocation() };
            return;
        }
        // Cached first — the Kotlin side decides whether it is recent enough — then refine.
        if max_cached_age_millis > 0 {
            if let Some(location) = unsafe { manager.location() } {
                self.deliver(&location, true);
            }
        }
        unsafe { manager.requestLocation() };
    }

    fn fail(&self, kind: ErrorKind, message: &str) {
        self.ivars().phase.set(Phase::Running);
        if let Some(sink) = self.sink() {
            sink.error(kind, message);
        }
    }

    fn authorization_changed(&self, manager: &CLLocationManager, status: CLAuthorizationStatus) {
        match self.ivars().phase.get() {
            Phase::Resolving => self.resolved(manager, status),
            Phase::AwaitingAuthorization if status != CLAuthorizationStatus::NotDetermined => {
                self.answered(manager, status)
            }
            _ => {}
        }
    }

    /// The first callback: the manager's true status.
    fn resolved(&self, manager: &CLLocationManager, status: CLAuthorizationStatus) {
        let undetermined = status == CLAuthorizationStatus::NotDetermined;
        let mut purpose = self.ivars().purpose.borrow_mut();
        match &mut *purpose {
            Purpose::Status(sender) => {
                if let Some(sender) = sender.take() {
                    let _ = sender.send(map_status(status));
                }
                drop(purpose);
                self.release();
            }
            Purpose::Authorization { background, done } => {
                let upgrade = *background && status == CLAuthorizationStatus::AuthorizedWhenInUse;
                if (!undetermined && !upgrade) || !can_request_authorization() {
                    if let Some(done) = done.take() {
                        done(map_status(status));
                    }
                    drop(purpose);
                    self.release();
                    return;
                }
                self.ivars().phase.set(Phase::AwaitingAuthorization);
                unsafe {
                    if *background {
                        manager.requestAlwaysAuthorization();
                    } else {
                        manager.requestWhenInUseAuthorization();
                    }
                }
            }
            Purpose::Session { .. } => {
                drop(purpose);
                match status {
                    CLAuthorizationStatus::AuthorizedAlways | CLAuthorizationStatus::AuthorizedWhenInUse => {
                        self.begin(manager)
                    }
                    // `requestLocation` fails at once while undetermined: ask first, start on the answer.
                    CLAuthorizationStatus::NotDetermined if can_request_authorization() => {
                        self.ivars().phase.set(Phase::AwaitingAuthorization);
                        unsafe { manager.requestWhenInUseAuthorization() };
                    }
                    CLAuthorizationStatus::NotDetermined => {
                        self.fail(ErrorKind::AuthorizationDenied, CANNOT_PROMPT)
                    }
                    _ => self.fail(ErrorKind::AuthorizationDenied, "location access was denied"),
                }
            }
        }
    }

    /// A determined status after the prompt.
    fn answered(&self, manager: &CLLocationManager, status: CLAuthorizationStatus) {
        let mut purpose = self.ivars().purpose.borrow_mut();
        match &mut *purpose {
            Purpose::Authorization { done, .. } => {
                self.ivars().phase.set(Phase::Running);
                if let Some(done) = done.take() {
                    done(map_status(status));
                }
                drop(purpose);
                self.release();
            }
            Purpose::Session { .. } => {
                drop(purpose);
                match status {
                    CLAuthorizationStatus::AuthorizedAlways | CLAuthorizationStatus::AuthorizedWhenInUse => {
                        self.begin(manager)
                    }
                    _ => self.fail(ErrorKind::AuthorizationDenied, "location access was denied"),
                }
            }
            Purpose::Status(_) => {}
        }
    }
}

const CANNOT_PROMPT: &str = "Core Location cannot prompt this app: it needs an NSLocation*UsageDescription in its \
    Info.plist and, under the hardened runtime, the com.apple.security.personal-information.location entitlement";

// ---------------------------------------------------------------------------------------------
// Backend
// ---------------------------------------------------------------------------------------------

pub struct Session {
    key: u64,
}

impl Drop for Session {
    fn drop(&mut self) {
        let key = self.key;
        on_loop(move || {
            if let Some(entry) = ENTRIES.with(|entries| entries.borrow_mut().remove(&key)) {
                unsafe {
                    entry.manager.stopUpdatingLocation();
                    entry.manager.setDelegate(None);
                }
            }
        });
    }
}

pub fn is_available() -> bool {
    // A blocking IPC call to locationd; Apple only warns against it on the main thread.
    unsafe { CLLocationManager::locationServicesEnabled_class() }
}

pub fn authorization_status() -> i32 {
    let (sender, receiver) = mpsc::channel();
    let key = NEXT_KEY.fetch_add(1, Ordering::Relaxed);
    let posted = on_loop(move || {
        let manager = unsafe { CLLocationManager::new() };
        let delegate = Delegate::new(key, Purpose::Status(Some(sender)));
        unsafe { manager.setDelegate(Some(ProtocolObject::from_ref(&*delegate))) };
        ENTRIES.with(|entries| entries.borrow_mut().insert(key, Entry { manager, _delegate: delegate }));
    });
    if !posted {
        return AUTH_RESTRICTED;
    }
    receiver.recv_timeout(QUERY_TIMEOUT).unwrap_or(AUTH_RESTRICTED)
}

pub fn request_authorization(background: bool, precise: bool, _desktop_id: String, done: AuthCallback) {
    let done = Arc::new(Mutex::new(Some(done)));
    let answer = done.clone();
    let posted = on_loop(move || {
        let Some(done) = answer.lock().ok().and_then(|mut done| done.take()) else {
            return;
        };
        let manager = unsafe { CLLocationManager::new() };
        unsafe { manager.setDesiredAccuracy(desired_accuracy(precise)) };
        let key = NEXT_KEY.fetch_add(1, Ordering::Relaxed);
        let delegate = Delegate::new(key, Purpose::Authorization { background, done: Some(done) });
        unsafe { manager.setDelegate(Some(ProtocolObject::from_ref(&*delegate))) };
        ENTRIES.with(|entries| entries.borrow_mut().insert(key, Entry { manager, _delegate: delegate }));
    });
    if !posted {
        if let Some(done) = done.lock().ok().and_then(|mut done| done.take()) {
            done(AUTH_RESTRICTED);
        }
    }
}

pub fn start(options: SessionOptions, sink: Arc<dyn Sink>) -> Result<Session, (ErrorKind, String)> {
    let key = NEXT_KEY.fetch_add(1, Ordering::Relaxed);
    let posted = on_loop(move || {
        let manager = unsafe { CLLocationManager::new() };
        unsafe {
            manager.setDesiredAccuracy(desired_accuracy(options.precise));
            manager.setDistanceFilter(if options.distance_meters > 0.0 {
                options.distance_meters
            } else {
                kCLDistanceFilterNone
            });
        }
        let delegate = Delegate::new(key, Purpose::Session { sink, options });
        unsafe { manager.setDelegate(Some(ProtocolObject::from_ref(&*delegate))) };
        ENTRIES.with(|entries| entries.borrow_mut().insert(key, Entry { manager, _delegate: delegate }));
    });
    if posted {
        Ok(Session { key })
    } else {
        Err((ErrorKind::PermanentlyUnavailable, "the Core Location thread could not be started".into()))
    }
}

fn desired_accuracy(precise: bool) -> f64 {
    unsafe {
        if precise {
            kCLLocationAccuracyBest
        } else {
            kCLLocationAccuracyKilometer
        }
    }
}

fn map_status(status: CLAuthorizationStatus) -> i32 {
    match status {
        CLAuthorizationStatus::NotDetermined => AUTH_NOT_DETERMINED,
        CLAuthorizationStatus::Denied => AUTH_DENIED,
        CLAuthorizationStatus::AuthorizedAlways => AUTH_BACKGROUND,
        CLAuthorizationStatus::AuthorizedWhenInUse => AUTH_FOREGROUND,
        _ => AUTH_RESTRICTED,
    }
}

/// Whether Core Location can prompt at all: only with a usage description in the `Info.plist`,
/// and under the hardened runtime only with the location entitlement (locationd logs "Client has
/// supported the hardened runtime but doesn't have the entitlement" and never answers).
fn can_request_authorization() -> bool {
    static CAN_PROMPT: OnceLock<bool> = OnceLock::new();
    *CAN_PROMPT.get_or_init(|| has_usage_description() && (!hardened_runtime() || has_location_entitlement()))
}

fn has_usage_description() -> bool {
    let bundle = NSBundle::mainBundle();
    [
        "NSLocationUsageDescription",
        "NSLocationWhenInUseUsageDescription",
        "NSLocationAlwaysAndWhenInUseUsageDescription",
        "NSLocationAlwaysUsageDescription",
    ]
    .iter()
    .any(|key| bundle.objectForInfoDictionaryKey(&NSString::from_str(key)).is_some())
}

const LOCATION_ENTITLEMENT: &str = "com.apple.security.personal-information.location";
/// `kSecCodeSignatureRuntime`.
const CODE_SIGNATURE_RUNTIME: u32 = 0x10000;
/// `kSecCSSigningInformation`.
const CS_SIGNING_INFORMATION: u32 = 1 << 1;
/// `kCFNumberSInt64Type`.
const CF_NUMBER_SINT64: isize = 4;

#[link(name = "Security", kind = "framework")]
extern "C" {
    static kSecCodeInfoFlags: *const c_void;
    fn SecCodeCopySelf(flags: u32, code: *mut *mut c_void) -> i32;
    fn SecCodeCopySigningInformation(code: *mut c_void, flags: u32, information: *mut *const c_void) -> i32;
    fn SecTaskCreateFromSelf(allocator: *const c_void) -> *mut c_void;
    fn SecTaskCopyValueForEntitlement(
        task: *mut c_void,
        entitlement: *const c_void,
        error: *mut *mut c_void,
    ) -> *const c_void;
}

#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    static kCFBooleanTrue: *const c_void;
    fn CFRelease(cf: *const c_void);
    fn CFDictionaryGetValue(dictionary: *const c_void, key: *const c_void) -> *const c_void;
    fn CFNumberGetValue(number: *const c_void, kind: isize, value: *mut c_void) -> bool;
}

/// Whether this process's signature carries the hardened runtime flag.
fn hardened_runtime() -> bool {
    unsafe {
        let mut code = std::ptr::null_mut();
        if SecCodeCopySelf(0, &mut code) != 0 || code.is_null() {
            return false;
        }
        let mut information = std::ptr::null();
        let status = SecCodeCopySigningInformation(code, CS_SIGNING_INFORMATION, &mut information);
        CFRelease(code);
        if status != 0 || information.is_null() {
            return false;
        }
        let mut flags: i64 = 0;
        let number = CFDictionaryGetValue(information, kSecCodeInfoFlags);
        let read = !number.is_null() && CFNumberGetValue(number, CF_NUMBER_SINT64, (&raw mut flags).cast());
        CFRelease(information);
        read && flags as u32 & CODE_SIGNATURE_RUNTIME != 0
    }
}

fn has_location_entitlement() -> bool {
    unsafe {
        let task = SecTaskCreateFromSelf(std::ptr::null());
        if task.is_null() {
            return false;
        }
        // NSString is toll-free bridged to CFString.
        let name = NSString::from_str(LOCATION_ENTITLEMENT);
        let value =
            SecTaskCopyValueForEntitlement(task, Retained::as_ptr(&name).cast(), std::ptr::null_mut());
        CFRelease(task);
        if value.is_null() {
            return false;
        }
        let granted = value == kCFBooleanTrue;
        CFRelease(value);
        granted
    }
}

fn to_fix(location: &CLLocation, cached: bool) -> Option<Fix> {
    unsafe {
        let coordinate = location.coordinate();
        let horizontal_accuracy = location.horizontalAccuracy();
        // A negative accuracy marks the coordinate itself as invalid.
        if horizontal_accuracy < 0.0 || !valid_coordinates(coordinate.latitude, coordinate.longitude) {
            return None;
        }
        let vertical_accuracy = Some(location.verticalAccuracy()).filter(|v| *v > 0.0);
        Some(Fix {
            latitude: coordinate.latitude,
            longitude: coordinate.longitude,
            altitude: vertical_accuracy.map(|_| location.altitude()),
            horizontal_accuracy: Some(horizontal_accuracy),
            vertical_accuracy,
            bearing: Some(location.course()).filter(|v| *v >= 0.0),
            speed: Some(location.speed()).filter(|v| *v >= 0.0),
            time_millis: (location.timestamp().timeIntervalSince1970() * 1000.0) as i64,
            cached,
        })
    }
}

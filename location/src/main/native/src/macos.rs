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
//! background access). Without one — a bare `java` launched from a terminal or an IDE — Core
//! Location never prompts, so nothing waits for an answer that cannot come.

use crate::{
    valid_coordinates, AuthCallback, ErrorKind, Fix, SessionOptions, Sink, AUTH_BACKGROUND,
    AUTH_DENIED, AUTH_FOREGROUND, AUTH_NOT_DETERMINED, AUTH_RESTRICTED,
};
use block2::RcBlock;
use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2::{define_class, msg_send, AllocAnyThread, DefinedClass};
use objc2_core_foundation::{kCFRunLoopDefaultMode, CFRetained, CFRunLoop, CFRunLoopTimer};
use objc2_core_location::{
    kCLDistanceFilterNone, kCLLocationAccuracyBest, kCLLocationAccuracyKilometer,
    CLAuthorizationStatus, CLError, CLLocation, CLLocationManager, CLLocationManagerDelegate,
};
use objc2_foundation::{NSArray, NSBundle, NSError, NSObject, NSObjectProtocol, NSString};
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
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

/// Runs `query` on the Core Location thread and waits for its answer.
fn query<T: Send + 'static>(query: impl FnOnce() -> T + Send + 'static) -> Option<T> {
    let (sender, receiver) = mpsc::channel();
    if !on_loop(move || {
        let _ = sender.send(query());
    }) {
        return None;
    }
    receiver.recv_timeout(QUERY_TIMEOUT).ok()
}

// ---------------------------------------------------------------------------------------------
// Delegate
// ---------------------------------------------------------------------------------------------

#[derive(Copy, Clone, PartialEq, Eq)]
enum Phase {
    /// Waiting for the user to answer the authorization prompt before starting.
    AwaitingAuthorization,
    Running,
}

struct Ivars {
    key: u64,
    /// Set for an authorization request, consumed by the first determined status.
    auth: RefCell<Option<AuthCallback>>,
    background: bool,
    /// Set for a location session.
    sink: Option<Arc<dyn Sink>>,
    options: Option<SessionOptions>,
    phase: Cell<Phase>,
    /// Timestamp of the last fix a one-shot delivered, so it never goes backwards.
    last_delivered: Cell<f64>,
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
            let one_shot = self.ivars().options.as_ref().is_some_and(|o| !o.continuous);
            for location in locations.iter() {
                self.deliver(&location, false, one_shot);
            }
        }

        #[unsafe(method(locationManager:didFailWithError:))]
        #[allow(non_snake_case)]
        fn locationManager_didFailWithError(&self, _manager: &CLLocationManager, error: &NSError) {
            let Some(sink) = self.ivars().sink.as_ref() else {
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
    fn new(ivars: Ivars) -> Retained<Self> {
        let this = Self::alloc().set_ivars(ivars);
        unsafe { msg_send![super(this), init] }
    }

    fn deliver(&self, location: &CLLocation, cached: bool, one_shot: bool) {
        let Some(sink) = self.ivars().sink.as_ref() else {
            return;
        };
        let timestamp = unsafe { location.timestamp().timeIntervalSince1970() };
        if one_shot {
            if timestamp <= self.ivars().last_delivered.get() {
                return;
            }
            self.ivars().last_delivered.set(timestamp);
        }
        if let Some(fix) = to_fix(location, cached) {
            sink.location(fix);
        }
    }

    fn begin(&self, manager: &CLLocationManager) {
        let Some(options) = self.ivars().options.as_ref() else {
            return;
        };
        self.ivars().phase.set(Phase::Running);
        if options.continuous {
            unsafe { manager.startUpdatingLocation() };
            return;
        }
        // Cached first — the Kotlin side decides whether it is recent enough — then refine.
        if options.max_cached_age_millis > 0 {
            if let Some(location) = unsafe { manager.location() } {
                self.deliver(&location, true, true);
            }
        }
        unsafe { manager.requestLocation() };
    }

    fn authorization_changed(&self, manager: &CLLocationManager, status: CLAuthorizationStatus) {
        if status == CLAuthorizationStatus::NotDetermined {
            return;
        }
        if let Some(done) = self.ivars().auth.borrow_mut().take() {
            done(map_status(status));
            let key = self.ivars().key;
            // Not from inside the callback that borrows this delegate: posted to the next turn.
            on_loop(move || drop(ENTRIES.with(|entries| entries.borrow_mut().remove(&key))));
            return;
        }
        if self.ivars().phase.get() != Phase::AwaitingAuthorization {
            return;
        }
        match status {
            CLAuthorizationStatus::AuthorizedAlways | CLAuthorizationStatus::AuthorizedWhenInUse => {
                self.begin(manager)
            }
            _ => {
                self.ivars().phase.set(Phase::Running);
                if let Some(sink) = self.ivars().sink.as_ref() {
                    sink.error(ErrorKind::AuthorizationDenied, "location access was denied");
                }
            }
        }
    }
}

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
    query(|| map_status(unsafe { CLLocationManager::new().authorizationStatus() })).unwrap_or(AUTH_RESTRICTED)
}

pub fn request_authorization(background: bool, precise: bool, _desktop_id: String, done: AuthCallback) {
    let done = Arc::new(Mutex::new(Some(done)));
    let answer = done.clone();
    let posted = on_loop(move || {
        let Some(done) = answer.lock().ok().and_then(|mut done| done.take()) else {
            return;
        };
        let manager = unsafe { CLLocationManager::new() };
        let status = unsafe { manager.authorizationStatus() };
        let upgrade = background && status == CLAuthorizationStatus::AuthorizedWhenInUse;
        if (status != CLAuthorizationStatus::NotDetermined && !upgrade) || !can_request_authorization() {
            done(map_status(status));
            return;
        }
        unsafe { manager.setDesiredAccuracy(desired_accuracy(precise)) };
        let key = NEXT_KEY.fetch_add(1, Ordering::Relaxed);
        let delegate = Delegate::new(Ivars {
            key,
            auth: RefCell::new(Some(done)),
            background,
            sink: None,
            options: None,
            phase: Cell::new(Phase::Running),
            last_delivered: Cell::new(f64::NEG_INFINITY),
        });
        unsafe {
            manager.setDelegate(Some(ProtocolObject::from_ref(&*delegate)));
            if delegate.ivars().background {
                manager.requestAlwaysAuthorization();
            } else {
                manager.requestWhenInUseAuthorization();
            }
        }
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
        let status = unsafe { manager.authorizationStatus() };
        let awaiting = status == CLAuthorizationStatus::NotDetermined && can_request_authorization();
        unsafe {
            manager.setDesiredAccuracy(desired_accuracy(options.precise));
            manager.setDistanceFilter(if options.distance_meters > 0.0 {
                options.distance_meters
            } else {
                kCLDistanceFilterNone
            });
        }
        let delegate = Delegate::new(Ivars {
            key,
            auth: RefCell::new(None),
            background: false,
            sink: Some(sink.clone()),
            options: Some(options),
            phase: Cell::new(if awaiting { Phase::AwaitingAuthorization } else { Phase::Running }),
            last_delivered: Cell::new(f64::NEG_INFINITY),
        });
        unsafe { manager.setDelegate(Some(ProtocolObject::from_ref(&*delegate))) };
        match status {
            CLAuthorizationStatus::Denied | CLAuthorizationStatus::Restricted => {
                sink.error(ErrorKind::AuthorizationDenied, "location access was denied");
            }
            // `requestLocation` fails at once while undetermined: ask first, start on the answer.
            _ if awaiting => unsafe { manager.requestWhenInUseAuthorization() },
            _ => delegate.begin(&manager),
        }
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

/// Whether Core Location can prompt at all: only with a usage description in the `Info.plist`.
fn can_request_authorization() -> bool {
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

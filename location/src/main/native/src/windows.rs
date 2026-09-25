//! Windows backend: WinRT `Windows.Devices.Geolocation.Geolocator`.
//!
//! Desktop (Win32) apps need no manifest capability: access follows the "Let desktop apps access
//! your location" privacy switch, and from Windows 11 24H2 on a first request raises the system's
//! consent prompt. WinRT activation from a thread that never initialised COM falls back to the
//! implicit MTA (windows-rs calls `CoIncrementMTAUsage`), so every call below may run on a plain
//! thread; `PositionChanged` / `StatusChanged` arrive on the WinRT thread pool.

use crate::{
    valid_coordinates, ErrorKind, Fix, SessionOptions, Sink, AuthCallback, AUTH_BACKGROUND,
    AUTH_DENIED, AUTH_FOREGROUND, AUTH_NOT_DETERMINED, AUTH_RESTRICTED,
};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use windows::core::HRESULT;
use windows::Devices::Geolocation::{
    GeolocationAccessStatus, Geolocator, Geocoordinate, Geoposition, PositionAccuracy,
    PositionChangedEventArgs, PositionStatus, StatusChangedEventArgs,
};
use windows::Foundation::{TimeSpan, TypedEventHandler};
use windows_core::Ref;
use windows_future::IAsyncOperation;

/// Set once `RequestAccessAsync` answered `Allowed` in this process: WinRT has no query that
/// tells "never asked" from "allowed" without asking.
static GRANTED: AtomicBool = AtomicBool::new(false);

const TICKS_PER_MILLI: i64 = 10_000;
/// Ticks between 1601-01-01 (the WinRT `DateTime` epoch) and 1970-01-01.
const UNIX_EPOCH_TICKS: i64 = 11_644_473_600 * 10_000_000;
/// How long a cached lookup may take before it is treated as "no cached fix": long enough for
/// the service to answer from its cache, too short for it to acquire a new position.
const CACHED_LOOKUP_TIMEOUT_MILLIS: i64 = 100;

const E_ACCESSDENIED: HRESULT = HRESULT(0x8007_0005_u32 as i32);
const ERROR_TIMEOUT: HRESULT = HRESULT(0x8007_05B4_u32 as i32);
const E_ABORT: HRESULT = HRESULT(0x8000_4004_u32 as i32);

pub struct Session {
    inner: Inner,
}

enum Inner {
    OneShot {
        stopped: Arc<AtomicBool>,
        pending: Arc<Mutex<Option<IAsyncOperation<Geoposition>>>>,
    },
    Continuous {
        geolocator: Geolocator,
        position_token: i64,
        status_token: i64,
    },
}

impl Drop for Session {
    fn drop(&mut self) {
        match &self.inner {
            Inner::OneShot { stopped, pending } => {
                stopped.store(true, Ordering::Release);
                if let Some(operation) = pending.lock().ok().and_then(|mut op| op.take()) {
                    let _ = operation.Cancel();
                }
            }
            Inner::Continuous { geolocator, position_token, status_token } => {
                let _ = geolocator.RemovePositionChanged(*position_token);
                let _ = geolocator.RemoveStatusChanged(*status_token);
            }
        }
    }
}

pub fn is_available() -> bool {
    Geolocator::new()
        .and_then(|geolocator| geolocator.LocationStatus())
        .map(|status| status != PositionStatus::NotAvailable)
        .unwrap_or(false)
}

pub fn authorization_status() -> i32 {
    match Geolocator::new().and_then(|geolocator| geolocator.LocationStatus()) {
        Ok(PositionStatus::Disabled) => AUTH_DENIED,
        Ok(PositionStatus::NotAvailable) | Err(_) => AUTH_RESTRICTED,
        Ok(_) if GRANTED.load(Ordering::Acquire) => AUTH_FOREGROUND,
        Ok(_) => AUTH_NOT_DETERMINED,
    }
}

pub fn request_authorization(background: bool, _precise: bool, _desktop_id: String, done: AuthCallback) {
    // `.get()` blocks until the user answers a consent prompt, so never on the caller's thread.
    // A failed spawn drops the callback; the Kotlin side times the request out.
    let _ = thread::Builder::new().name("nucleus-location-auth".into()).spawn(move || {
        let status = match Geolocator::RequestAccessAsync().and_then(|operation| operation.join()) {
            Ok(GeolocationAccessStatus::Allowed) => {
                GRANTED.store(true, Ordering::Release);
                // A desktop app keeps its access while in the background: nothing to upgrade.
                if background { AUTH_BACKGROUND } else { AUTH_FOREGROUND }
            }
            Ok(GeolocationAccessStatus::Denied) => AUTH_DENIED,
            Ok(_) => AUTH_NOT_DETERMINED,
            Err(_) => AUTH_RESTRICTED,
        };
        done(status);
    });
}

pub fn start(options: SessionOptions, sink: Arc<dyn Sink>) -> Result<Session, (ErrorKind, String)> {
    let geolocator = Geolocator::new().map_err(|e| (ErrorKind::PermanentlyUnavailable, e.message()))?;
    let accuracy = if options.precise { PositionAccuracy::High } else { PositionAccuracy::Default };
    geolocator.SetDesiredAccuracy(accuracy).map_err(to_error)?;
    if options.continuous {
        start_continuous(geolocator, &options, sink)
    } else {
        start_one_shot(geolocator, &options, sink)
    }
}

fn start_one_shot(
    geolocator: Geolocator,
    options: &SessionOptions,
    sink: Arc<dyn Sink>,
) -> Result<Session, (ErrorKind, String)> {
    let stopped = Arc::new(AtomicBool::new(false));
    let pending: Arc<Mutex<Option<IAsyncOperation<Geoposition>>>> = Arc::new(Mutex::new(None));
    let max_cached_age = options.max_cached_age_millis as i64;

    let thread_stopped = stopped.clone();
    let thread_pending = pending.clone();
    thread::Builder::new()
        .name("nucleus-location-once".into())
        .spawn(move || {
            // Runs `operation` as the session's cancellable pending lookup.
            let run = |operation: windows::core::Result<IAsyncOperation<Geoposition>>| {
                let operation = operation?;
                if let Ok(mut slot) = thread_pending.lock() {
                    *slot = Some(operation.clone());
                }
                if thread_stopped.load(Ordering::Acquire) {
                    let _ = operation.Cancel();
                }
                let result = operation.join();
                if let Ok(mut slot) = thread_pending.lock() {
                    *slot = None;
                }
                result
            };

            let mut delivered_cached = false;
            if max_cached_age > 0 {
                let cached = run(geolocator.GetGeopositionAsyncWithAgeAndTimeout(
                    TimeSpan { Duration: max_cached_age * TICKS_PER_MILLI },
                    TimeSpan { Duration: CACHED_LOOKUP_TIMEOUT_MILLIS * TICKS_PER_MILLI },
                ));
                if let Some(fix) = cached.ok().and_then(|position| to_fix(&position.Coordinate().ok()?, true)) {
                    if !thread_stopped.load(Ordering::Acquire) {
                        delivered_cached = true;
                        sink.location(fix);
                    }
                }
            }
            if thread_stopped.load(Ordering::Acquire) {
                return;
            }
            match run(geolocator.GetGeopositionAsync()) {
                Ok(position) => match position.Coordinate().ok().and_then(|c| to_fix(&c, false)) {
                    Some(fix) if !thread_stopped.load(Ordering::Acquire) => sink.location(fix),
                    Some(_) => {}
                    None if !delivered_cached => {
                        sink.error(ErrorKind::TemporarilyUnavailable, "the position had no coordinates")
                    }
                    None => {}
                },
                Err(error) if !thread_stopped.load(Ordering::Acquire) && error.code() != E_ABORT => {
                    let (kind, message) = to_error(error);
                    sink.error(kind, &message);
                }
                Err(_) => {}
            }
        })
        .map_err(|e| (ErrorKind::Unknown, e.to_string()))?;

    Ok(Session { inner: Inner::OneShot { stopped, pending } })
}

fn start_continuous(
    geolocator: Geolocator,
    options: &SessionOptions,
    sink: Arc<dyn Sink>,
) -> Result<Session, (ErrorKind, String)> {
    // Both are hints: 0 keeps the provider's own default.
    let _ = geolocator.SetReportInterval(options.interval_millis.min(u32::MAX as u64) as u32);
    if options.distance_meters > 0.0 {
        let _ = geolocator.SetMovementThreshold(options.distance_meters);
    }

    let position_sink = sink.clone();
    let position_handler = TypedEventHandler::new(move |_: Ref<Geolocator>, args: Ref<PositionChangedEventArgs>| {
        let fix = args
            .as_ref()
            .and_then(|args| args.Position().ok())
            .and_then(|position| position.Coordinate().ok())
            .and_then(|coordinate| to_fix(&coordinate, false));
        if let Some(fix) = fix {
            position_sink.location(fix);
        }
        Ok(())
    });
    let status_handler = TypedEventHandler::new(move |_: Ref<Geolocator>, args: Ref<StatusChangedEventArgs>| {
        let status = args.as_ref().and_then(|args| args.Status().ok());
        match status {
            Some(PositionStatus::Disabled) => {
                sink.error(ErrorKind::AuthorizationDenied, "location access is turned off")
            }
            Some(PositionStatus::NotAvailable) => {
                sink.error(ErrorKind::PermanentlyUnavailable, "location is not available on this device")
            }
            Some(PositionStatus::NoData) => {
                sink.error(ErrorKind::TemporarilyUnavailable, "no location source has a position")
            }
            _ => {}
        }
        Ok(())
    });

    let position_token = geolocator.PositionChanged(&position_handler).map_err(to_error)?;
    let status_token = match geolocator.StatusChanged(&status_handler) {
        Ok(token) => token,
        Err(error) => {
            let _ = geolocator.RemovePositionChanged(position_token);
            return Err(to_error(error));
        }
    };
    Ok(Session { inner: Inner::Continuous { geolocator, position_token, status_token } })
}

fn to_fix(coordinate: &Geocoordinate, cached: bool) -> Option<Fix> {
    let position = coordinate.Point().ok()?.Position().ok()?;
    if !valid_coordinates(position.Latitude, position.Longitude) {
        return None;
    }
    let vertical_accuracy = coordinate.AltitudeAccuracy().ok().and_then(|r| r.Value().ok());
    Some(Fix {
        latitude: position.Latitude,
        longitude: position.Longitude,
        // Geopoint always carries an altitude; it only means something when its accuracy is known.
        altitude: vertical_accuracy.map(|_| position.Altitude),
        horizontal_accuracy: coordinate.Accuracy().ok(),
        vertical_accuracy,
        bearing: coordinate.Heading().ok().and_then(|r| r.Value().ok()).filter(|v| v.is_finite()),
        speed: coordinate.Speed().ok().and_then(|r| r.Value().ok()).filter(|v| v.is_finite() && *v >= 0.0),
        time_millis: coordinate
            .Timestamp()
            .map(|t| (t.UniversalTime - UNIX_EPOCH_TICKS) / TICKS_PER_MILLI)
            .unwrap_or_else(|_| crate::now_millis()),
        cached,
    })
}

fn to_error(error: windows::core::Error) -> (ErrorKind, String) {
    let kind = match error.code() {
        E_ACCESSDENIED => ErrorKind::AuthorizationDenied,
        ERROR_TIMEOUT => ErrorKind::TemporarilyUnavailable,
        _ => ErrorKind::Unknown,
    };
    (kind, format!("{} ({:#010x})", error.message(), error.code().0 as u32))
}

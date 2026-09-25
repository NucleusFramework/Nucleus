//! Linux backend: the XDG Location portal, falling back to GeoClue directly.
//!
//! The portal is preferred everywhere, sandboxed or not: it owns the desktop's consent UI and
//! reaches GeoClue under its own (allow-listed) identity, so an unpackaged app needs no `.desktop`
//! file for it. GeoClue is used directly only when the portal is missing (no service, no Location
//! interface) and the app is not sandboxed — never after the portal answered "no", which would
//! route around the user's decision through a different consent path (robius-location's rule).
//!
//! Each session owns a private D-Bus connection and a worker thread reading it. Stopping a
//! session closes that connection: both the portal and GeoClue drop whatever a vanished peer
//! had open, and the worker's message iterator ends with it.

use crate::{
    valid_coordinates, AuthCallback, ErrorKind, Fix, SessionOptions, Sink, AUTH_BACKGROUND,
    AUTH_DENIED, AUTH_FOREGROUND, AUTH_NOT_DETERMINED,
};
use std::collections::HashMap;
use std::env;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use zbus::blocking::{Connection, MessageIterator, Proxy};
use zbus::message::{Message, Type as MessageType};
use zbus::zvariant::{OwnedObjectPath, OwnedValue, Value};
use zbus::MatchRule;

const PORTAL_DESTINATION: &str = "org.freedesktop.portal.Desktop";
const PORTAL_PATH: &str = "/org/freedesktop/portal/desktop";
const PORTAL_LOCATION: &str = "org.freedesktop.portal.Location";
const PORTAL_REQUEST: &str = "org.freedesktop.portal.Request";
const PORTAL_SESSION: &str = "org.freedesktop.portal.Session";
// Accuracy levels of the portal specification.
const PORTAL_ACCURACY_CITY: u32 = 2;
const PORTAL_ACCURACY_EXACT: u32 = 5;

const GEOCLUE_DESTINATION: &str = "org.freedesktop.GeoClue2";
const GEOCLUE_MANAGER_PATH: &str = "/org/freedesktop/GeoClue2/Manager";
const GEOCLUE_MANAGER: &str = "org.freedesktop.GeoClue2.Manager";
const GEOCLUE_CLIENT: &str = "org.freedesktop.GeoClue2.Client";
const GEOCLUE_LOCATION: &str = "org.freedesktop.GeoClue2.Location";
// GClueAccuracyLevel.
const GEOCLUE_ACCURACY_CITY: u32 = 4;
const GEOCLUE_ACCURACY_EXACT: u32 = 8;

const DBUS_DESTINATION: &str = "org.freedesktop.DBus";
const DBUS_PATH: &str = "/org/freedesktop/DBus";
const DBUS_INTERFACE: &str = "org.freedesktop.DBus";
const PROPERTIES_INTERFACE: &str = "org.freedesktop.DBus.Properties";

const SIGNAL_QUEUE_CAPACITY: usize = 64;

/// Set once a portal `Start` or a GeoClue `Start` succeeded in this process: neither backend
/// has a query that tells "allowed" from "never asked" without asking.
static GRANTED: AtomicBool = AtomicBool::new(false);
static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);

pub struct Session {
    connection: Arc<Mutex<Option<Connection>>>,
    stopped: Arc<AtomicBool>,
}

impl Drop for Session {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::Release);
        if let Some(connection) = self.connection.lock().ok().and_then(|mut c| c.take()) {
            let _ = connection.close();
        }
    }
}

pub fn is_available() -> bool {
    portal_version().is_ok() || (!is_sandboxed() && geoclue_present())
}

pub fn authorization_status() -> i32 {
    if GRANTED.load(Ordering::Acquire) {
        AUTH_FOREGROUND
    } else {
        AUTH_NOT_DETERMINED
    }
}

pub fn request_authorization(background: bool, precise: bool, desktop_id: String, done: AuthCallback) {
    let options = SessionOptions {
        precise,
        continuous: false,
        interval_millis: 0,
        distance_meters: 0.0,
        max_cached_age_millis: 0,
        desktop_id,
    };
    // A failed spawn drops the callback; the Kotlin side times the request out.
    let _ = thread::Builder::new().name("nucleus-location-auth".into()).spawn(move || {
        let granted = if background { AUTH_BACKGROUND } else { AUTH_FOREGROUND };
        let status = match open(&options) {
            Ok(Backend::Portal(portal)) => match portal.start() {
                Ok(()) => match portal.await_response(|| false) {
                    Ok(()) => granted,
                    Err((ErrorKind::AuthorizationDenied, _)) => AUTH_DENIED,
                    Err(_) => AUTH_NOT_DETERMINED,
                },
                Err((ErrorKind::AuthorizationDenied, _)) => AUTH_DENIED,
                Err(_) => AUTH_NOT_DETERMINED,
            },
            Ok(Backend::GeoClue(geoclue)) => match geoclue.start() {
                Ok(()) => granted,
                Err((ErrorKind::AuthorizationDenied, _)) => AUTH_DENIED,
                Err(_) => AUTH_NOT_DETERMINED,
            },
            Err(_) => AUTH_NOT_DETERMINED,
        };
        done(status);
    });
}

pub fn start(options: SessionOptions, sink: Arc<dyn Sink>) -> Result<Session, (ErrorKind, String)> {
    let connection = Arc::new(Mutex::new(None));
    let stopped = Arc::new(AtomicBool::new(false));
    let worker_connection = connection.clone();
    let worker_stopped = stopped.clone();
    thread::Builder::new()
        .name("nucleus-location".into())
        .spawn(move || {
            let stopped = || worker_stopped.load(Ordering::Acquire);
            let backend = match open(&options) {
                Ok(backend) => backend,
                Err((kind, message)) => {
                    if !stopped() {
                        sink.error(kind, &message);
                    }
                    return;
                }
            };
            if let Ok(mut slot) = worker_connection.lock() {
                if stopped() {
                    return;
                }
                *slot = Some(backend.connection().clone());
            }
            let result = match backend {
                Backend::Portal(portal) => portal.run(&options, &*sink, &stopped),
                Backend::GeoClue(geoclue) => geoclue.run(&options, &*sink, &stopped),
            };
            if let Err((kind, message)) = result {
                if !stopped() {
                    sink.error(kind, &message);
                }
            }
        })
        .map_err(|e| (ErrorKind::Unknown, e.to_string()))?;
    Ok(Session { connection, stopped })
}

// ---------------------------------------------------------------------------------------------
// Backend selection
// ---------------------------------------------------------------------------------------------

enum Backend {
    Portal(Portal),
    GeoClue(GeoClue),
}

impl Backend {
    fn connection(&self) -> &Connection {
        match self {
            Self::Portal(portal) => &portal.connection,
            Self::GeoClue(geoclue) => &geoclue.connection,
        }
    }
}

fn open(options: &SessionOptions) -> Result<Backend, (ErrorKind, String)> {
    match Portal::open(options) {
        Ok(portal) => Ok(Backend::Portal(portal)),
        Err(PortalOpenError::Missing(reason)) if !is_sandboxed() => GeoClue::open(options)
            .map(Backend::GeoClue)
            .map_err(|(kind, message)| (kind, format!("{message} (portal: {reason})"))),
        Err(PortalOpenError::Missing(reason)) => Err((ErrorKind::PermanentlyUnavailable, reason)),
        Err(PortalOpenError::Failed(kind, message)) => Err((kind, message)),
    }
}

/// Sandboxed apps must stay on the portal, whose permission decision a system-bus call to
/// GeoClue would otherwise bypass.
fn is_sandboxed() -> bool {
    Path::new("/.flatpak-info").exists() || env::var_os("FLATPAK_ID").is_some() || env::var_os("SNAP").is_some()
}

fn next_token(kind: &str) -> String {
    format!("nucleus_location_{kind}_{}_{}", std::process::id(), NEXT_TOKEN.fetch_add(1, Ordering::Relaxed))
}

// ---------------------------------------------------------------------------------------------
// XDG portal
// ---------------------------------------------------------------------------------------------

enum PortalOpenError {
    /// No portal, or one without the Location interface: GeoClue may be tried instead.
    Missing(String),
    Failed(ErrorKind, String),
}

struct Portal {
    connection: Connection,
    messages: MessageIterator,
    session: OwnedObjectPath,
    request: OwnedObjectPath,
    request_token: String,
}

fn portal_version() -> zbus::Result<u32> {
    let connection = Connection::session()?;
    Proxy::new(&connection, PORTAL_DESTINATION, PORTAL_PATH, PORTAL_LOCATION)?.get_property("version")
}

impl Portal {
    /// Connects, checks the Location interface and creates a session — nothing the user sees yet.
    fn open(options: &SessionOptions) -> Result<Self, PortalOpenError> {
        let connection = Connection::session().map_err(|e| PortalOpenError::Missing(e.to_string()))?;
        let proxy = Proxy::new(&connection, PORTAL_DESTINATION, PORTAL_PATH, PORTAL_LOCATION)
            .map_err(|e| PortalOpenError::Failed(ErrorKind::Unknown, e.to_string()))?;
        match proxy.get_property::<u32>("version") {
            Ok(version) if version >= 1 => {}
            Ok(version) => return Err(PortalOpenError::Missing(format!("Location portal version {version}"))),
            Err(error) if is_missing_service(&error) => return Err(PortalOpenError::Missing(error.to_string())),
            Err(error) => return Err(PortalOpenError::Failed(classify(&error), error.to_string())),
        }

        // Subscribed before any call, so neither the `Response` nor an early fix can be missed.
        let rule = MatchRule::builder()
            .msg_type(MessageType::Signal)
            .sender(PORTAL_DESTINATION)
            .and_then(|b| b.path_namespace(PORTAL_PATH))
            .map(|b| b.build())
            .map_err(|e| PortalOpenError::Failed(ErrorKind::Unknown, e.to_string()))?;
        let messages = MessageIterator::for_match_rule(rule, &connection, Some(SIGNAL_QUEUE_CAPACITY))
            .map_err(|e| PortalOpenError::Failed(classify(&e), e.to_string()))?;

        let accuracy = if options.precise { PORTAL_ACCURACY_EXACT } else { PORTAL_ACCURACY_CITY };
        let mut session_options: HashMap<&str, Value> = HashMap::new();
        session_options.insert("session_handle_token", Value::from(next_token("session")));
        session_options.insert("accuracy", Value::from(accuracy));
        session_options.insert("distance-threshold", Value::from(options.distance_meters.min(u32::MAX as f64) as u32));
        session_options.insert("time-threshold", Value::from((options.interval_millis / 1000).min(u32::MAX as u64) as u32));
        let session: OwnedObjectPath = proxy
            .call("CreateSession", &session_options)
            .map_err(|e| PortalOpenError::Failed(classify(&e), e.to_string()))?;

        let request_token = next_token("request");
        let sender = connection
            .unique_name()
            .map(|name| name.trim_start_matches(':').replace('.', "_"))
            .unwrap_or_default();
        let request = OwnedObjectPath::try_from(format!("{PORTAL_PATH}/request/{sender}/{request_token}"))
            .map_err(|e| PortalOpenError::Failed(ErrorKind::Unknown, e.to_string()))?;
        Ok(Self { connection, messages, session, request, request_token })
    }

    /// Starts the session; the portal answers on the request object once the user has decided.
    fn start(&self) -> Result<(), (ErrorKind, String)> {
        let proxy = Proxy::new(&self.connection, PORTAL_DESTINATION, PORTAL_PATH, PORTAL_LOCATION).map_err(to_error)?;
        let mut start_options: HashMap<&str, Value> = HashMap::new();
        start_options.insert("handle_token", Value::from(self.request_token.as_str()));
        let _request: OwnedObjectPath = proxy
            .call("Start", &(&self.session, "", start_options))
            .map_err(to_error)?;
        Ok(())
    }

    /// Waits for the `Start` response, discarding anything else that arrives first.
    fn await_response(&self, stopped: impl Fn() -> bool) -> Result<(), (ErrorKind, String)> {
        for message in self.messages.clone() {
            if stopped() {
                return Ok(());
            }
            let Ok(message) = message else { continue };
            if let Some(response) = self.response(&message) {
                return response;
            }
        }
        Err((ErrorKind::TemporarilyUnavailable, "the portal connection closed".into()))
    }

    fn response(&self, message: &Message) -> Option<Result<(), (ErrorKind, String)>> {
        let header = message.header();
        if header.interface().map(|i| i.as_str()) != Some(PORTAL_REQUEST)
            || header.member().map(|m| m.as_str()) != Some("Response")
            || header.path().map(|p| p.as_str()) != Some(self.request.as_str())
        {
            return None;
        }
        let code = message
            .body()
            .deserialize::<(u32, HashMap<String, OwnedValue>)>()
            .map(|(code, _)| code)
            .unwrap_or(u32::MAX);
        Some(match code {
            0 => {
                GRANTED.store(true, Ordering::Release);
                Ok(())
            }
            1 => Err((ErrorKind::AuthorizationDenied, "location access was denied".into())),
            _ => Err((ErrorKind::Unknown, format!("the location portal refused to start ({code})"))),
        })
    }

    fn run(self, options: &SessionOptions, sink: &dyn Sink, stopped: &dyn Fn() -> bool) -> Result<(), (ErrorKind, String)> {
        self.start()?;
        let mut started = false;
        for message in self.messages.clone() {
            if stopped() {
                return Ok(());
            }
            let Ok(message) = message else { continue };
            if !started {
                if let Some(response) = self.response(&message) {
                    response?;
                    started = true;
                }
                continue;
            }
            let header = message.header();
            match (header.interface().map(|i| i.as_str()), header.member().map(|m| m.as_str())) {
                (Some(PORTAL_LOCATION), Some("LocationUpdated")) => {
                    let Ok((session, values)) =
                        message.body().deserialize::<(OwnedObjectPath, HashMap<String, OwnedValue>)>()
                    else {
                        continue;
                    };
                    if session != self.session {
                        continue;
                    }
                    if let Some(fix) = parse_fix(&values, -1.0) {
                        sink.location(fix);
                        if !options.continuous {
                            return Ok(());
                        }
                    }
                }
                (Some(PORTAL_SESSION), Some("Closed"))
                    if header.path().map(|p| p.as_str()) == Some(self.session.as_str()) =>
                {
                    return Err((ErrorKind::TemporarilyUnavailable, "the location portal closed the session".into()));
                }
                _ => {}
            }
        }
        Ok(())
    }
}

// ---------------------------------------------------------------------------------------------
// GeoClue
// ---------------------------------------------------------------------------------------------

struct GeoClue {
    connection: Connection,
    client: OwnedObjectPath,
    messages: MessageIterator,
}

fn geoclue_present() -> bool {
    let Ok(connection) = Connection::system() else {
        return false;
    };
    let Ok(proxy) = Proxy::new(&connection, DBUS_DESTINATION, DBUS_PATH, DBUS_INTERFACE) else {
        return false;
    };
    // GeoClue is bus-activated: usually not running until someone asks for it.
    proxy.call::<_, _, bool>("NameHasOwner", &(GEOCLUE_DESTINATION,)).unwrap_or(false)
        || proxy
            .call::<_, _, Vec<String>>("ListActivatableNames", &())
            .is_ok_and(|names| names.iter().any(|name| name == GEOCLUE_DESTINATION))
}

impl GeoClue {
    fn open(options: &SessionOptions) -> Result<Self, (ErrorKind, String)> {
        let connection = Connection::system().map_err(|e| (ErrorKind::PermanentlyUnavailable, e.to_string()))?;
        let manager = Proxy::new(&connection, GEOCLUE_DESTINATION, GEOCLUE_MANAGER_PATH, GEOCLUE_MANAGER).map_err(to_error)?;
        let client: OwnedObjectPath = manager.call("GetClient", &()).map_err(to_error)?;
        let proxy = Proxy::new(&connection, GEOCLUE_DESTINATION, client.as_str(), GEOCLUE_CLIENT).map_err(to_error)?;
        let accuracy = if options.precise { GEOCLUE_ACCURACY_EXACT } else { GEOCLUE_ACCURACY_CITY };
        proxy.set_property("DesktopId", options.desktop_id.as_str()).map_err(|e| to_error(e.into()))?;
        proxy.set_property("RequestedAccuracyLevel", accuracy).map_err(|e| to_error(e.into()))?;
        proxy
            .set_property("DistanceThreshold", options.distance_meters.min(u32::MAX as f64) as u32)
            .map_err(|e| to_error(e.into()))?;
        proxy
            .set_property("TimeThreshold", (options.interval_millis / 1000).min(u32::MAX as u64) as u32)
            .map_err(|e| to_error(e.into()))?;

        let rule = MatchRule::builder()
            .msg_type(MessageType::Signal)
            .sender(GEOCLUE_DESTINATION)
            .and_then(|b| b.path(client.as_str()))
            .and_then(|b| b.interface(GEOCLUE_CLIENT))
            .and_then(|b| b.member("LocationUpdated"))
            .map(|b| b.build())
            .map_err(to_error)?;
        let messages = MessageIterator::for_match_rule(rule, &connection, Some(SIGNAL_QUEUE_CAPACITY)).map_err(to_error)?;
        drop(proxy);
        Ok(Self { connection, client, messages })
    }

    /// Starts the client. GeoClue asks its agent (and through it the user) before answering, so
    /// a successful `Start` is the authorization.
    fn start(&self) -> Result<(), (ErrorKind, String)> {
        let proxy = Proxy::new(&self.connection, GEOCLUE_DESTINATION, self.client.as_str(), GEOCLUE_CLIENT).map_err(to_error)?;
        proxy.call::<_, _, ()>("Start", &()).map_err(to_error)?;
        GRANTED.store(true, Ordering::Release);
        Ok(())
    }

    fn run(self, options: &SessionOptions, sink: &dyn Sink, stopped: &dyn Fn() -> bool) -> Result<(), (ErrorKind, String)> {
        self.start()?;
        for message in self.messages.clone() {
            if stopped() {
                return Ok(());
            }
            let Ok(message) = message else { continue };
            let Ok((_old, new)) = message.body().deserialize::<(OwnedObjectPath, OwnedObjectPath)>() else {
                continue;
            };
            let Ok(properties) = Proxy::new(&self.connection, GEOCLUE_DESTINATION, new.as_str(), PROPERTIES_INTERFACE) else {
                continue;
            };
            let Ok(values) = properties.call::<_, _, HashMap<String, OwnedValue>>("GetAll", &(GEOCLUE_LOCATION,)) else {
                continue;
            };
            // GeoClue marks an unknown altitude with -DBL_MAX.
            if let Some(fix) = parse_fix(&values, f64::MIN) {
                sink.location(fix);
                if !options.continuous {
                    return Ok(());
                }
            }
        }
        Ok(())
    }
}

// ---------------------------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------------------------

/// Parses a portal / GeoClue location dictionary. Both use the same keys: unknown speed and
/// heading are -1, an unknown altitude is `unknown_altitude`, `Timestamp` is `(seconds, µs)`.
fn parse_fix(values: &HashMap<String, OwnedValue>, unknown_altitude: f64) -> Option<Fix> {
    let number = |key: &str| values.get(key).and_then(|v| f64::try_from(v).ok()).filter(|v| v.is_finite());
    let latitude = number("Latitude")?;
    let longitude = number("Longitude")?;
    if !valid_coordinates(latitude, longitude) {
        return None;
    }
    let time_millis = values
        .get("Timestamp")
        .and_then(|v| v.try_clone().ok())
        .and_then(|v| <(u64, u64)>::try_from(v).ok())
        .map(|(seconds, micros)| (seconds * 1000 + micros / 1000) as i64)
        .unwrap_or_else(crate::now_millis);
    Some(Fix {
        latitude,
        longitude,
        altitude: number("Altitude").filter(|v| *v != unknown_altitude && *v > -1.0e300),
        horizontal_accuracy: number("Accuracy").filter(|v| *v >= 0.0),
        vertical_accuracy: None,
        bearing: number("Heading").filter(|v| (0.0..360.0).contains(v)),
        speed: number("Speed").filter(|v| *v >= 0.0),
        time_millis,
        cached: false,
    })
}

fn error_name(error: &zbus::Error) -> Option<&str> {
    match error {
        zbus::Error::MethodError(name, _, _) => Some(name.as_str()),
        zbus::Error::FDO(fdo) => match fdo.as_ref() {
            zbus::fdo::Error::ServiceUnknown(_) => Some("org.freedesktop.DBus.Error.ServiceUnknown"),
            zbus::fdo::Error::UnknownInterface(_) => Some("org.freedesktop.DBus.Error.UnknownInterface"),
            zbus::fdo::Error::UnknownMethod(_) => Some("org.freedesktop.DBus.Error.UnknownMethod"),
            zbus::fdo::Error::UnknownObject(_) => Some("org.freedesktop.DBus.Error.UnknownObject"),
            zbus::fdo::Error::UnknownProperty(_) => Some("org.freedesktop.DBus.Error.UnknownProperty"),
            zbus::fdo::Error::InvalidArgs(_) => Some("org.freedesktop.DBus.Error.InvalidArgs"),
            zbus::fdo::Error::AccessDenied(_) => Some("org.freedesktop.DBus.Error.AccessDenied"),
            zbus::fdo::Error::NameHasNoOwner(_) => Some("org.freedesktop.DBus.Error.NameHasNoOwner"),
            _ => None,
        },
        _ => None,
    }
}

fn is_missing_service(error: &zbus::Error) -> bool {
    matches!(
        error_name(error),
        Some(
            "org.freedesktop.DBus.Error.ServiceUnknown"
                | "org.freedesktop.DBus.Error.NameHasNoOwner"
                | "org.freedesktop.DBus.Error.UnknownInterface"
                | "org.freedesktop.DBus.Error.UnknownMethod"
                | "org.freedesktop.DBus.Error.UnknownObject"
                | "org.freedesktop.DBus.Error.UnknownProperty"
                | "org.freedesktop.DBus.Error.InvalidArgs"
                | "org.freedesktop.DBus.Error.Spawn.ServiceNotFound"
        )
    )
}

fn classify(error: &zbus::Error) -> ErrorKind {
    match error_name(error) {
        Some(
            "org.freedesktop.DBus.Error.AccessDenied"
            | "org.freedesktop.DBus.Error.AuthFailed"
            | "org.freedesktop.portal.Error.NotAllowed"
            | "org.freedesktop.portal.Error.Cancelled"
            | "org.freedesktop.GeoClue2.Error.AccessDenied"
            | "org.freedesktop.GeoClue2.Error.NotAuthorized",
        ) => ErrorKind::AuthorizationDenied,
        Some("org.freedesktop.DBus.Error.NoNetwork") => ErrorKind::Network,
        Some(
            "org.freedesktop.DBus.Error.NoReply"
            | "org.freedesktop.DBus.Error.Timeout"
            | "org.freedesktop.DBus.Error.TimedOut"
            | "org.freedesktop.GeoClue2.Error.NotAvailable",
        ) => ErrorKind::TemporarilyUnavailable,
        _ if is_missing_service(error) => ErrorKind::PermanentlyUnavailable,
        _ => ErrorKind::Unknown,
    }
}

fn to_error(error: zbus::Error) -> (ErrorKind, String) {
    (classify(&error), error.to_string())
}

//! Linux backend: the XDG Location portal, falling back to GeoClue directly.
//!
//! The portal is preferred everywhere, sandboxed or not: it owns the desktop's consent UI and
//! reaches GeoClue under its own (allow-listed) identity, so an unpackaged app needs no `.desktop`
//! file for it. GeoClue is used directly only when the portal is missing (no service, no Location
//! interface) and the app is not sandboxed — never after the portal answered "no", which would
//! route around the user's decision through a different consent path (robius-location's rule).
//!
//! xdg-desktop-portal (1.21) drives one GeoClue client for all of its Location sessions, those of
//! every app: a session started next to a running one gets no initial fix (the client is already
//! started, so nothing changes), each session's thresholds overwrite the client's, and closing
//! any session stops the client under all the others. This process therefore keeps a single
//! portal session (`Hub`) shared by every request, opened at the most precise accuracy asked for,
//! without thresholds (each request filters its own fixes), and closed when the last request ends.
//! GeoClue reached directly gives each connection a client of its own: a direct session owns a
//! private D-Bus connection and a worker thread reading it, and stopping closes that connection.

use crate::{
    valid_coordinates, AuthCallback, ErrorKind, Fix, SessionOptions, Sink, AUTH_BACKGROUND,
    AUTH_DENIED, AUTH_FOREGROUND, AUTH_NOT_DETERMINED, AUTH_RESTRICTED,
};
use std::collections::HashMap;
use std::env;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
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
/// The `NotAllowed` message of xdg-desktop-portal when location is turned off on the desktop
/// (GNOME *Privacy → Location*, or a lockdown policy).
const SERVICES_DISABLED: &str = "Location services disabled";

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
    opened: Arc<Mutex<Option<Opened>>>,
    stopped: Arc<AtomicBool>,
}

/// What the worker opened, for `Session::drop` to release.
enum Opened {
    /// A member of the shared portal session.
    Portal(u64),
    /// The private connection of a direct GeoClue session.
    GeoClue(Connection),
}

impl Drop for Session {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::Release);
        match self.opened.lock().ok().and_then(|mut opened| opened.take()) {
            Some(Opened::Portal(member)) => hub_leave(member),
            Some(Opened::GeoClue(connection)) => {
                let _ = connection.close();
            }
            None => {}
        }
    }
}

/// A session is created (and closed at once) rather than only reading the portal's version:
/// only `CreateSession` reports location turned off on the desktop. Closing a session that was
/// never started does not touch the portal's GeoClue client.
pub fn is_available() -> bool {
    match Portal::open(&probe_options()) {
        Ok(portal) => {
            portal.close();
            true
        }
        Err(PortalOpenError::Missing(_)) => !is_sandboxed() && geoclue_present(),
        Err(PortalOpenError::Failed(..)) => false,
    }
}

pub fn authorization_status() -> i32 {
    if GRANTED.load(Ordering::Acquire) {
        AUTH_FOREGROUND
    } else {
        AUTH_NOT_DETERMINED
    }
}

/// `parent_window` is the portal's parent window identifier (`x11:<xid>`, `wayland:<handle>`) for
/// the consent dialog, or empty for none.
pub fn request_authorization(
    background: bool,
    precise: bool,
    desktop_id: String,
    parent_window: String,
    done: AuthCallback,
) {
    let options = SessionOptions { precise, desktop_id, ..probe_options() };
    // A failed spawn drops the callback; the Kotlin side times the request out.
    let _ = thread::Builder::new().name("nucleus-location-auth".into()).spawn(move || {
        let granted = if background { AUTH_BACKGROUND } else { AUTH_FOREGROUND };
        match open(&options) {
            // Answered by the shared session's `Response`, or at once when it already has one.
            Ok(Backend::Portal(portal)) => {
                let (_, deliveries) = hub_join(portal, Member::Authorization { precise, granted, done }, &parent_window);
                deliver(deliveries);
            }
            Ok(Backend::GeoClue(geoclue)) => done(authorization_after(geoclue.start(), granted)),
            Err(error) => done(authorization_after(Err(error), granted)),
        }
    });
}

/// The authorization a request ends with.
fn authorization_after(result: Result<(), (ErrorKind, String)>, granted: i32) -> i32 {
    match result {
        Ok(()) => granted,
        // Location turned off on the desktop: refused, as Windows and Core Location report it.
        Err((ErrorKind::AuthorizationDenied, _)) => AUTH_DENIED,
        Err((ErrorKind::PermanentlyUnavailable, message)) if message.contains(SERVICES_DISABLED) => AUTH_DENIED,
        Err((ErrorKind::PermanentlyUnavailable, _)) => AUTH_RESTRICTED,
        Err(_) => AUTH_NOT_DETERMINED,
    }
}

pub fn start(options: SessionOptions, sink: Arc<dyn Sink>) -> Result<Session, (ErrorKind, String)> {
    let opened = Arc::new(Mutex::new(None));
    let stopped = Arc::new(AtomicBool::new(false));
    let worker_opened = opened.clone();
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
            match backend {
                Backend::Portal(portal) => {
                    let Ok(mut slot) = worker_opened.lock() else { return };
                    if stopped() {
                        portal.close();
                        return;
                    }
                    let (member, deliveries) = hub_join(portal, Member::fixes(&options, sink), "");
                    *slot = Some(Opened::Portal(member));
                    // Outside the slot lock: a one-shot served here stops its session at once.
                    drop(slot);
                    deliver(deliveries);
                }
                Backend::GeoClue(geoclue) => {
                    if let Ok(mut slot) = worker_opened.lock() {
                        if stopped() {
                            return;
                        }
                        *slot = Some(Opened::GeoClue(geoclue.connection.clone()));
                    }
                    if let Err((kind, message)) = geoclue.run(&options, &*sink, &stopped) {
                        if !stopped() {
                            sink.error(kind, &message);
                        }
                    }
                }
            }
        })
        .map_err(|e| (ErrorKind::Unknown, e.to_string()))?;
    Ok(Session { opened, stopped })
}

// ---------------------------------------------------------------------------------------------
// Backend selection
// ---------------------------------------------------------------------------------------------

enum Backend {
    Portal(Portal),
    GeoClue(GeoClue),
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

fn probe_options() -> SessionOptions {
    SessionOptions {
        precise: false,
        continuous: false,
        interval_millis: 0,
        distance_meters: 0.0,
        max_cached_age_millis: 0,
        desktop_id: String::new(),
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

        // No distance / time threshold: they would apply to the portal's GeoClue client, shared
        // with every other session (see the module documentation).
        let accuracy = if options.precise { PORTAL_ACCURACY_EXACT } else { PORTAL_ACCURACY_CITY };
        let mut session_options: HashMap<&str, Value> = HashMap::new();
        session_options.insert("session_handle_token", Value::from(next_token("session")));
        session_options.insert("accuracy", Value::from(accuracy));
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
    /// The consent dialog, when one is shown, is parented to `parent_window`.
    fn start(&self, parent_window: &str) -> Result<(), (ErrorKind, String)> {
        let proxy = Proxy::new(&self.connection, PORTAL_DESTINATION, PORTAL_PATH, PORTAL_LOCATION).map_err(to_error)?;
        let mut start_options: HashMap<&str, Value> = HashMap::new();
        start_options.insert("handle_token", Value::from(self.request_token.as_str()));
        let _request: OwnedObjectPath = proxy
            .call("Start", &(&self.session, parent_window, start_options))
            .map_err(to_error)?;
        Ok(())
    }

    /// Closes the session and the connection.
    fn close(self) {
        close_portal(&self.connection, &self.session);
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
            // GNOME shows the consent dialog only for the focused app: a sandboxed app without a
            // focused window gets 2 at once, as it does when GeoClue fails to start.
            _ => Err((
                ErrorKind::Unknown,
                format!("the location portal ended the request without an answer ({code}): no consent dialog could be shown (GNOME only asks for the focused app) or GeoClue failed"),
            )),
        })
    }

    /// Reads the shared session until it fails or the hub moves on to another one.
    fn run(self, generation: u64, parent_window: &str) {
        if let Err((kind, message)) = self.start(parent_window) {
            hub_fail(generation, kind, &message);
            return;
        }
        for message in self.messages.clone() {
            if !hub_runs(generation) {
                return;
            }
            let Ok(message) = message else { continue };
            if let Some(response) = self.response(&message) {
                match response {
                    Ok(()) => hub_started(generation),
                    Err((kind, message)) => {
                        hub_fail(generation, kind, &message);
                        return;
                    }
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
                        hub_fix(generation, fix);
                    }
                }
                (Some(PORTAL_SESSION), Some("Closed"))
                    if header.path().map(|p| p.as_str()) == Some(self.session.as_str()) =>
                {
                    hub_fail(generation, ErrorKind::TemporarilyUnavailable, "the location portal closed the session");
                    return;
                }
                _ => {}
            }
        }
        hub_fail(generation, ErrorKind::TemporarilyUnavailable, "the portal connection closed");
    }
}

fn close_portal(connection: &Connection, session: &OwnedObjectPath) {
    if let Ok(proxy) = Proxy::new(connection, PORTAL_DESTINATION, session.as_str(), PORTAL_SESSION) {
        let _ = proxy.call::<_, _, ()>("Close", &());
    }
    let _ = connection.clone().close();
}

// ---------------------------------------------------------------------------------------------
// The shared portal session
// ---------------------------------------------------------------------------------------------

/// Every portal request of this process, served by one portal session (see the module docs).
struct Hub {
    next_member: u64,
    /// Identifies the session in `run`, so a replaced session's thread stops acting.
    generation: u64,
    members: Vec<(u64, Member)>,
    run: Option<Run>,
    /// The running session's last fix: the current location, since the portal reports every
    /// change to a running session. Handed to members joining it.
    last: Option<Fix>,
}

static HUB: Mutex<Hub> = Mutex::new(Hub { next_member: 1, generation: 0, members: Vec::new(), run: None, last: None });

/// The portal session the hub runs.
struct Run {
    generation: u64,
    precise: bool,
    /// The portal answered `Start` with success.
    started: bool,
    connection: Connection,
    session: OwnedObjectPath,
}

impl Run {
    fn close(self) {
        close_portal(&self.connection, &self.session);
    }
}

enum Member {
    /// A `request_authorization`, answered by the session's `Response`.
    Authorization { precise: bool, granted: i32, done: AuthCallback },
    /// A location session.
    Fixes {
        sink: Arc<dyn Sink>,
        precise: bool,
        continuous: bool,
        interval_millis: i64,
        distance_meters: f64,
        /// The last fix handed to this member, for its thresholds.
        last: Option<Fix>,
    },
}

/// A callback to run once the hub is unlocked: it may re-enter the hub (a one-shot stops its
/// session from its fix callback).
type Delivery = Box<dyn FnOnce() + Send>;

impl Member {
    fn fixes(options: &SessionOptions, sink: Arc<dyn Sink>) -> Self {
        Self::Fixes {
            sink,
            precise: options.precise,
            continuous: options.continuous,
            interval_millis: options.interval_millis.min(i64::MAX as u64) as i64,
            distance_meters: options.distance_meters,
            last: None,
        }
    }

    fn precise(&self) -> bool {
        match self {
            Self::Authorization { precise, .. } | Self::Fixes { precise, .. } => *precise,
        }
    }

    /// Queues `fix` for a location member that wants it; `false` once the member is done.
    fn offer(&mut self, fix: &Fix, deliveries: &mut Vec<Delivery>) -> bool {
        let Self::Fixes { sink, continuous, interval_millis, distance_meters, last, .. } = self else {
            return true;
        };
        if let Some(previous) = last {
            if fix.time_millis - previous.time_millis < *interval_millis || distance_between(previous, fix) < *distance_meters {
                return true;
            }
        }
        *last = Some(fix.clone());
        let (sink, fix) = (sink.clone(), fix.clone());
        deliveries.push(Box::new(move || sink.location(fix)));
        *continuous
    }

    fn fail(self, kind: ErrorKind, message: &str, deliveries: &mut Vec<Delivery>) {
        let message = message.to_owned();
        match self {
            Self::Authorization { granted, done, .. } => {
                deliveries.push(Box::new(move || done(authorization_after(Err((kind, message)), granted))))
            }
            Self::Fixes { sink, .. } => deliveries.push(Box::new(move || sink.error(kind, &message))),
        }
    }
}

fn hub() -> MutexGuard<'static, Hub> {
    HUB.lock().unwrap_or_else(|e| e.into_inner())
}

fn deliver(deliveries: Vec<Delivery>) {
    for delivery in deliveries {
        delivery();
    }
}

/// Adds `member`, served by the running session when it is precise enough — at once when that
/// session already answered — or by a new one opened from `portal`. `portal` is a session
/// created for this request: used for the new session, closed unstarted otherwise.
fn hub_join(portal: Portal, member: Member, parent_window: &str) -> (u64, Vec<Delivery>) {
    let mut deliveries: Vec<Delivery> = Vec::new();
    let mut unused = None;
    let mut replaced = None;
    let mut hub = hub();
    let id = hub.next_member;
    hub.next_member += 1;
    let precise = member.precise();
    let fits = hub.run.as_ref().is_some_and(|run| run.precise || !precise);
    let started = hub.run.as_ref().is_some_and(|run| run.started);
    if fits {
        unused = Some(portal);
        match member {
            Member::Authorization { done, granted, .. } if started => {
                deliveries.push(Box::new(move || done(granted)));
            }
            mut member => {
                let keep = match hub.last.clone() {
                    Some(fix) => member.offer(&fix, &mut deliveries),
                    None => true,
                };
                if keep {
                    hub.members.push((id, member));
                }
            }
        }
    } else {
        // No session yet, or a less precise one: the new member's session replaces it. Its
        // waiting members stay and are answered by the new session.
        hub.members.push((id, member));
        hub.generation += 1;
        hub.last = None;
        let generation = hub.generation;
        replaced = hub.run.replace(Run {
            generation,
            precise,
            started: false,
            connection: portal.connection.clone(),
            session: portal.session.clone(),
        });
        let parent_window = parent_window.to_owned();
        let spawned = thread::Builder::new()
            .name("nucleus-location".into())
            .spawn(move || portal.run(generation, &parent_window));
        if let Err(error) = spawned {
            drop(hub);
            hub_fail(generation, ErrorKind::Unknown, &error.to_string());
            hub = self::hub();
        }
    }
    drop(hub);
    // Closed before the new session starts, so its `Stop` reaches GeoClue first.
    if let Some(run) = replaced {
        run.close();
    }
    if let Some(portal) = unused {
        portal.close();
    }
    (id, deliveries)
}

fn hub_leave(member: u64) {
    let mut hub = hub();
    hub.members.retain(|(id, _)| *id != member);
    let idle = close_if_idle(&mut hub);
    drop(hub);
    if let Some(run) = idle {
        run.close();
    }
}

/// Takes the session out once no member is left.
fn close_if_idle(hub: &mut Hub) -> Option<Run> {
    if !hub.members.is_empty() {
        return None;
    }
    hub.last = None;
    hub.run.take()
}

fn hub_runs(generation: u64) -> bool {
    runs(&hub(), generation)
}

fn runs(hub: &Hub, generation: u64) -> bool {
    hub.run.as_ref().is_some_and(|run| run.generation == generation)
}

fn hub_started(generation: u64) {
    let mut deliveries: Vec<Delivery> = Vec::new();
    let mut hub = hub();
    let Some(run) = hub.run.as_mut().filter(|run| run.generation == generation) else { return };
    run.started = true;
    for (id, member) in std::mem::take(&mut hub.members) {
        match member {
            Member::Authorization { done, granted, .. } => deliveries.push(Box::new(move || done(granted))),
            fixes => hub.members.push((id, fixes)),
        }
    }
    let idle = close_if_idle(&mut hub);
    drop(hub);
    if let Some(run) = idle {
        run.close();
    }
    deliver(deliveries);
}

fn hub_fix(generation: u64, fix: Fix) {
    let mut deliveries: Vec<Delivery> = Vec::new();
    let mut hub = hub();
    if !runs(&hub, generation) {
        return;
    }
    hub.last = Some(fix.clone());
    hub.members.retain_mut(|(_, member)| member.offer(&fix, &mut deliveries));
    let idle = close_if_idle(&mut hub);
    drop(hub);
    if let Some(run) = idle {
        run.close();
    }
    deliver(deliveries);
}

/// Fails every member: the session is gone.
fn hub_fail(generation: u64, kind: ErrorKind, message: &str) {
    let mut deliveries: Vec<Delivery> = Vec::new();
    let mut hub = hub();
    if !runs(&hub, generation) {
        return;
    }
    for (_, member) in std::mem::take(&mut hub.members) {
        member.fail(kind, message, &mut deliveries);
    }
    let run = close_if_idle(&mut hub);
    drop(hub);
    if let Some(run) = run {
        run.close();
    }
    deliver(deliveries);
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

/// Great-circle distance in meters.
fn distance_between(a: &Fix, b: &Fix) -> f64 {
    const EARTH_RADIUS_METERS: f64 = 6_371_000.0;
    let (lat1, lat2) = (a.latitude.to_radians(), b.latitude.to_radians());
    let half_dlat = (lat2 - lat1) / 2.0;
    let half_dlon = (b.longitude - a.longitude).to_radians() / 2.0;
    let h = half_dlat.sin().powi(2) + lat1.cos() * lat2.cos() * half_dlon.sin().powi(2);
    2.0 * EARTH_RADIUS_METERS * h.sqrt().min(1.0).asin()
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
    if let zbus::Error::MethodError(name, Some(message), _) = error {
        if name.as_str() == "org.freedesktop.portal.Error.NotAllowed" && message.contains(SERVICES_DISABLED) {
            return ErrorKind::PermanentlyUnavailable;
        }
    }
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

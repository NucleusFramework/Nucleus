//! The Flatpak portal (`org.freedesktop.portal.Flatpak`) and the OpenURI portal, as seen from
//! inside the sandbox.
//!
//! An update monitor owns a private session-bus connection and a thread reading it: the portal
//! sends `UpdateAvailable` and `Progress` to the connection that created the monitor, and closing
//! that connection is what ends the thread (and, on the portal side, the monitor).

use std::collections::HashMap;
use std::fs::File;
use std::os::fd::{AsFd, BorrowedFd};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;
use zbus::blocking::{Connection, MessageIterator, Proxy};
use zbus::message::Type as MessageType;
use zbus::zvariant::{Fd, OwnedObjectPath, OwnedValue, Value};
use zbus::MatchRule;

const FLATPAK_DESTINATION: &str = "org.freedesktop.portal.Flatpak";
const FLATPAK_PATH: &str = "/org/freedesktop/portal/Flatpak";
const FLATPAK_INTERFACE: &str = "org.freedesktop.portal.Flatpak";
const MONITOR_INTERFACE: &str = "org.freedesktop.portal.Flatpak.UpdateMonitor";
const DESKTOP_DESTINATION: &str = "org.freedesktop.portal.Desktop";
const DESKTOP_PATH: &str = "/org/freedesktop/portal/desktop";
const OPEN_URI_INTERFACE: &str = "org.freedesktop.portal.OpenURI";
/// `FLATPAK_SPAWN_FLAGS_LATEST_VERSION`: run the newest installed commit, not the running one.
const SPAWN_FLAGS_LATEST_VERSION: u32 = 1 << 1;
const SIGNAL_QUEUE_CAPACITY: usize = 64;
/// D-Bus name reported for failures that did not come from the portal (no bus, broken reply).
pub const ERROR_BUS: &str = "dev.nucleusframework.Flatpak.Error.Bus";

static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);

/// A failed portal call: the D-Bus error name and its message.
pub struct PortalError {
    pub name: String,
    pub message: String,
}

impl From<zbus::Error> for PortalError {
    fn from(error: zbus::Error) -> Self {
        match error {
            zbus::Error::MethodError(name, message, _) => PortalError {
                name: name.to_string(),
                message: message.unwrap_or_default(),
            },
            other => PortalError { name: ERROR_BUS.to_string(), message: other.to_string() },
        }
    }
}

/// One `Progress` signal. `op`, `n_ops` and `progress` are absent (0) when the portal fails
/// before the transaction is planned.
pub struct Progress {
    pub n_ops: u32,
    pub op: u32,
    pub progress: u32,
    pub status: u32,
    pub error: Option<String>,
    pub error_message: Option<String>,
}

/// What a monitor reports, from its reader thread.
pub trait Events: Send + 'static {
    fn update_available(&self, running: &str, local: &str, remote: &str);
    fn progress(&self, progress: Progress);
    /// The connection ended without [Monitor::close]: the portal or the bus went away.
    fn closed(&self, reason: &str);
}

/// `version` of the Flatpak portal, `0` when it is not reachable.
pub fn portal_version() -> u32 {
    let Ok(connection) = Connection::session() else { return 0 };
    Proxy::new(&connection, FLATPAK_DESTINATION, FLATPAK_PATH, FLATPAK_INTERFACE)
        .and_then(|proxy| proxy.get_property::<u32>("version"))
        .unwrap_or(0)
}

pub struct Monitor {
    connection: Connection,
    path: OwnedObjectPath,
    closing: Arc<AtomicBool>,
}

impl Monitor {
    pub fn start(events: impl Events) -> Result<Monitor, PortalError> {
        let connection = Connection::session()?;
        // Subscribed before the monitor exists, so no signal can be missed. Filtered on the
        // monitor's path below: the path the portal returns is the one that counts.
        let rule = MatchRule::builder()
            .msg_type(MessageType::Signal)
            .sender(FLATPAK_DESTINATION)
            .and_then(|b| b.interface(MONITOR_INTERFACE))
            .map(|b| b.build())?;
        let messages = MessageIterator::for_match_rule(rule, &connection, Some(SIGNAL_QUEUE_CAPACITY))?;

        let proxy = Proxy::new(&connection, FLATPAK_DESTINATION, FLATPAK_PATH, FLATPAK_INTERFACE)?;
        let mut options: HashMap<&str, Value> = HashMap::new();
        let token = format!("nucleus_flatpak_{}_{}", std::process::id(), NEXT_TOKEN.fetch_add(1, Ordering::Relaxed));
        options.insert("handle_token", Value::from(token));
        let path: OwnedObjectPath = proxy.call("CreateUpdateMonitor", &options)?;

        let closing = Arc::new(AtomicBool::new(false));
        let reader_path = path.clone();
        let reader_closing = closing.clone();
        thread::Builder::new()
            .name("nucleus-flatpak-monitor".into())
            .spawn(move || read_signals(messages, reader_path, reader_closing, events))
            .map_err(|e| PortalError { name: ERROR_BUS.to_string(), message: e.to_string() })?;
        Ok(Monitor { connection, path, closing })
    }

    /// Starts installing the update. Returns once the portal accepted the request: the outcome
    /// arrives as `Progress` signals.
    pub fn update(&self, parent_window: &str) -> Result<(), PortalError> {
        let proxy = Proxy::new(&self.connection, FLATPAK_DESTINATION, self.path.as_str(), MONITOR_INTERFACE)?;
        let options: HashMap<&str, Value> = HashMap::new();
        proxy.call::<_, _, ()>("Update", &(parent_window, options))?;
        Ok(())
    }

    /// Closes the monitor and its connection. Never joins the reader: a Kotlin callback running
    /// on it may be the one closing.
    pub fn close(self) {
        self.closing.store(true, Ordering::SeqCst);
        if let Ok(proxy) = Proxy::new(&self.connection, FLATPAK_DESTINATION, self.path.as_str(), MONITOR_INTERFACE) {
            let _ = proxy.call::<_, _, ()>("Close", &());
        }
        let _ = self.connection.close();
    }
}

fn read_signals(messages: MessageIterator, path: OwnedObjectPath, closing: Arc<AtomicBool>, events: impl Events) {
    for message in messages {
        let Ok(message) = message else { break };
        let header = message.header();
        if header.path().map(|p| p.as_str()) != Some(path.as_str()) {
            continue;
        }
        let member = header.member().map(|m| m.to_string()).unwrap_or_default();
        let Ok((info,)) = message.body().deserialize::<(HashMap<String, OwnedValue>,)>() else {
            continue;
        };
        match member.as_str() {
            "UpdateAvailable" => events.update_available(
                &string(&info, "running-commit"),
                &string(&info, "local-commit"),
                &string(&info, "remote-commit"),
            ),
            "Progress" => events.progress(Progress {
                n_ops: uint(&info, "n_ops"),
                op: uint(&info, "op"),
                progress: uint(&info, "progress"),
                status: uint(&info, "status"),
                error: optional_string(&info, "error"),
                error_message: optional_string(&info, "error_message"),
            }),
            _ => {}
        }
    }
    if !closing.load(Ordering::SeqCst) {
        events.closed("the portal connection closed");
    }
}

fn optional_string(info: &HashMap<String, OwnedValue>, key: &str) -> Option<String> {
    info.get(key).and_then(|v| v.downcast_ref::<&str>().ok().map(str::to_string))
}

fn string(info: &HashMap<String, OwnedValue>, key: &str) -> String {
    optional_string(info, key).unwrap_or_default()
}

fn uint(info: &HashMap<String, OwnedValue>, key: &str) -> u32 {
    info.get(key).and_then(|v| v.downcast_ref::<u32>().ok()).unwrap_or(0)
}

/// Runs `argv` in a new sandbox of this app on its newest installed commit. Returns the pid.
///
/// The new process gets this one's stdin / stdout / stderr, `/dev/null` for any that is closed.
/// They must be passed even when nobody reads them: the portal numbers the fd carrying the
/// environment after the highest fd it was given, so without any it becomes fd 0, which
/// `flatpak run --env-fd` rejects ("Cannot use reserved file descriptor 0, 1 or 2") and nothing
/// starts.
pub fn spawn_latest(cwd: &str, argv: &[String]) -> Result<u32, PortalError> {
    let connection = Connection::session()?;
    let proxy = Proxy::new(&connection, FLATPAK_DESTINATION, FLATPAK_PATH, FLATPAK_INTERFACE)?;
    let null = File::options().read(true).write(true).open("/dev/null");
    let mut fds: HashMap<u32, Fd> = HashMap::new();
    for target in 0..=2u32 {
        let fd = if Path::new(&format!("/proc/self/fd/{target}")).exists() {
            // SAFETY: fd 0, 1 or 2 is open (checked above) and stays open for this call.
            Some(unsafe { BorrowedFd::borrow_raw(target as i32) })
        } else {
            null.as_ref().ok().map(|file| file.as_fd())
        };
        if let Some(fd) = fd {
            fds.insert(target, Fd::from(fd));
        }
    }
    let envs: HashMap<&str, &str> = HashMap::new();
    let options: HashMap<&str, Value> = HashMap::new();
    let argv: Vec<Vec<u8>> = argv.iter().map(|arg| bytestring(arg)).collect();
    let pid: u32 = proxy.call(
        "Spawn",
        &(bytestring(cwd), argv, fds, envs, SPAWN_FLAGS_LATEST_VERSION, options),
    )?;
    Ok(pid)
}

/// `ay` as the portal reads it (`g_variant_get_bytestring`): without the trailing NUL it would
/// see an empty string.
fn bytestring(value: &str) -> Vec<u8> {
    let mut bytes = value.as_bytes().to_vec();
    bytes.push(0);
    bytes
}

/// Asks the desktop to open `uri` (the OpenURI portal). Returns once the request is accepted.
pub fn open_uri(parent_window: &str, uri: &str) -> Result<(), PortalError> {
    let connection = Connection::session()?;
    let proxy = Proxy::new(&connection, DESKTOP_DESTINATION, DESKTOP_PATH, OPEN_URI_INTERFACE)?;
    let options: HashMap<&str, Value> = HashMap::new();
    let _: OwnedObjectPath = proxy.call("OpenURI", &(parent_window, uri, options))?;
    Ok(())
}

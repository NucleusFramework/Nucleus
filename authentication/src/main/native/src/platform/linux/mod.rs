//! polkit: `CheckAuthorization` on an action the app installed, with user interaction
//! allowed, so the desktop's polkit agent shows its password dialog. The dialog text
//! comes from the action's `.policy` file, never from the caller.

mod dbus;

use std::collections::BTreeMap;
use std::sync::Mutex;

use self::dbus::{CallError, Connection, Sender};
use crate::{Biometry, Done, Error, Failure, Policy, Prompt, Result};

/// A check still running, kept so [cancel] can reach it.
#[derive(Default)]
struct Pending {
    canceled: bool,
    sender: Option<Sender>,
}

static PENDING: Mutex<BTreeMap<i64, Pending>> = Mutex::new(BTreeMap::new());

pub(crate) fn availability(policy: Policy) -> (Biometry, Result<()>) {
    if policy == Policy::Biometrics {
        return (Biometry::None, Err(biometrics_only()));
    }
    let result = Connection::system()
        .map_err(|error| Failure::new(Error::Unavailable, format!("system bus: {error}")))
        .and_then(|mut connection| connection.ping_polkit().map_err(|error| failure(error, false)));
    (Biometry::None, result)
}

pub(crate) fn authenticate(id: i64, policy: Policy, prompt: Prompt, _parent: i64, done: Done) -> Result<()> {
    if policy == Policy::Biometrics {
        return Err(biometrics_only());
    }
    let action_id = prompt
        .linux_action_id
        .filter(|action| !action.is_empty())
        .ok_or_else(|| Failure::new(Error::InvalidPrompt, "Linux needs the polkit action id to check"))?;
    if let Ok(mut pending) = PENDING.lock() {
        pending.insert(id, Pending::default());
    }
    // D-Bus round trips and the agent's dialog block: never on the caller's thread.
    let spawned = std::thread::Builder::new()
        .name("nucleus-authentication-polkit".into())
        .spawn(move || {
            let result = check(id, &action_id);
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
        return Err(Failure::new(Error::Platform, "cannot start the polkit thread"));
    }
    Ok(())
}

pub(crate) fn cancel(id: i64) {
    let sender = PENDING.lock().ok().and_then(|mut pending| {
        pending.get_mut(&id).and_then(|pending| {
            pending.canceled = true;
            pending.sender.clone()
        })
    });
    // Without a sender the check has not been sent yet: `check` sees the flag instead.
    if let Some(sender) = sender {
        let _ = sender.cancel(&cancellation_id(id));
    }
}

fn check(id: i64, action_id: &str) -> Result<()> {
    let mut connection =
        Connection::system().map_err(|error| Failure::new(Error::Unavailable, format!("system bus: {error}")))?;
    let canceled = PENDING
        .lock()
        .ok()
        .and_then(|mut pending| {
            pending.get_mut(&id).map(|pending| {
                pending.sender = Some(connection.sender());
                pending.canceled
            })
        })
        .unwrap_or(true);
    if canceled {
        return Err(Error::AppCanceled.into());
    }

    let result = connection
        .check_authorization(action_id, &cancellation_id(id))
        .map_err(|error| failure(error, true))?;
    if result.is_authorized {
        Ok(())
    } else if result.dismissed {
        Err(Error::UserCanceled.into())
    } else if result.is_challenge {
        // polkit wanted to ask but nobody answered: no agent is running.
        Err(Failure::new(Error::Unavailable, "no polkit authentication agent"))
    } else {
        // The action is denied to this subject outright.
        Err(Failure::new(Error::Failed, format!("{action_id} is not authorized")))
    }
}

/// Unique per process and request, as polkit requires of a cancellation id.
fn cancellation_id(id: i64) -> String {
    format!("nucleus-authentication-{}-{id}", std::process::id())
}

fn biometrics_only() -> Failure {
    Failure::new(
        Error::UnsupportedPolicy,
        "polkit cannot require biometrics: PAM decides how the user authenticates",
    )
}

fn failure(error: CallError, checking: bool) -> Failure {
    match error {
        CallError::Io(error) => Failure::new(Error::Unavailable, format!("polkit: {error}")),
        CallError::Reply(reply) => {
            let kind = match reply.name.as_str() {
                "org.freedesktop.PolicyKit1.Error.Cancelled" => Error::UserCanceled,
                "org.freedesktop.PolicyKit1.Error.NotAuthorized" => Error::Failed,
                // Raised for an action no .policy file declares, before any prompt.
                "org.freedesktop.PolicyKit1.Error.Failed" if checking && reply.message.contains("not registered") => {
                    Error::InvalidPrompt
                }
                "org.freedesktop.PolicyKit1.Error.Failed" => Error::Platform,
                "org.freedesktop.PolicyKit1.Error.NotSupported"
                | "org.freedesktop.DBus.Error.ServiceUnknown"
                | "org.freedesktop.DBus.Error.NameHasNoOwner"
                | "org.freedesktop.DBus.Error.NoServer"
                | "org.freedesktop.DBus.Error.NoReply"
                | "org.freedesktop.DBus.Error.Timeout"
                | "org.freedesktop.DBus.Error.TimedOut"
                | "org.freedesktop.DBus.Error.Disconnected" => Error::Unavailable,
                _ => Error::Platform,
            };
            Failure::new(kind, format!("{}: {}", reply.name, reply.message))
        }
    }
}

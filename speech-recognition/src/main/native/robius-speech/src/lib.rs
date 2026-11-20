//! Native streaming speech-to-text input and microphone capture for Rust apps.
//!
//! Recognition keeps going across as many utterances as the user speaks, until you
//! stop it. An "utterance" is just a chunk of words spoken by the user: partial
//! transcripts replace the current one, whereas a final transcript commits it.
//!
//! Note that speech always stays as plain text here. This crate never interprets
//! spoken works as commands or synthesizes key events from them,
//! so saying something like "enter" will just type the word "enter".
//!
//! [`Dictation`] turns those transcripts into edits of a text field, so an app
//! only has to apply the replacements it hands back.
//!
//! Apple builds need the Xcode command line tools (`xcode-select --install`), and
//! support macOS 11+ and iOS 13+. For macOS builds, set the `MACOSX_DEPLOYMENT_TARGET`
//! env var to 11.0 or newer for the whole Cargo invocation, including the final app.
//! This mostly just matters on Intel x86 macs, but it doesn't hurt to always set it.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicU64, Ordering};

pub mod dictation;
pub use dictation::{Dictation, Replacement};

#[cfg(any(target_os = "macos", target_os = "ios"))]
mod apple;
#[cfg(all(target_os = "android", native_speech_android))]
mod android;
#[cfg(target_os = "windows")]
mod windows;

#[cfg(any(target_os = "macos", target_os = "ios"))]
use apple as backend;
#[cfg(all(target_os = "android", native_speech_android))]
use android as backend;
#[cfg(target_os = "windows")]
use windows as backend;

#[derive(Clone, Debug)]
pub struct NativeSpeechOptions {
    /// A BCP-47 locale (e.g., `"en-US"`), or the OS's default speech language.
    pub locale: Option<String>,
    /// Prefer on-device recognition when the native service supports it.
    /// Otherwise the system service may need a network connection.
    pub prefer_on_device: bool,
}

impl Default for NativeSpeechOptions {
    fn default() -> Self {
        Self { locale: None, prefer_on_device: true }
    }
}

/// The cause of a speech failure, so you can handle each one properly
/// instead of matching on message text.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
#[non_exhaustive]
pub enum SpeechErrorKind {
    /// The user denied permission, or your app is missing the privacy
    /// declaration it needs in order to even ask.
    /// Usually you'll want to tell the user to enable audio and/or speech
    /// permissions in their system settings.
    PermissionDenied,
    /// The audio input or speech recognition service just doesn't exist here,
    /// so retrying will never work.
    Unavailable,
    /// The recognizer doesn't support the language you asked for.
    Language,
    /// The microphone is missing, busy, or changed mid-session.
    /// Starting things again will likely work.
    Audio,
    /// Another recording is already active; only one session can be active at a time.
    Busy,
    /// Anything else that the OS reported.
    Other,
}

/// A [`SpeechErrorKind`] that you can match on, plus the message the OS gave us.
/// `Display` writes just that message, which is already worded for end users.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SpeechError {
    kind: SpeechErrorKind,
    message: String,
}

impl SpeechError {
    pub fn new(kind: SpeechErrorKind, message: impl Into<String>) -> Self {
        Self { kind, message: message.into() }
    }

    pub fn kind(&self) -> SpeechErrorKind {
        self.kind
    }

    pub fn message(&self) -> &str {
        &self.message
    }
}

impl std::fmt::Display for SpeechError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for SpeechError {}

#[derive(Clone, Debug, PartialEq)]
pub enum NativeSpeechEvent {
    /// Permissions were granted and the microphone is actually recording.
    Started,
    /// Recognized text is available.
    ///
    /// If `is_final` is `false`, the text is a partial transcript that may be updated/changed later.
    /// If `is_final` is `true`, the text is a final transcript that is fully "committed".
    Transcript {
        text: String,
        is_final: bool,
    },
    /// Microphone amplitude, normalized between 0 and 1, which you can use to
    /// display a level meter or similar sound wave animation.
    AudioLevel(f32),
    /// Capture and final recognition are done. This is a final event.
    Stopped,
    /// A final error, e.g., denied permissions or unavailable hardware.
    Error(SpeechError),
}

/// Event codes shared with the Swift and Java bridges. The error kinds are just
/// extra values of the same `kind` argument, so classifying them needs no ABI change.
#[cfg(any(target_os = "macos", target_os = "ios", all(target_os = "android", native_speech_android)))]
#[allow(dead_code)] // Not every backend uses every code.
pub(crate) mod codes {
    pub(crate) const STARTED: i32 = 0;
    pub(crate) const PARTIAL: i32 = 1;
    pub(crate) const FINAL: i32 = 2;
    pub(crate) const LEVEL: i32 = 3;
    pub(crate) const STOPPED: i32 = 4;
    pub(crate) const ERROR: i32 = 5;
    pub(crate) const ERROR_PERMISSION: i32 = 6;
    pub(crate) const ERROR_UNAVAILABLE: i32 = 7;
    pub(crate) const ERROR_LANGUAGE: i32 = 8;
    pub(crate) const ERROR_AUDIO: i32 = 9;
}

/// Unknown codes fall back to `Other`, so a newer bridge still reports its message.
#[cfg(any(target_os = "macos", target_os = "ios", all(target_os = "android", native_speech_android)))]
pub(crate) fn error_kind_from_code(code: i32) -> SpeechErrorKind {
    match code {
        codes::ERROR_PERMISSION => SpeechErrorKind::PermissionDenied,
        codes::ERROR_UNAVAILABLE => SpeechErrorKind::Unavailable,
        codes::ERROR_LANGUAGE => SpeechErrorKind::Language,
        codes::ERROR_AUDIO => SpeechErrorKind::Audio,
        _ => SpeechErrorKind::Other,
    }
}

type Callback = Arc<dyn Fn(NativeSpeechEvent) + Send + Sync>;
static SESSIONS: OnceLock<Mutex<HashMap<u64, Callback>>> = OnceLock::new();
static NEXT_SESSION: AtomicU64 = AtomicU64::new(1);

fn sessions() -> &'static Mutex<HashMap<u64, Callback>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Owns the system microphone and recognition task; dropping it cancels both.
///
/// Importantly, only one session can be active at a time.
///
/// Callbacks arrive on platform-dependent native OS threads, so you'll want to
/// forward them to your main UI thread or event loop. Note that it's possible for
/// an in-progress callback to continue executing after you cancel the session.
pub struct NativeSpeechSession {
    id: u64,
}

/// The short, stable name of the recognizer this build uses, e.g., `"apple-sfspeech"`.
/// Handy for logs and bug reports.
pub fn engine_name() -> &'static str {
    backend::ENGINE
}

impl NativeSpeechSession {
    /// Whether this platform offers a native speech recognizer at all.
    ///
    /// This is false on Linux and the web, so you'll usually want to hide any
    /// dictation button entirely rather than let it fail when pressed.
    pub fn is_supported() -> bool {
        backend::is_supported()
    }

    /// Starts dictating, requesting microphone (and on Apple, speech recognition)
    /// permission if needed, so you don't need any platform-specific permission code.
    ///
    /// This returns immediately rather than blocking on the permission prompt.
    /// The `Started` event tells you when the microphone is actually recording,
    /// and a refusal arrives as [`SpeechErrorKind::PermissionDenied`].
    pub fn start(
        options: NativeSpeechOptions,
        callback: impl Fn(NativeSpeechEvent) + Send + Sync + 'static,
    ) -> Result<Self, SpeechError> {
        if !Self::is_supported() {
            return Err(SpeechError::new(
                SpeechErrorKind::Unavailable,
                "Native speech recognition is not available on this platform.",
            ));
        }
        if options.locale.as_ref().is_some_and(|locale| locale.contains('\0')) {
            return Err(SpeechError::new(
                SpeechErrorKind::Language,
                "The speech recognition language is invalid.",
            ));
        }
        let id = NEXT_SESSION.fetch_add(1, Ordering::Relaxed);
        {
            let mut sessions = sessions().lock().unwrap();
            if !sessions.is_empty() {
                return Err(SpeechError::new(
                    SpeechErrorKind::Busy,
                    "Another speech recording is already active.",
                ));
            }
            sessions.insert(id, Arc::new(callback));
        }
        if let Err(error) = backend::start(id, &options) {
            sessions().lock().unwrap().remove(&id);
            return Err(error);
        }
        Ok(Self { id })
    }

    /// Closes the microphone, but still lets the final recognition result arrive.
    pub fn stop(&self) {
        backend::stop(self.id, false);
    }

    /// Stops immediately and discards anything that's still pending.
    pub fn cancel(&self) {
        sessions().lock().unwrap().remove(&self.id);
        backend::stop(self.id, true);
    }
}

impl Drop for NativeSpeechSession {
    fn drop(&mut self) {
        self.cancel();
    }
}

/// Cancels every active session, for things like suspending or quitting the app.
pub fn cancel_all() {
    let ids: Vec<_> = sessions().lock().unwrap().drain().map(|(id, _)| id).collect();
    for id in ids {
        backend::stop(id, true);
    }
}

#[cfg(any(test, target_os = "macos", target_os = "ios", all(target_os = "android", native_speech_android), target_os = "windows"))]
pub(crate) fn emit(id: u64, event: NativeSpeechEvent) {
    let callback = {
        let mut sessions = sessions().lock().unwrap();
        if matches!(event, NativeSpeechEvent::Stopped | NativeSpeechEvent::Error(_)) {
            sessions.remove(&id)
        } else {
            sessions.get(&id).cloned()
        }
    };
    if let Some(callback) = callback {
        // Never unwind through a native callback boundary.
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| callback(event)));
    }
}

#[cfg(not(any(target_os = "macos", target_os = "ios", all(target_os = "android", native_speech_android), target_os = "windows")))]
mod backend {
    use super::{NativeSpeechOptions, SpeechError, SpeechErrorKind};
    pub(super) const ENGINE: &str = "none";
    pub(super) fn is_supported() -> bool { false }
    pub(super) fn start(_: u64, _: &NativeSpeechOptions) -> Result<(), SpeechError> {
        Err(SpeechError::new(
            SpeechErrorKind::Unavailable,
            "Native speech recognition is not available on this platform.",
        ))
    }
    pub(super) fn stop(_: u64, _: bool) {}
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terminal_and_cancelled_sessions_discard_late_native_results() {
        let received = Arc::new(Mutex::new(Vec::new()));
        let output = received.clone();
        sessions().lock().unwrap().insert(1001, Arc::new(move |event| {
            output.lock().unwrap().push(event);
        }));
        emit(1001, NativeSpeechEvent::Transcript { text: "draft".into(), is_final: false });
        emit(1001, NativeSpeechEvent::Stopped);
        emit(1001, NativeSpeechEvent::Transcript { text: "stale".into(), is_final: true });
        assert_eq!(*received.lock().unwrap(), vec![
            NativeSpeechEvent::Transcript { text: "draft".into(), is_final: false },
            NativeSpeechEvent::Stopped,
        ]);

        let output = received.clone();
        sessions().lock().unwrap().insert(1002, Arc::new(move |event| {
            output.lock().unwrap().push(event);
        }));
        // A previous session's delayed callback must not be delivered to its
        // successor, even when recognition is restarted immediately.
        emit(1001, NativeSpeechEvent::Error(SpeechError::new(SpeechErrorKind::Other, "late failure")));
        NativeSpeechSession { id: 1002 }.cancel();
        emit(1002, NativeSpeechEvent::Started);
        assert_eq!(received.lock().unwrap().len(), 2);
        let sessions = sessions().lock().unwrap();
        assert!(!sessions.contains_key(&1001));
        assert!(!sessions.contains_key(&1002));
    }
}

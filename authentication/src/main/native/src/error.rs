use std::fmt;

pub(crate) type Result<T> = std::result::Result<T, Failure>;

/// Failure categories reported back to Kotlin. The numeric codes are the
/// `AuthenticationError` ordinals plus one (`0` is success, see
/// `NativeAuthenticationBridge.kt`) — keep both sides in sync, only ever append.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Error {
    UserCanceled = 1,
    AppCanceled,
    SystemCanceled,
    Failed,
    Lockout,
    NotEnrolled,
    NoCredential,
    Unavailable,
    DisabledByPolicy,
    Busy,
    NotInteractive,
    UnsupportedPolicy,
    InvalidPrompt,
    Unsupported,
    Platform,
}

impl Error {
    pub(crate) fn code(self) -> i32 {
        self as i32
    }

    fn describe(self) -> &'static str {
        match self {
            Error::UserCanceled => "the user cancelled authentication",
            Error::AppCanceled => "the app cancelled authentication",
            Error::SystemCanceled => "the system cancelled authentication",
            Error::Failed => "the user failed to provide valid credentials",
            Error::Lockout => "too many failed attempts",
            Error::NotEnrolled => "no biometric identity is enrolled",
            Error::NoCredential => "no device credential is set up",
            Error::Unavailable => "authentication is unavailable",
            Error::DisabledByPolicy => "authentication is disabled by policy",
            Error::Busy => "the authentication device is busy",
            Error::NotInteractive => "the authentication UI cannot be shown",
            Error::UnsupportedPolicy => "this policy cannot be enforced on this platform",
            Error::InvalidPrompt => "invalid prompt",
            Error::Unsupported => "authentication is unsupported on this platform",
            Error::Platform => "platform error",
        }
    }
}

/// An [Error] plus the platform's own words, when it gave any.
#[derive(Clone, Debug)]
pub(crate) struct Failure {
    pub(crate) kind: Error,
    pub(crate) message: Option<String>,
}

impl Failure {
    pub(crate) fn new(kind: Error, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: Some(message.into()),
        }
    }
}

impl From<Error> for Failure {
    fn from(kind: Error) -> Self {
        Self { kind, message: None }
    }
}

#[cfg(target_os = "windows")]
impl From<windows::core::Error> for Failure {
    fn from(value: windows::core::Error) -> Self {
        Failure::new(Error::Platform, format!("Windows API error: {value}"))
    }
}

impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.message {
            Some(message) => write!(f, "{}: {message}", self.kind.describe()),
            None => f.write_str(self.kind.describe()),
        }
    }
}

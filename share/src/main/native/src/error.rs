use std::fmt;

pub(crate) type Result<T> = std::result::Result<T, Error>;

/// Failures reported back to Kotlin. The numeric codes are the `ShareError` ordinals
/// plus one (`0` is success, see `NativeShareBridge.kt`) — keep both sides in sync.
#[derive(Debug)]
pub(crate) enum Error {
    Empty,
    InvalidItem,
    NoHandler,
    AlreadyOpen,
    UnsupportedItem,
    NoWindow,
    Unsupported,
    Io(std::io::Error),
    Platform(String),
}

impl Error {
    pub(crate) fn code(&self) -> i32 {
        match self {
            Error::Empty => 1,
            Error::InvalidItem => 2,
            Error::NoHandler => 3,
            Error::AlreadyOpen => 4,
            Error::UnsupportedItem => 5,
            Error::NoWindow => 6,
            Error::Unsupported => 7,
            Error::Io(_) => 8,
            Error::Platform(_) => 9,
        }
    }
}

impl From<std::io::Error> for Error {
    fn from(value: std::io::Error) -> Self {
        Error::Io(value)
    }
}

#[cfg(target_os = "windows")]
impl From<windows::core::Error> for Error {
    fn from(value: windows::core::Error) -> Self {
        Error::Platform(format!("Windows API error: {value}"))
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Empty => f.write_str("nothing to share"),
            Error::InvalidItem => f.write_str("invalid share item"),
            Error::NoHandler => f.write_str("no app is available to receive this share"),
            Error::AlreadyOpen => f.write_str("another share sheet is already open"),
            Error::UnsupportedItem => f.write_str("this share item is unsupported on the current platform"),
            Error::NoWindow => f.write_str("no window to attach the share sheet to"),
            Error::Unsupported => f.write_str("sharing is unsupported on this platform"),
            Error::Io(err) => write!(f, "I/O error: {err}"),
            Error::Platform(message) => f.write_str(message),
        }
    }
}

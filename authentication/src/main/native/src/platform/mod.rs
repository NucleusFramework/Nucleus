#[cfg(target_os = "windows")]
mod windows;
#[cfg(target_os = "windows")]
pub(crate) use self::windows::{authenticate, availability, cancel};

#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "macos")]
pub(crate) use self::macos::{authenticate, availability, cancel};

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "linux")]
pub(crate) use self::linux::{authenticate, availability, cancel};

#[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
mod unsupported {
    use crate::{Biometry, Done, Error, Policy, Prompt, Result};

    pub(crate) fn availability(_policy: Policy) -> (Biometry, Result<()>) {
        (Biometry::None, Err(Error::Unsupported.into()))
    }

    pub(crate) fn authenticate(_id: i64, _policy: Policy, _prompt: Prompt, _parent: i64, _done: Done) -> Result<()> {
        Err(Error::Unsupported.into())
    }

    pub(crate) fn cancel(_id: i64) {}
}
#[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
pub(crate) use self::unsupported::{authenticate, availability, cancel};

//! The CredUI prompt for the current account's password, checked with `LogonUserW`:
//! what robius-authentication falls back to when Windows Hello is not set up.

use std::ffi::c_void;

use windows::core::{PCWSTR, PSTR, PWSTR};
use windows::Win32::Foundation::{CloseHandle, ERROR_CANCELLED, HANDLE, HWND, NO_ERROR, WIN32_ERROR};
use windows::Win32::Graphics::Gdi::HBITMAP;
use windows::Win32::Security::Authentication::Identity::{
    GetUserNameExW, LsaConnectUntrusted, LsaDeregisterLogonProcess, LsaLookupAuthenticationPackage, NameSamCompatible,
    LSA_STRING, MSV1_0_PACKAGE_NAME,
};
use windows::Win32::Security::Credentials::{
    CredUIParseUserNameW, CredUIPromptForWindowsCredentialsW, CredUnPackAuthenticationBufferW,
    CREDUIWIN_ENUMERATE_CURRENT_USER, CREDUI_INFOW, CREDUI_MAX_DOMAIN_TARGET_LENGTH, CREDUI_MAX_USERNAME_LENGTH,
    CRED_PACK_PROTECTED_CREDENTIALS,
};
use windows::Win32::Security::{LogonUserW, LOGON32_LOGON_INTERACTIVE, LOGON32_PROVIDER_DEFAULT};
use windows::Win32::System::Com::CoTaskMemFree;

use crate::{Error, Failure, Prompt, Result};

// One more for the terminating nul. The unpacked name is DOMAIN\user, so it is sized
// for the whole thing (CREDUI_MAX_USERNAME_LENGTH), not just the account name.
const MAX_USERNAME_LENGTH: usize = CREDUI_MAX_USERNAME_LENGTH as usize + 1;
const MAX_DOMAIN_LENGTH: usize = CREDUI_MAX_DOMAIN_TARGET_LENGTH as usize + 1;
const MAX_PASSWORD_LENGTH: usize = 256 + 1;
// CREDUI_MAX_CAPTION_LENGTH / CREDUI_MAX_MESSAGE_LENGTH, in UTF-16 units.
const MAX_CAPTION_LENGTH: usize = 128;
const MAX_MESSAGE_LENGTH: usize = 1024;

type Username = [u16; MAX_USERNAME_LENGTH];
type Domain = [u16; MAX_DOMAIN_LENGTH];
type Password = [u16; MAX_PASSWORD_LENGTH];

pub(super) fn authenticate(parent: HWND, prompt: &Prompt) -> Result<()> {
    let (auth_buf, auth_buf_size) = ui_prompt(parent, prompt)?;
    let unpacked = unpack_authentication_buffer(auth_buf, auth_buf_size);
    // CredUI wants the credential blob zeroed and freed with CoTaskMemFree.
    unsafe {
        std::ptr::write_bytes(auth_buf as *mut u8, 0, auth_buf_size as usize);
        CoTaskMemFree(Some(auth_buf));
    }
    let ((username, username_size), mut password) = unpacked?;

    let result = check_and_logon(username, username_size, &mut password);
    for c in password.iter_mut() {
        unsafe { std::ptr::write_volatile(c, 0) };
    }
    result
}

fn check_and_logon(username: Username, username_size: usize, password: &mut Password) -> Result<()> {
    let (current_user, current_user_size) = current_user()?;
    // Someone else's valid credentials do not authenticate this user.
    if !eq_ignore_case(&username[..username_size], &current_user[..current_user_size]) {
        return Err(Failure::new(Error::Failed, "the credentials belong to another account"));
    }
    let (account_name, domain) = parse_username(username)?;
    logon_user(account_name, domain, password)
}

/// Compares two UTF-16 strings case-insensitively (Windows account names are), ignoring trailing nuls.
fn eq_ignore_case(a: &[u16], b: &[u16]) -> bool {
    let a = String::from_utf16_lossy(a);
    let b = String::from_utf16_lossy(b);
    a.trim_end_matches('\0').to_lowercase() == b.trim_end_matches('\0').to_lowercase()
}

fn ui_prompt(parent: HWND, prompt: &Prompt) -> Result<(*mut c_void, u32)> {
    let message = wide(&prompt.reason, MAX_MESSAGE_LENGTH);
    let caption = wide(prompt.title.as_deref().unwrap_or(""), MAX_CAPTION_LENGTH);
    let ui = CREDUI_INFOW {
        cbSize: size_of::<CREDUI_INFOW>() as u32,
        hwndParent: parent,
        pszMessageText: PCWSTR(message.as_ptr()),
        pszCaptionText: if prompt.title.is_some() {
            PCWSTR(caption.as_ptr())
        } else {
            PCWSTR::null()
        },
        hbmBanner: HBITMAP::default(),
    };

    let handle = lsa_handle()?;
    let auth_package = auth_package(handle);
    // The LSA handle only serves the package lookup: close it either way.
    unsafe {
        let _ = LsaDeregisterLogonProcess(handle);
    }
    let mut auth_package = auth_package?;

    let mut auth_buf = std::ptr::null_mut();
    let mut auth_buf_size = 0u32;
    let err = unsafe {
        CredUIPromptForWindowsCredentialsW(
            Some(&ui),
            0,
            &mut auth_package,
            None,
            0,
            &mut auth_buf,
            &mut auth_buf_size,
            None,
            CREDUIWIN_ENUMERATE_CURRENT_USER,
        )
    };
    match WIN32_ERROR(err) {
        NO_ERROR => Ok((auth_buf, auth_buf_size)),
        ERROR_CANCELLED => Err(Error::UserCanceled.into()),
        other => Err(Failure::new(
            Error::Platform,
            format!("CredUIPromptForWindowsCredentialsW: {}", other.0),
        )),
    }
}

/// `text` as a nul-terminated UTF-16 string of at most `max` units, cut on a char boundary.
fn wide(text: &str, max: usize) -> Vec<u16> {
    let mut out = Vec::with_capacity(text.len().min(max) + 1);
    for c in text.chars() {
        let mut buf = [0u16; 2];
        let units = c.encode_utf16(&mut buf);
        if out.len() + units.len() >= max {
            break;
        }
        out.extend_from_slice(units);
    }
    out.push(0);
    out
}

fn lsa_handle() -> Result<HANDLE> {
    let mut handle = HANDLE::default();
    if unsafe { LsaConnectUntrusted(&mut handle) }.is_ok() {
        Ok(handle)
    } else {
        Err(Failure::new(Error::Platform, "LsaConnectUntrusted failed"))
    }
}

fn auth_package(handle: HANDLE) -> Result<u32> {
    let name = unsafe { MSV1_0_PACKAGE_NAME.as_bytes() };
    let mut buffer = name.to_vec();
    buffer.push(0);
    let lsa_name = LSA_STRING {
        Length: name.len() as u16,
        MaximumLength: buffer.len() as u16,
        Buffer: PSTR(buffer.as_mut_ptr()),
    };
    let mut auth_package = 0u32;
    if unsafe { LsaLookupAuthenticationPackage(handle, &lsa_name, &mut auth_package) }.is_ok() {
        Ok(auth_package)
    } else {
        Err(Failure::new(Error::Platform, "LsaLookupAuthenticationPackage failed"))
    }
}

fn unpack_authentication_buffer(buffer: *mut c_void, size: u32) -> Result<((Username, usize), Password)> {
    let mut username = [0u16; MAX_USERNAME_LENGTH];
    let mut username_size = username.len() as u32;
    let mut password = [0u16; MAX_PASSWORD_LENGTH];
    let mut password_size = password.len() as u32;
    unsafe {
        CredUnPackAuthenticationBufferW(
            CRED_PACK_PROTECTED_CREDENTIALS,
            buffer,
            size,
            Some(PWSTR(username.as_mut_ptr())),
            &mut username_size,
            None,
            None,
            Some(PWSTR(password.as_mut_ptr())),
            &mut password_size,
        )
    }
    .map_err(|error| Failure::new(Error::Platform, format!("CredUnPackAuthenticationBufferW: {error}")))?;
    Ok(((username, username_size as usize), password))
}

fn current_user() -> Result<(Username, usize)> {
    let mut username = [0u16; MAX_USERNAME_LENGTH];
    let mut username_size = username.len() as u32;
    if unsafe {
        GetUserNameExW(
            NameSamCompatible,
            Some(PWSTR(username.as_mut_ptr())),
            &mut username_size,
        )
    } {
        // The reported length excludes the nul.
        Ok((username, username_size as usize))
    } else {
        Err(Failure::new(Error::Platform, "GetUserNameExW failed"))
    }
}

fn parse_username(mut username: Username) -> Result<(Username, Domain)> {
    let mut account_name = [0u16; MAX_USERNAME_LENGTH];
    let mut domain = [0u16; MAX_DOMAIN_LENGTH];
    let err = unsafe { CredUIParseUserNameW(PCWSTR(username.as_mut_ptr()), &mut account_name, &mut domain) };
    if err == NO_ERROR {
        Ok((account_name, domain))
    } else {
        Err(Failure::new(
            Error::Platform,
            format!("CredUIParseUserNameW: {}", err.0),
        ))
    }
}

fn logon_user(mut account_name: Username, mut domain: Domain, password: &mut Password) -> Result<()> {
    let mut token = HANDLE::default();
    let result = unsafe {
        LogonUserW(
            PCWSTR(account_name.as_mut_ptr()),
            PCWSTR(domain.as_mut_ptr()),
            PCWSTR(password.as_mut_ptr()),
            LOGON32_LOGON_INTERACTIVE,
            LOGON32_PROVIDER_DEFAULT,
            // A null token pointer makes Windows succeed whatever the password.
            &mut token,
        )
    }
    .map_err(|_| Failure::from(Error::Failed));
    if !token.is_invalid() {
        unsafe {
            let _ = CloseHandle(token);
        }
    }
    result
}

//! WinRT Share UI through `DataTransferManager`'s desktop-window interop.

use std::collections::BTreeMap;
use std::ffi::OsString;
use std::os::windows::ffi::{OsStrExt, OsStringExt};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use windows::core::{factory, AgileReference, Interface, Ref, HSTRING};
use windows::ApplicationModel::DataTransfer::{
    DataPackageOperation, DataRequest, DataRequestedEventArgs, DataTransferManager,
};
use windows::Foundation::{TypedEventHandler, Uri};
use windows::Storage::{IStorageItem, StorageFile};
use windows::Win32::Foundation::{HWND, LPARAM, TRUE};
use windows::Win32::System::Threading::GetCurrentProcessId;
use windows::Win32::UI::Shell::IDataTransferManagerInterop;
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetAncestor, GetForegroundWindow, GetWindow, GetWindowThreadProcessId, IsWindow,
    IsWindowVisible, GA_ROOT, GW_OWNER,
};
use windows::core::BOOL;
use windows_collections::IIterable;

use crate::{Error, Parent, Result, ShareItem, ShareOptions};

/// The `DataRequested` registration left on each window, keyed by HWND, so a
/// share never answers with the payload of the previous one.
static ACTIVE_REGISTRATIONS: Mutex<BTreeMap<isize, i64>> = Mutex::new(BTreeMap::new());

pub(crate) fn share(options: ShareOptions, parent: Parent) -> Result<()> {
    if !DataTransferManager::IsSupported()? {
        return Err(Error::Unsupported);
    }

    // Resolved up front: inside the handler a failure could only be reported as a
    // generic "cannot share" in the system UI.
    let storage_items = resolve_storage_items(&options)?;
    let hwnd = resolve_window(parent.handle)?;
    let key = hwnd.0 as isize;

    remove_stale_registration(key);

    let interop: IDataTransferManagerInterop = factory::<DataTransferManager, IDataTransferManagerInterop>()?;
    let manager: DataTransferManager = unsafe { interop.GetForWindow(hwnd)? };
    let active_token = Arc::new(Mutex::new(None));
    let handler_token = active_token.clone();

    let handler = TypedEventHandler::new(
        move |_sender: Ref<DataTransferManager>, args: Ref<DataRequestedEventArgs>| {
            if let Some(args) = args.as_ref() {
                let request = args.Request()?;
                if fill_request(&options, &storage_items, &request).is_err() {
                    let _ = request.FailWithDisplayText(&HSTRING::from("Unable to prepare the share payload."));
                }
            }
            remove_current_registration(key, &handler_token);
            Ok(())
        },
    );

    let token = manager.DataRequested(&handler)?;
    if let Ok(mut active) = active_token.lock() {
        *active = Some(token);
    }
    if let Ok(mut registrations) = ACTIVE_REGISTRATIONS.lock() {
        registrations.insert(key, token);
    }

    if let Err(error) = unsafe { interop.ShowShareUIForWindow(hwnd) } {
        // The UI never appeared, so the handler will never run.
        remove_current_registration(key, &active_token);
        return Err(error.into());
    }
    Ok(())
}

/// The top-level window to attach to: the caller's, else the process's frontmost one.
fn resolve_window(handle: i64) -> Result<HWND> {
    if handle != 0 {
        let hwnd = HWND(handle as isize as *mut _);
        if !unsafe { IsWindow(Some(hwnd)) }.as_bool() {
            return Err(Error::NoWindow);
        }
        let root = unsafe { GetAncestor(hwnd, GA_ROOT) };
        return Ok(if root.is_invalid() { hwnd } else { root });
    }

    let foreground = unsafe { GetForegroundWindow() };
    if !foreground.is_invalid() && is_own_top_level(foreground) {
        return Ok(foreground);
    }
    // EnumWindows walks top-level windows in Z order: the first match is frontmost.
    let mut found = HWND::default();
    let _ = unsafe { EnumWindows(Some(find_own_window), LPARAM(&mut found as *mut HWND as isize)) };
    if found.is_invalid() {
        Err(Error::NoWindow)
    } else {
        Ok(found)
    }
}

unsafe extern "system" fn find_own_window(hwnd: HWND, lparam: LPARAM) -> BOOL {
    if is_own_top_level(hwnd) {
        *(lparam.0 as *mut HWND) = hwnd;
        return BOOL(0);
    }
    TRUE
}

fn is_own_top_level(hwnd: HWND) -> bool {
    let mut pid = 0u32;
    unsafe { GetWindowThreadProcessId(hwnd, Some(&mut pid)) };
    pid == unsafe { GetCurrentProcessId() }
        && unsafe { IsWindowVisible(hwnd) }.as_bool()
        && unsafe { GetWindow(hwnd, GW_OWNER) }.map_or(true, |owner| owner.is_invalid())
}

fn remove_stale_registration(key: isize) {
    let stale = ACTIVE_REGISTRATIONS
        .lock()
        .ok()
        .and_then(|mut registrations| registrations.remove(&key));
    if let Some(token) = stale {
        remove_handler(key, token);
    }
}

fn remove_current_registration(key: isize, active_token: &Mutex<Option<i64>>) {
    let Some(token) = active_token.lock().ok().and_then(|mut token| token.take()) else {
        return;
    };
    remove_handler(key, token);
    if let Ok(mut registrations) = ACTIVE_REGISTRATIONS.lock() {
        if registrations.get(&key) == Some(&token) {
            registrations.remove(&key);
        }
    }
}

fn remove_handler(key: isize, token: i64) {
    let _ = factory::<DataTransferManager, IDataTransferManagerInterop>().and_then(|interop| {
        let manager: DataTransferManager = unsafe { interop.GetForWindow(HWND(key as *mut _)) }?;
        manager.RemoveDataRequested(token)
    });
}

/// WinRT storage items for every file attachment. They travel as [AgileReference]s
/// because the handler may run on another apartment than the one that resolved them.
fn resolve_storage_items(options: &ShareOptions) -> Result<Vec<AgileReference<IStorageItem>>> {
    let mut storage_items = Vec::new();
    for item in &options.items {
        let path = match item {
            ShareItem::File { path, .. } => PathBuf::from(path),
            ShareItem::FileUri { uri, .. } => file_uri_to_path(uri).ok_or(Error::UnsupportedItem)?,
            _ => continue,
        };
        // `GetFileFromPathAsync` rejects the `\\?\` form `canonicalize` produces.
        let path = strip_verbatim_prefix(&std::fs::canonicalize(path)?);
        let file = StorageFile::GetFileFromPathAsync(&HSTRING::from(path.as_path()))?.join()?;
        let item: IStorageItem = file.cast()?;
        storage_items.push(AgileReference::new(&item)?);
    }
    Ok(storage_items)
}

fn fill_request(
    options: &ShareOptions,
    storage_items: &[AgileReference<IStorageItem>],
    request: &DataRequest,
) -> windows::core::Result<()> {
    let data = request.Data()?;
    let properties = data.Properties()?;

    let title = options.title.as_deref().or(options.subject.as_deref()).unwrap_or("Share");
    properties.SetTitle(&HSTRING::from(title))?;
    if let Some(subject) = &options.subject {
        properties.SetDescription(&HSTRING::from(subject.as_str()))?;
    }
    data.SetRequestedOperation(DataPackageOperation::Copy)?;

    if let Some(text) = options.shared_text() {
        data.SetText(&HSTRING::from(text.as_str()))?;
    }
    if let Some(url) = options.first_url() {
        if let Ok(uri) = Uri::CreateUri(&HSTRING::from(url)) {
            data.SetWebLink(&uri)?;
        }
    }
    if !storage_items.is_empty() {
        let items = storage_items
            .iter()
            .map(|item| item.resolve().map(Some))
            .collect::<windows::core::Result<Vec<Option<IStorageItem>>>>()?;
        let items: IIterable<IStorageItem> = items.into();
        data.SetStorageItemsReadOnly(&items)?;
    }
    Ok(())
}

/// `file:///C:/dir/a%20b.txt` → `C:\dir\a b.txt`; `None` for any other scheme.
fn file_uri_to_path(uri: &str) -> Option<PathBuf> {
    let uri = Uri::CreateUri(&HSTRING::from(uri)).ok()?;
    if uri.SchemeName().ok()?.to_string_lossy().to_ascii_lowercase() != "file" {
        return None;
    }
    let path = windows::Foundation::Uri::UnescapeComponent(&uri.Path().ok()?).ok()?.to_string_lossy();
    let path = path.strip_prefix('/').unwrap_or(&path).replace('/', "\\");
    let host = uri.Host().ok().map(|host| host.to_string_lossy()).unwrap_or_default();
    Some(if host.is_empty() { PathBuf::from(path) } else { PathBuf::from(format!(r"\\{host}\{path}")) })
}

/// `\\?\C:\dir` → `C:\dir`, `\\?\UNC\server\share` → `\\server\share`.
fn strip_verbatim_prefix(path: &Path) -> PathBuf {
    let wide: Vec<u16> = path.as_os_str().encode_wide().collect();
    let verbatim: Vec<u16> = r"\\?\".encode_utf16().collect();
    let verbatim_unc: Vec<u16> = r"\\?\UNC\".encode_utf16().collect();

    if wide.starts_with(&verbatim_unc) {
        let mut rebuilt: Vec<u16> = r"\\".encode_utf16().collect();
        rebuilt.extend_from_slice(&wide[verbatim_unc.len()..]);
        PathBuf::from(OsString::from_wide(&rebuilt))
    } else if wide.starts_with(&verbatim) {
        PathBuf::from(OsString::from_wide(&wide[verbatim.len()..]))
    } else {
        path.to_path_buf()
    }
}

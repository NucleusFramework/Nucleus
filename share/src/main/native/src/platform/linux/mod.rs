//! XDG desktop portal: Linux has no share sheet, so a single file or URL goes to the
//! portal's "Open With" chooser and a mixed payload to its "save files" dialog, with
//! `xdg-open` as the fallback when no portal answers. Ported from `robius-share`.

use std::{
    ffi::OsString,
    fs::OpenOptions,
    io::Write,
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::atomic::{AtomicUsize, Ordering},
};

use crate::{Completion, Error, Parent, Result, ShareItem, ShareOptions};

mod dbus;

pub(crate) fn share(options: ShareOptions, parent: Parent) -> Result<()> {
    let Parent { portal, done, .. } = parent;
    let portal = portal.filter(|it| !it.is_empty()).unwrap_or_else(active_x11_parent);
    LinuxPayload::new(&options)?.open(portal, done)
}

enum LinuxPayload {
    Uri(String),
    Path { path: PathBuf, cleanup: bool },
    // A mixed payload has no single thing to "open", so we just save it to disk.
    Save { title: String, items: Vec<SaveItem> },
}

struct SaveItem {
    name: String,
    source: SaveSource,
}

enum SaveSource {
    File(PathBuf),
    Text(String),
}

impl SaveItem {
    fn write_to(&self, dest: &std::path::Path) -> std::io::Result<()> {
        match &self.source {
            SaveSource::File(src) => std::fs::copy(src, dest).map(|_| ()),
            SaveSource::Text(text) => std::fs::write(dest, text),
        }
    }
}

impl LinuxPayload {
    fn new(options: &ShareOptions) -> Result<Self> {
        if options.items.len() == 1 {
            return match &options.items[0] {
                ShareItem::Text(text) => Ok(Self::Path {
                    path: write_temp_text_file(text)?,
                    cleanup: true,
                }),
                ShareItem::Url(url) => Ok(Self::Uri(url.clone())),
                ShareItem::File { path } => path_payload(Path::new(path)),
                ShareItem::FileUri { uri } => uri_payload(uri),
            };
        }

        // No single thing to "open", so turn each item into its own file and let
        // the user save them all to a folder of their choice.
        let mut items = Vec::new();
        for item in &options.items {
            match item {
                ShareItem::Text(text) => items.push(SaveItem {
                    name: "text.txt".to_owned(),
                    source: SaveSource::Text(text.clone()),
                }),
                ShareItem::Url(url) => items.push(url_link(url)),
                ShareItem::File { path } => {
                    if let Some((name, path)) = save_source(std::fs::canonicalize(path).ok()) {
                        items.push(SaveItem { name, source: SaveSource::File(path) });
                    }
                }
                ShareItem::FileUri { uri } => {
                    let path = file_uri_to_path(uri).filter(|path| path.exists());
                    if let Some((name, path)) = save_source(path) {
                        items.push(SaveItem { name, source: SaveSource::File(path) });
                    }
                }
            }
        }
        // If all items couldn't be made into files, just return an error.
        if items.is_empty() {
            return Err(Error::InvalidItem);
        }
        dedup_names(&mut items);
        Ok(Self::Save { title: dialog_title(options), items })
    }

    /// `done` is released once the portal dialog answered — the parent's export must
    /// outlive the dialog — or right away when there is none to wait for.
    fn open(self, parent: String, done: Completion) -> Result<()> {
        match self {
            LinuxPayload::Path { path, cleanup } => open_path(&parent, path, cleanup, done),
            LinuxPayload::Uri(uri) => open_uri(&parent, uri, done),
            LinuxPayload::Save { title, items } => {
                // The dialog stays up for as long as the user wants: never block the caller.
                std::thread::Builder::new()
                    .name("nucleus-share".to_owned())
                    .spawn(move || {
                        save_bundle(&parent, &title, items);
                        drop(done);
                    })
                    .map(|_| ())
                    .map_err(Error::Io)
            }
        }
    }
}

fn open_path(parent: &str, path: PathBuf, cleanup: bool, done: Completion) -> Result<()> {
    if let Ok(request) = dbus::open_path(parent, &path, true) {
        return wait_for_portal_request(request, cleanup.then_some(path), done);
    }

    let result = open_with_xdg(path.clone().into_os_string());
    if cleanup && result.is_err() {
        remove_temp_file(&path);
    }
    result
}

fn open_uri(parent: &str, uri: String, done: Completion) -> Result<()> {
    if let Ok(request) = dbus::open_uri(parent, &uri, true) {
        return wait_for_portal_request(request, None, done);
    }
    open_with_xdg(OsString::from(uri))
}

fn wait_for_portal_request(request: dbus::Request, cleanup: Option<PathBuf>, done: Completion) -> Result<()> {
    std::thread::Builder::new()
        .name("nucleus-share-portal".to_owned())
        .spawn(move || {
            let _ = request.wait();
            drop(done);
            if let Some(path) = cleanup {
                remove_temp_file(&path);
            }
        })
        .map(|_| ())
        .map_err(Error::Io)
}

/// Ask the portal to save the bundle to a folder, then write each item there.
fn save_bundle(parent: &str, title: &str, items: Vec<SaveItem>) {
    let names = items
        .iter()
        .map(|item| {
            let mut name = item.name.clone().into_bytes();
            name.push(0); // yea, we still use NUL-terminated strings in old school linux stuff
            name
        })
        .collect::<Vec<_>>();

    match dbus::save_files(parent, title, &names) {
        Ok(dests) => {
            if dests.len() == items.len() {
                for (dest, item) in dests.iter().zip(&items) {
                    let _ = item.write_to(dest);
                }
            }
        }
        // Err means no d-bus portal, so just open the first item with `xdg-open` instead.
        Err(_) => {
            let arg = items
                .iter()
                .find_map(|item| match &item.source {
                    SaveSource::Text(text) => {
                        write_temp_text_file(text).ok().map(PathBuf::into_os_string)
                    }
                    SaveSource::File(_) => None,
                })
                .or_else(|| {
                    items.iter().find_map(|item| match &item.source {
                        SaveSource::File(path) => Some(path.clone().into_os_string()),
                        SaveSource::Text(_) => None,
                    })
                });
            if let Some(arg) = arg {
                let _ = open_with_xdg(arg);
            }
        }
    }
}

/// Turn a URL into an `.html` file, which is the only file that all Linux distros
/// seem to reliably open in a browser.
fn url_link(url: &str) -> SaveItem {
    // Only auto-redirecting URLs get treated by DEs as a clickable `.html`
    let scheme = url.split_once(':').map(|(s, _)| s.to_ascii_lowercase());
    if !matches!(scheme.as_deref(), Some("http" | "https" | "ftp" | "ftps")) {
        return SaveItem {
            name: "link.txt".to_owned(),
            source: SaveSource::Text(format!("{url}\n")),
        };
    }
    let name = url_host(url)
        .map(|host| format!("{host}.html"))
        .unwrap_or_else(|| "link.html".to_owned());
    let url = html_escape(url);
    // just a really simple auto-redirect html file template
    let contents = format!(
        "<!doctype html><meta charset=utf-8>\n\
         <meta http-equiv=refresh content=\"0; url={url}\">\n\
         <title>{url}</title>\n\
         <a href=\"{url}\">{url}</a>\n"
    );
    SaveItem { name, source: SaveSource::Text(contents) }
}

fn html_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            _ => out.push(c),
        }
    }
    out
}

/// Extract a filesystem-safe host name from a URL, if it has one.
fn url_host(url: &str) -> Option<String> {
    let host = url.split_once("://")?.1.split(['/', '?', '#']).next()?;
    if host.is_empty() {
        return None;
    }
    let safe = host
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' { c } else { '_' })
        .collect();
    Some(safe)
}

/// Returns the save dialog title: the share title, or the subject, otherwise a default.
fn dialog_title(options: &ShareOptions) -> String {
    options.title.clone()
        .or_else(|| options.subject.clone())
        .unwrap_or_else(|| "Save shared files".to_owned())
}

/// Best-effort portal parent when the caller gave none: the active X11 window, via
/// `xprop`. Wayland windows cannot be named this way — pass Tao's `xdgPortalParent`.
fn active_x11_parent() -> String {
    // `xprop` needs a reachable X server (real X11, or XWayland under Wayland).
    if std::env::var_os("DISPLAY").is_none() {
        return String::new();
    }
    active_x11_window()
        .map(|xid| format!("x11:{xid:x}"))
        .unwrap_or_default()
}

/// Returns the current X11 window ID (`_NET_ACTIVE_WINDOW`) via xprop.
fn active_x11_window() -> Option<u64> {
    let output = Command::new("xprop")
        .args(["-root", "-notype", "_NET_ACTIVE_WINDOW"])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    // "_NET_ACTIVE_WINDOW" is a property formatted as a hex number, like "... 0x2600011"
    let text = String::from_utf8_lossy(&output.stdout);
    let hex = text.rsplit("0x").next()?.trim();
    u64::from_str_radix(hex, 16).ok().filter(|&id| id != 0)
}

/// Resolve a shared file to a tuple of `(suggested name, readable path)`.
fn save_source(path: Option<PathBuf>) -> Option<(String, PathBuf)> {
    let path = path?;
    let name = path.file_name()?.to_string_lossy().into_owned();
    Some((name, path))
}

/// Make the suggested file names unique so none overwrite each other.
fn dedup_names(items: &mut [SaveItem]) {
    let mut seen = std::collections::HashSet::new();
    for item in items.iter_mut() {
        if seen.insert(item.name.clone()) {
            continue;
        }
        let (stem, ext) = match item.name.rsplit_once('.') {
            Some((stem, ext)) => (stem.to_owned(), format!(".{ext}")),
            None => (item.name.clone(), String::new()),
        };
        let mut n = 1;
        loop {
            let candidate = format!("{stem}-{n}{ext}");
            if seen.insert(candidate.clone()) {
                item.name = candidate;
                break;
            }
            n += 1;
        }
    }
}

fn path_payload(path: &Path) -> Result<LinuxPayload> {
    Ok(LinuxPayload::Path {
        path: std::fs::canonicalize(path)?,
        cleanup: false,
    })
}

fn uri_payload(uri: &str) -> Result<LinuxPayload> {
    if let Some(path) = file_uri_to_path(uri) {
        std::fs::metadata(&path)?;
        return Ok(LinuxPayload::Path {
            path,
            cleanup: false,
        });
    }

    Ok(LinuxPayload::Uri(uri.to_owned()))
}

fn remove_temp_file(path: &Path) {
    let _ = std::fs::remove_file(path);
}

fn open_with_xdg(arg: OsString) -> Result<()> {
    let child = Command::new("xdg-open")
        .arg(arg)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
    match child {
        Ok(mut child) => {
            // Spawn a thread so we don't block the caller while waiting
            // on the xdg-open child process.
            std::thread::spawn(move || {
                let _ = child.wait();
            });
            Ok(())
        }
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => Err(Error::NoHandler),
        Err(err) => Err(Error::Io(err)),
    }
}

fn write_temp_text_file(text: &str) -> Result<PathBuf> {
    static TEMP_FILE_SUFFIX: AtomicUsize = AtomicUsize::new(0);

    let temp_dir = temp_share_dir()?;
    for _ in 0..100 {
        let counter = TEMP_FILE_SUFFIX.fetch_add(1, Ordering::Relaxed);
        let path = temp_dir.join(format!(
            "nucleus-share-{}-{}.txt",
            std::process::id(),
            counter,
        ));

        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
        {
            Ok(mut file) => {
                file.write_all(text.as_bytes())?;
                return Ok(path);
            }
            Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(err) => return Err(Error::Io(err)),
        }
    }

    Err(Error::Io(std::io::Error::new(
        std::io::ErrorKind::AlreadyExists,
        "could not create a temporary share payload",
    )))
}

fn temp_share_dir() -> Result<PathBuf> {
    let base = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir);
    let dir = base.join("nucleus-share");
    std::fs::create_dir_all(&dir)?;
    std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700))?;
    Ok(dir)
}

fn file_uri_to_path(uri: &str) -> Option<PathBuf> {
    let uri = uri.strip_prefix("file://")?;
    let uri = uri.split(['?', '#']).next().unwrap_or(uri);
    let uri = if uri.starts_with("localhost/") {
        &uri["localhost".len()..]
    } else if !uri.starts_with('/') {
        return None;
    } else {
        uri
    };
    percent_decode(uri).map(PathBuf::from)
}

fn percent_decode(value: &str) -> Option<OsString> {
    let mut bytes = Vec::with_capacity(value.len());
    let mut input = value.as_bytes().iter().copied();
    while let Some(byte) = input.next() {
        if byte == b'%' {
            let high = input.next()?;
            let low = input.next()?;
            bytes.push((hex_value(high)? << 4) | hex_value(low)?);
        } else {
            bytes.push(byte);
        }
    }

    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStringExt;
        Some(OsString::from_vec(bytes))
    }
}

fn hex_value(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

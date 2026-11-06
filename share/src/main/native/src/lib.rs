//! JNI bridge behind `dev.nucleusframework.share.ShareSheet` on desktop.
//!
//! The payload model and the per-platform strategies follow `robius-share`
//! (https://github.com/project-robius/robius/tree/main/crates/share, MIT):
//! Windows goes through the WinRT Share UI (`DataTransferManager` desktop interop),
//! macOS through `NSSharingServicePicker`, Linux through the XDG desktop portal.
//! Validation happens on the Kotlin side; this crate only maps and presents.

// Each platform reads a different subset of the payload model and error set.
#![allow(dead_code)]

mod completion;
mod error;
mod platform;

use jni::objects::{JClass, JDoubleArray, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

pub(crate) use completion::Completion;
pub(crate) use error::{Error, Result};

/// A single payload item, mirrored from `ShareItem` in Kotlin. MIME type hints are
/// not forwarded: none of the desktop share UIs takes one.
#[derive(Clone, Debug)]
pub(crate) enum ShareItem {
    Text(String),
    Url(String),
    File { path: String },
    FileUri { uri: String },
}

/// The window the share UI is attached to. Every field is optional: the platform
/// falls back to the process's frontmost window when the caller gives none.
#[derive(Debug, Default)]
pub(crate) struct Parent {
    /// `HWND` on Windows, `NSWindow*` on macOS, unused on Linux.
    pub(crate) handle: i64,
    /// XDG portal `parent_window` (`x11:<hex>` / `wayland:<token>`), Linux only.
    pub(crate) portal: Option<String>,
    /// `[x, y, width, height]` in points, relative to the content view's top-left
    /// corner, macOS only.
    pub(crate) anchor: Option<[f64; 4]>,
    /// Released once the share UI no longer needs the parent: kept by a platform
    /// that waits for its dialog (Linux portal), dropped with the parent otherwise.
    pub(crate) done: Completion,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct ShareOptions {
    pub(crate) title: Option<String>,
    pub(crate) subject: Option<String>,
    pub(crate) items: Vec<ShareItem>,
}

impl ShareOptions {
    /// Text and URLs joined by newlines, in payload order.
    pub(crate) fn shared_text(&self) -> Option<String> {
        let text = self
            .items
            .iter()
            .filter_map(|item| match item {
                ShareItem::Text(text) | ShareItem::Url(text) => Some(text.as_str()),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join("\n");
        (!text.is_empty()).then_some(text)
    }

    pub(crate) fn first_url(&self) -> Option<&str> {
        self.items.iter().find_map(|item| match item {
            ShareItem::Url(url) => Some(url.as_str()),
            _ => None,
        })
    }
}

const KIND_TEXT: jint = 0;
const KIND_URL: jint = 1;
const KIND_FILE: jint = 2;
const KIND_FILE_URI: jint = 3;

/// Presents the share UI. Returns `0` on success, otherwise an [Error] code, with a
/// human-readable message stored in `message[0]`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_nucleusframework_share_NativeShareBridge_nativeShare<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    title: JString<'local>,
    subject: JString<'local>,
    kinds: JIntArray<'local>,
    values: JObjectArray<'local>,
    parent_handle: jlong,
    parent_portal: JString<'local>,
    anchor: JDoubleArray<'local>,
    on_parent_released: JObject<'local>,
    message: JObjectArray<'local>,
) -> jint {
    // First, so that no early return can skip it.
    let done = match Completion::new(&mut env, &on_parent_released) {
        Ok(done) => done,
        Err(error) => return report(&mut env, &message, jni_error(error)),
    };
    let result = read_request(&mut env, &title, &subject, &kinds, &values)
        .and_then(|options| {
            let parent = Parent {
                handle: parent_handle,
                portal: optional_string(&mut env, &parent_portal)?,
                anchor: read_anchor(&mut env, &anchor)?,
                done,
            };
            platform::share(options, parent)
        });

    match result {
        Ok(()) => 0,
        Err(error) => report(&mut env, &message, error),
    }
}

fn report(env: &mut JNIEnv, message: &JObjectArray, error: Error) -> jint {
    if let Ok(text) = env.new_string(error.to_string()) {
        let _ = env.set_object_array_element(message, 0, text);
    }
    error.code()
}

fn read_request(
    env: &mut JNIEnv,
    title: &JString,
    subject: &JString,
    kinds: &JIntArray,
    values: &JObjectArray,
) -> Result<ShareOptions> {
    let count = env.get_array_length(kinds).map_err(jni_error)? as usize;
    let mut raw_kinds = vec![0; count];
    env.get_int_array_region(kinds, 0, &mut raw_kinds).map_err(jni_error)?;

    let mut items = Vec::with_capacity(count);
    for (index, kind) in raw_kinds.into_iter().enumerate() {
        let value = string_at(env, values, index)?.ok_or(Error::InvalidItem)?;
        items.push(match kind {
            KIND_TEXT => ShareItem::Text(value),
            KIND_URL => ShareItem::Url(value),
            KIND_FILE => ShareItem::File { path: value },
            KIND_FILE_URI => ShareItem::FileUri { uri: value },
            _ => return Err(Error::InvalidItem),
        });
    }
    if items.is_empty() {
        return Err(Error::Empty);
    }

    Ok(ShareOptions {
        title: optional_string(env, title)?,
        subject: optional_string(env, subject)?,
        items,
    })
}

fn string_at(env: &mut JNIEnv, array: &JObjectArray, index: usize) -> Result<Option<String>> {
    let element = env.get_object_array_element(array, index as i32).map_err(jni_error)?;
    let string = JString::from(element);
    let value = optional_string(env, &string);
    let _ = env.delete_local_ref(string);
    value
}

fn optional_string(env: &mut JNIEnv, string: &JString) -> Result<Option<String>> {
    if string.is_null() {
        return Ok(None);
    }
    let value: String = env.get_string(string).map_err(jni_error)?.into();
    Ok(Some(value))
}

fn read_anchor(env: &mut JNIEnv, anchor: &JDoubleArray) -> Result<Option<[f64; 4]>> {
    if anchor.is_null() {
        return Ok(None);
    }
    let mut rect = [0.0; 4];
    env.get_double_array_region(anchor, 0, &mut rect).map_err(jni_error)?;
    Ok(Some(rect))
}

fn jni_error(error: jni::errors::Error) -> Error {
    Error::Platform(format!("JNI: {error}"))
}

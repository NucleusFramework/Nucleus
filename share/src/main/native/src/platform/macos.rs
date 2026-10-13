//! `NSSharingServicePicker`, anchored on the parent window's content view.
#![allow(unused_unsafe)]

use std::cell::RefCell;
use std::sync::mpsc;
use std::time::Duration;

use dispatch2::DispatchQueue;
use objc2::rc::Retained;
use objc2::runtime::AnyObject;
use objc2::MainThreadMarker;
use objc2_app_kit::{NSApplication, NSSharingServicePicker, NSView, NSWindow};
use objc2_foundation::{NSArray, NSPoint, NSRect, NSRectEdge, NSSize, NSString, NSURL};

use crate::{Error, Parent, Result, ShareItem, ShareOptions};

thread_local! {
    /// The picker on screen. AppKit does not document that it keeps the picker alive
    /// while its popover is shown, so the last one is retained until the next share.
    static PICKER: RefCell<Option<Retained<NSSharingServicePicker>>> = const { RefCell::new(None) };
}

/// How long a caller off the main thread waits for AppKit. Without an `NSApplication`
/// running, nothing ever drains the main queue: fail instead of hanging.
const MAIN_THREAD_TIMEOUT: Duration = Duration::from_secs(10);

pub(crate) fn share(options: ShareOptions, parent: Parent) -> Result<()> {
    if let Some(mtm) = MainThreadMarker::new() {
        return share_on_main(mtm, options, parent);
    }
    let (sender, receiver) = mpsc::channel();
    DispatchQueue::main().exec_async(move || {
        // SAFETY: blocks submitted to the main queue run on the main thread.
        let mtm = unsafe { MainThreadMarker::new_unchecked() };
        let _ = sender.send(share_on_main(mtm, options, parent));
    });
    receiver.recv_timeout(MAIN_THREAD_TIMEOUT).map_err(|_| {
        Error::Platform("the AppKit main thread did not answer: is an NSApplication running?".to_owned())
    })?
}

fn share_on_main(mtm: MainThreadMarker, options: ShareOptions, parent: Parent) -> Result<()> {
    let window = resolve_window(mtm, parent.handle)?;
    let view = unsafe { window.contentView() }.ok_or(Error::NoWindow)?;

    let items = options.items.iter().map(item_object).collect::<Result<Vec<_>>>()?;
    let items = NSArray::from_retained_slice(&items);
    let picker = unsafe { NSSharingServicePicker::initWithItems(mtm.alloc(), &items) };

    let rect = anchor_rect(&view, parent.anchor);
    unsafe { picker.showRelativeToRect_ofView_preferredEdge(rect, &view, NSRectEdge::MinY) };
    PICKER.with(|slot| *slot.borrow_mut() = Some(picker));
    Ok(())
}

fn resolve_window(mtm: MainThreadMarker, handle: i64) -> Result<Retained<NSWindow>> {
    if handle != 0 {
        // SAFETY: the caller hands over a live `NSWindow*` (Tao's `nsWindowHandle`);
        // retaining it keeps it valid for the duration of this call.
        return unsafe { Retained::retain(handle as *mut NSWindow) }.ok_or(Error::NoWindow);
    }
    let application = NSApplication::sharedApplication(mtm);
    unsafe { application.keyWindow() }
        .or_else(|| unsafe { application.mainWindow() })
        .ok_or(Error::NoWindow)
}

/// The caller's anchor is in points from the content view's top-left corner; AppKit
/// views are bottom-left unless flipped. Without one, the picker hangs off the top
/// centre of the window.
fn anchor_rect(view: &NSView, anchor: Option<[f64; 4]>) -> NSRect {
    let bounds = view.bounds();
    let [x, y, width, height] = anchor.unwrap_or([bounds.size.width / 2.0, 0.0, 1.0, 1.0]);
    let y = if view.isFlipped() { y } else { bounds.size.height - y - height };
    NSRect::new(NSPoint::new(x, y), NSSize::new(width.max(1.0), height.max(1.0)))
}

fn item_object(item: &ShareItem) -> Result<Retained<AnyObject>> {
    match item {
        ShareItem::Text(text) => Ok(NSString::from_str(text).into_super().into_super()),
        ShareItem::Url(url) | ShareItem::FileUri { uri: url } => url_object(url),
        ShareItem::File { path } => {
            let path = std::fs::canonicalize(path)?;
            let path = path.to_str().ok_or(Error::InvalidItem)?;
            let url = unsafe { NSURL::fileURLWithPath(&NSString::from_str(path)) };
            Ok(url.into_super().into_super())
        }
    }
}

fn url_object(url: &str) -> Result<Retained<AnyObject>> {
    let url = unsafe { NSURL::URLWithString(&NSString::from_str(url)) }.ok_or(Error::InvalidItem)?;
    Ok(url.into_super().into_super())
}

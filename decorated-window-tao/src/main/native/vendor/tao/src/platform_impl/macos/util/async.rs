// Copyright 2014-2021 The winit contributors
// Copyright 2021-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0

use std::{
  ops::Deref,
  sync::{Arc, Mutex, Weak},
};

use core_graphics::base::CGFloat;
use dispatch2::{DispatchQueue, DispatchQueueAttr, DispatchTime};
use objc2::rc::Retained;
use std::time::{Duration, Instant};
use objc2::{rc::autoreleasepool, Message};
use objc2_app_kit::{NSScreen, NSView, NSWindow, NSWindowStyleMask};
use objc2_foundation::{MainThreadMarker, NSPoint, NSRect, NSSize, NSString};

use crate::{
  dpi::LogicalSize,
  platform_impl::platform::{
    ffi::{self, id, NO, YES},
    window::SharedState,
  },
};

pub fn is_main_thread() -> bool {
  unsafe { msg_send!(class!(NSThread), isMainThread) }
}

// Unsafe wrapper type that allows us to dispatch things that aren't Send.
// This should *only* be used to dispatch to the main queue.
// While it is indeed not guaranteed that these types can safely be sent to
// other threads, we know that they're safe to use on the main thread.
struct MainThreadSafe<T>(T);

unsafe impl<T> Send for MainThreadSafe<T> {}

impl<T> Deref for MainThreadSafe<T> {
  type Target = T;
  fn deref(&self) -> &T {
    &self.0
  }
}

fn run_on_main<F: Send + FnOnce()>(f: F) {
  if is_main_thread() {
    f();
  } else {
    DispatchQueue::main().exec_sync(f);
  }
}

unsafe fn set_style_mask(ns_window: &NSWindow, ns_view: &NSView, mask: NSWindowStyleMask) {
  ns_window.setStyleMask(mask);
  // If we don't do this, key handling will break
  // (at least until the window is clicked again/etc.)
  ns_window.makeFirstResponder(Some(ns_view));
}

// Always use this function instead of trying to modify `styleMask` directly!
// `setStyleMask:` isn't thread-safe, so we have to use Grand Central Dispatch.
// Otherwise, this would vomit out errors about not being on the main thread
// and fail to do anything.
pub unsafe fn set_style_mask_async(
  ns_window: &NSWindow,
  ns_view: &NSView,
  mask: NSWindowStyleMask,
) {
  let ns_window = MainThreadSafe(ns_window.retain());
  let ns_view = MainThreadSafe(ns_view.retain());
  DispatchQueue::main().exec_async(move || {
    set_style_mask(&ns_window, &ns_view, mask);
  });
}
pub unsafe fn set_style_mask_sync(ns_window: &NSWindow, ns_view: &NSView, mask: NSWindowStyleMask) {
  if is_main_thread() {
    set_style_mask(ns_window, ns_view, mask);
  } else {
    let ns_window = MainThreadSafe(ns_window.retain());
    let ns_view = MainThreadSafe(ns_view.retain());
    DispatchQueue::main().exec_sync(move || {
      set_style_mask(&ns_window, &ns_view, mask);
    })
  }
}

// `setContentSize:` isn't thread-safe either, though it doesn't log any errors
// and just fails silently. Anyway, GCD to the rescue!
pub unsafe fn set_content_size_async(ns_window: &NSWindow, size: LogicalSize<f64>) {
  // PATCH(nucleus): apply immediately on the main thread so a programmatic
  // WindowState.size animation (#576) doesn't leave the NSWindow a frame
  // behind the Compose scene. GCD async is only needed off-thread
  // (`setContentSize:` is not thread-safe). Mirrors `set_frame_top_left_point_async`.
  if is_main_thread() {
    ns_window.setContentSize(NSSize::new(size.width as CGFloat, size.height as CGFloat));
    return;
  }
  let ns_window = MainThreadSafe(ns_window.retain());
  DispatchQueue::main().exec_async(move || {
    ns_window.setContentSize(NSSize::new(size.width as CGFloat, size.height as CGFloat));
  });
}

// `setFrameTopLeftPoint:` isn't thread-safe, but fortunately has the courtesy
// to log errors. When we're already on the main thread, apply immediately so
// a pre-show position update lands before a following `setVisible(true)`.
pub unsafe fn set_frame_top_left_point_async(ns_window: &NSWindow, point: NSPoint) {
  if is_main_thread() {
    ns_window.setFrameTopLeftPoint(point);
    return;
  }
  let ns_window = MainThreadSafe(ns_window.retain());
  DispatchQueue::main().exec_async(move || {
    ns_window.setFrameTopLeftPoint(point);
  });
}

// `setFrameTopLeftPoint:` isn't thread-safe, and fails silently.
pub unsafe fn set_level_async(ns_window: &NSWindow, level: ffi::NSWindowLevel) {
  let ns_window = MainThreadSafe(ns_window.retain());
  DispatchQueue::main().exec_async(move || {
    ns_window.setLevel(level as _);
  });
}

// `toggleFullScreen` is thread-safe, but our additional logic to account for
// window styles isn't.
pub unsafe fn toggle_full_screen_async(
  ns_window: &NSWindow,
  ns_view: &NSView,
  not_fullscreen: bool,
  shared_state: Weak<Mutex<SharedState>>,
) {
  let ns_window = MainThreadSafe(ns_window.retain());
  let ns_view = MainThreadSafe(ns_view.retain());
  let shared_state = MainThreadSafe(shared_state);
  DispatchQueue::main().exec_async(move || {
    // `toggleFullScreen` doesn't work if the `StyleMask` is none, so we
    // set a normal style temporarily. The previous state will be
    // restored in `WindowDelegate::window_did_exit_fullscreen`.
    if not_fullscreen {
      let curr_mask = ns_window.styleMask();
      let required = NSWindowStyleMask::Titled | NSWindowStyleMask::Resizable;
      if !curr_mask.contains(required) {
        set_style_mask(&ns_window, &ns_view, required);
        if let Some(shared_state) = shared_state.upgrade() {
          trace!("Locked shared state in `toggle_full_screen_callback`");
          let mut shared_state_lock = shared_state.lock().unwrap();
          shared_state_lock.saved_style = Some(curr_mask);
          trace!("Unlocked shared state in `toggle_full_screen_callback`");
        }
      }
    }
    // Window level must be restored from `CGShieldingWindowLevel()
    // + 1` back to normal in order for `toggleFullScreen` to do
    // anything
    ns_window.setLevel(0);
    ns_window.toggleFullScreen(None);
  });
}

pub unsafe fn restore_display_mode_async(ns_screen: u32) {
  DispatchQueue::main().exec_async(move || {
    ffi::CGRestorePermanentDisplayConfiguration();
    assert_eq!(ffi::CGDisplayRelease(ns_screen), ffi::kCGErrorSuccess);
  });
}

// `setMaximized` is not thread-safe
pub unsafe fn set_maximized_async(
  ns_window: &NSWindow,
  is_zoomed: bool,
  maximized: bool,
  shared_state: Weak<Mutex<SharedState>>,
) {
  let ns_window = MainThreadSafe(ns_window.retain());
  let shared_state = MainThreadSafe(shared_state);
  DispatchQueue::main().exec_async(move || {
    if let Some(shared_state) = shared_state.upgrade() {
      trace!("Locked shared state in `set_maximized`");
      let mut shared_state_lock = shared_state.lock().unwrap();

      // Save the standard frame sized if it is not zoomed.
      // PATCH(nucleus): only when actually maximizing and no zoom animation
      // is in flight — a request issued mid-animation sees `is_zoomed ==
      // false` (frame mid-flight), and saving here would overwrite the real
      // pre-zoom frame with a half-grown one, making the restore target wrong.
      if !is_zoomed && maximized && !shared_state_lock.zoom_animating {
        shared_state_lock.standard_frame = Some(NSWindow::frame(&ns_window));
      }

      shared_state_lock.maximized = maximized;

      let curr_mask = ns_window.styleMask();
      if shared_state_lock.fullscreen.is_some() {
        // Handle it in window_did_exit_fullscreen
        return;
      } else if curr_mask.contains(NSWindowStyleMask::Resizable)
        && curr_mask.contains(NSWindowStyleMask::Titled)
      {
        // PATCH(nucleus): upstream calls `ns_window.zoom(None)` here. AppKit's
        // `zoom:` runs its resize animation SYNCHRONOUSLY on the main thread
        // (~350 ms) in a private run-loop mode that services neither the main
        // dispatch queue nor observers registered on `kCFRunLoopCommonModes`,
        // so the embedder saw a single Resized at the end and the content
        // snapped into place. Animating through the NSWindow animator proxy
        // instead delivered a Resized per step, but the steps are Core
        // Animation's: overlapping requests run overlapping animations whose
        // final frame is whichever finishes last, and presenting the content
        // synchronously on each step (Nucleus #576) left them stopping
        // mid-flight.
        //
        // So step the frame ourselves, on the main queue, 60 times a second,
        // easing between the current frame and the zoom target (the same
        // frames `zoom:` uses: screen visibleFrame ⇄ saved standard frame)
        // over `animationResizeTime:`. Every step is a plain `setFrame:`, so
        // the embedder gets one Resized per step and can present the content
        // for it in the same turn; a new request bumps `zoom_generation`,
        // which stops the chain in flight and starts over from the frame it
        // had reached. `is_zoomed()` is frame-based (see window.rs) so
        // bypassing `zoom:` keeps the maximized-state tracking consistent.
        let mtm = MainThreadMarker::new_unchecked();
        let screen = ns_window.screen().or_else(|| NSScreen::mainScreen(mtm));
        let target = if maximized {
          match screen {
            Some(screen) => NSScreen::visibleFrame(&screen),
            None => return,
          }
        } else {
          shared_state_lock.saved_standard_frame()
        };
        let duration: f64 = msg_send![&*ns_window, animationResizeTime: target];
        shared_state_lock.zoom_generation += 1;
        shared_state_lock.zoom_animating = true;
        let generation = shared_state_lock.zoom_generation;
        let from = NSWindow::frame(&ns_window);
        drop(shared_state_lock);
        zoom_step(
          MainThreadSafe((*ns_window).retain()),
          MainThreadSafe(Arc::downgrade(&shared_state)),
          generation,
          from,
          target,
          Instant::now(),
          duration.max(ZOOM_MIN_DURATION_SECS),
        );
        return;
      } else {
        // if it's not resizable, we set the frame directly
        let new_rect = if maximized {
          let mtm = MainThreadMarker::new_unchecked();
          let screen = NSScreen::mainScreen(mtm).unwrap();
          NSScreen::visibleFrame(&screen)
        } else {
          shared_state_lock.saved_standard_frame()
        };
        let _: () = msg_send![&*ns_window, setFrame:new_rect, display:NO, animate: YES];
      }

      trace!("Unlocked shared state in `set_maximized`");
    }
  });
}

// `orderOut:` isn't thread-safe. Calling it from another thread actually works,
// but with an odd delay.
pub unsafe fn order_out_sync(ns_window: &NSWindow) {
  let ns_window = MainThreadSafe(ns_window.retain());
  run_on_main(move || {
    ns_window.orderOut(None);
  });
}

// `makeKeyAndOrderFront:` isn't thread-safe. Calling it from another thread
// actually works, but with an odd delay.
pub unsafe fn make_key_and_order_front_sync(ns_window: &NSWindow) {
  let ns_window = MainThreadSafe(ns_window.retain());
  run_on_main(move || {
    ns_window.makeKeyAndOrderFront(None);
  });
}

// `setTitle:` isn't thread-safe. Calling it from another thread invalidates the
// window drag regions, which throws an exception when not done in the main
// thread
pub unsafe fn set_title_async(ns_window: &NSWindow, title: String) {
  let ns_window = MainThreadSafe(ns_window.retain());
  DispatchQueue::main().exec_async(move || {
    let title = NSString::from_str(&title);
    ns_window.setTitle(&title);
  });
}

// `setFocus:` isn't thread-safe.
pub unsafe fn set_focus(ns_window: &NSWindow) {
  let ns_window = MainThreadSafe(ns_window.retain());
  run_on_main(move || {
    ns_window.makeKeyAndOrderFront(None);
    let app: id = msg_send![class!(NSApplication), sharedApplication];
    let () = msg_send![app, activateIgnoringOtherApps: YES];
  });
}

// `close:` is thread-safe, but we want the event to be triggered from the main
// thread. Though, it's a good idea to look into that more...
pub unsafe fn close_async(ns_window: &NSWindow) {
  let ns_window = MainThreadSafe(ns_window.retain());
  run_on_main(move || {
    autoreleasepool(move |_| {
      ns_window.close();
    });
  });
}

// `setIgnoresMouseEvents_:` isn't thread-safe, and fails silently.
pub unsafe fn set_ignore_mouse_events(ns_window: &NSWindow, ignore: bool) {
  let ns_window = MainThreadSafe(ns_window.retain());
  DispatchQueue::main().exec_async(move || {
    ns_window.setIgnoresMouseEvents(ignore);
  });
}

// PATCH(nucleus): one step of the zoom animation started by
// `set_maximized_async`; re-schedules itself until the target is reached or
// a newer request has bumped `zoom_generation`.
const ZOOM_STEP_MS: u64 = 16;
const ZOOM_MIN_DURATION_SECS: f64 = 0.05;

unsafe fn zoom_step(
  ns_window: MainThreadSafe<Retained<NSWindow>>,
  shared_state: MainThreadSafe<Weak<Mutex<SharedState>>>,
  generation: u64,
  from: NSRect,
  to: NSRect,
  started: Instant,
  duration: f64,
) {
  let when = DispatchTime::try_from(Duration::from_millis(ZOOM_STEP_MS)).unwrap_or(DispatchTime::NOW);
  let _ = DispatchQueue::main().after(when, move || {
    let Some(state) = shared_state.upgrade() else {
      return;
    };
    if state.lock().unwrap().zoom_generation != generation {
      return;
    }
    let t = (started.elapsed().as_secs_f64() / duration).min(1.0);
    // Ease in-out, like AppKit's own window frame animation.
    let e = 0.5 - 0.5 * (std::f64::consts::PI * t).cos();
    let frame = NSRect::new(
      NSPoint::new(
        from.origin.x + (to.origin.x - from.origin.x) * e,
        from.origin.y + (to.origin.y - from.origin.y) * e,
      ),
      NSSize::new(
        from.size.width + (to.size.width - from.size.width) * e,
        from.size.height + (to.size.height - from.size.height) * e,
      ),
    );
    ns_window.setFrame_display(frame, true);
    if t < 1.0 {
      zoom_step(ns_window, shared_state, generation, from, to, started, duration);
    } else {
      state.lock().unwrap().zoom_animating = false;
    }
  });
}

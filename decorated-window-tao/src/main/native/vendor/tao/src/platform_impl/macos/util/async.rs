// Copyright 2014-2021 The winit contributors
// Copyright 2021-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0

// TODO: Migrate this to https://github.com/rust-windowing/winit/pull/4589

use std::{
  ops::Deref,
  time::{Duration, Instant},
};

use dispatch2::{DispatchQueue, DispatchTime};
use objc2::{
  msg_send,
  rc::{Retained, Weak},
  DefinedClass, MainThreadMarker, Message,
};
use objc2_app_kit::{NSScreen, NSWindowStyleMask};
use objc2_foundation::{NSPoint, NSRect, NSSize};

use crate::platform_impl::platform::window_delegate::{WindowDelegate, DEFAULT_STANDARD_FRAME};

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

// `toggleFullScreen` is thread-safe, but our additional logic to account for
// window styles isn't.
pub unsafe fn toggle_full_screen_async(
  window_delegate: Retained<WindowDelegate>,
  not_fullscreen: bool,
) {
  let window_delegate = MainThreadSafe(window_delegate);
  DispatchQueue::main().exec_async(move || {
    // `toggleFullScreen` doesn't work if the `StyleMask` is none, so we
    // set a normal style temporarily. The previous state will be
    // restored in `WindowDelegate::window_did_exit_fullscreen`.
    if not_fullscreen {
      let curr_mask = window_delegate.window().styleMask();
      let required = NSWindowStyleMask::Titled | NSWindowStyleMask::Resizable;
      if !curr_mask.contains(required) {
        window_delegate.set_style_mask(required);
        window_delegate.ivars().saved_style.set(Some(curr_mask));
      }
    }
    // Window level must be restored from `CGShieldingWindowLevel()
    // + 1` back to normal in order for `toggleFullScreen` to do
    // anything
    window_delegate.window().setLevel(0);
    window_delegate.window().toggleFullScreen(None);
  });
}

// `setMaximized` is not thread-safe
pub unsafe fn set_maximized_async(
  window_delegate: Retained<WindowDelegate>,
  is_zoomed: bool,
  maximized: bool,
) {
  let window_delegate = MainThreadSafe(window_delegate);
  DispatchQueue::main().exec_async(move || {
    unsafe {
      let ivars = window_delegate.ivars();
      // Save the standard frame if it is not zoomed.
      // PATCH(nucleus): only when actually maximizing and no zoom animation
      // is in flight — a request issued mid-animation sees `is_zoomed ==
      // false` (frame mid-flight), and saving here would overwrite the real
      // pre-zoom frame with a half-grown one, making the restore target wrong.
      if !is_zoomed && maximized && !ivars.zoom_animating.get() {
        ivars
          .standard_frame
          .set(Some(window_delegate.window().frame()));
      }
      ivars.maximized.set(maximized);
      if ivars.fullscreen.borrow().is_some() {
        // Apply the requested state when native fullscreen ends.
        return;
      }
      // PATCH(nucleus): upstream calls `zoom(None)` on a resizable titled
      // window and `setFrame:display:NO animate:YES` otherwise — both
      // AppKit's blocking animator, which `TaoWindow` reroutes to
      // `animate_frame` below (`set_frame_display_animate`, window.rs). Zoom
      // between the frames `zoom:` uses: screen visibleFrame ⇄ saved standard
      // frame. `is_zoomed()` is frame-based (see window_delegate.rs) so
      // bypassing `zoom:` keeps the maximized-state tracking consistent.
      let target = if maximized {
        let mtm = MainThreadMarker::from(window_delegate.window());
        match window_delegate
          .window()
          .screen()
          .or_else(|| NSScreen::mainScreen(mtm))
        {
          Some(screen) => screen.visibleFrame(),
          None => return,
        }
      } else {
        ivars.standard_frame.get().unwrap_or(DEFAULT_STANDARD_FRAME)
      };
      window_delegate
        .window()
        .setFrame_display_animate(target, true, true);
    }
  });
}

// PATCH(nucleus): tao's frame animation — what `setFrame:display:animate:YES`
// resolves to on a `TaoWindow` (window.rs): `set_maximized_async` above, and
// the zooms AppKit starts on its own — a double-click on a resize edge
// (`_zoomToScreenEdge:`), the Window-menu tiling (`_zoomLeft:` and friends),
// `zoom:`. AppKit's own animator runs SYNCHRONOUSLY on the main thread
// (~250 ms) in a private run-loop mode that services neither the main
// dispatch queue nor observers registered on `kCFRunLoopCommonModes`, so every
// step's `windowDidResize:` only queued a `Resized` and the embedder got the
// whole run once the window already sat at the target: the content snapped
// into place (Nucleus #576). Animating through the NSWindow animator proxy
// instead delivered a Resized per step, but the steps are Core Animation's:
// overlapping requests run overlapping animations whose final frame is
// whichever finishes last, and presenting the content synchronously on each
// step left them stopping mid-flight.
//
// So step the frame ourselves, on the main queue, 60 times a second, easing
// between the current frame and the target over `animationResizeTime:`. Every
// step is a plain `setFrame:display:`, so the embedder gets one Resized per
// step and can present the content for it in the same turn; a new request
// bumps `zoom_generation`, which stops the chain in flight and starts over
// from the frame it had reached.
pub(crate) unsafe fn animate_frame(window_delegate: &WindowDelegate, target: NSRect) {
  let ns_window = window_delegate.window();
  let duration: f64 = msg_send![ns_window, animationResizeTime: target];
  let ivars = window_delegate.ivars();
  let generation = ivars.zoom_generation.get() + 1;
  ivars.zoom_generation.set(generation);
  ivars.zoom_animating.set(true);
  zoom_step(
    MainThreadSafe(Weak::from_retained(&window_delegate.retain())),
    generation,
    ns_window.frame(),
    target,
    Instant::now(),
    duration.max(ZOOM_MIN_DURATION_SECS),
    Duration::from_millis(ZOOM_STEP_MS),
  );
}

// One step of `animate_frame`, run `delay` from now; re-schedules itself until
// the target is reached or a newer request has bumped `zoom_generation`. The
// next step is due ZOOM_STEP_MS after this one *started*: the embedder
// presents the content of each step inside its `setFrame:` (Nucleus #953), and
// counting that time on top of the interval would slow the cadence by it.
const ZOOM_STEP_MS: u64 = 16;
const ZOOM_MIN_DURATION_SECS: f64 = 0.05;

unsafe fn zoom_step(
  window_delegate: MainThreadSafe<Weak<WindowDelegate>>,
  generation: u64,
  from: NSRect,
  to: NSRect,
  started: Instant,
  duration: f64,
  delay: Duration,
) {
  let when = DispatchTime::try_from(delay).unwrap_or(DispatchTime::NOW);
  let _ = DispatchQueue::main().after(when, move || {
    let Some(delegate) = window_delegate.load() else {
      return;
    };
    let ivars = delegate.ivars();
    if ivars.zoom_generation.get() != generation {
      return;
    }
    let step_started = Instant::now();
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
    delegate.window().setFrame_display(frame, true);
    if t < 1.0 {
      let delay = Duration::from_millis(ZOOM_STEP_MS).saturating_sub(step_started.elapsed());
      zoom_step(window_delegate, generation, from, to, started, duration, delay);
    } else {
      ivars.zoom_animating.set(false);
    }
  });
}

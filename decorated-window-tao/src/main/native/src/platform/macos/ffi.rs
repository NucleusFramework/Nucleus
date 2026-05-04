// FFI declarations for Objective-C helpers compiled into the crate by
// `build.rs` (see `objc/*.m`). Visibility is `pub(crate)` so siblings under
// `platform::macos::*` can reach them; outside callers should use the higher-
// level wrappers in those submodules.

use std::ffi::c_void;

extern "C" {
    pub(crate) fn nucleus_tao_run_on_main_blocking(
        entry: extern "C" fn(*mut c_void),
        context: *mut c_void,
    );
    pub(crate) fn nucleus_tao_is_main_thread() -> i32;
    pub(crate) fn nucleus_tao_install_cmd_q_handler();
    pub(crate) fn nucleus_tao_enable_press_and_hold();
    pub(crate) fn nucleus_tao_activate_input_context(ns_view_handle: i64);
    pub(crate) fn nucleus_tao_set_ime_local_rect(
        ns_view_handle: i64,
        x_px: f64,
        y_px: f64,
        w_px: f64,
        h_px: f64,
    );
    pub(crate) fn nucleus_tao_attach_text_overlay(ns_view_handle: i64);
    pub(crate) fn nucleus_tao_focus_text_overlay(focused: i32);
    pub(crate) fn nucleus_tao_a11y_attach(ns_view_handle: i64);
    pub(crate) fn nucleus_tao_a11y_detach(ns_view_handle: i64);
    pub(crate) fn nucleus_tao_a11y_apply_snapshot(
        ns_view_handle: i64,
        bytes: *const u8,
        len: usize,
    ) -> i32;
    pub(crate) fn nucleus_tao_a11y_post_focus_changed(ns_view_handle: i64, node_id: u64);
    pub(crate) fn nucleus_tao_a11y_is_voiceover_running() -> i32;
    pub(crate) fn nucleus_tao_apple_events_install();
    pub(crate) fn nucleus_tao_a11y_is_active() -> i32;
    pub(crate) fn nucleus_tao_a11y_consume_resync() -> i32;
    pub(crate) fn nucleus_tao_a11y_note_pushed();
    pub(crate) fn nucleus_tao_install_drag_monitor();
    pub(crate) fn nucleus_tao_start_window_drag(ns_window_ptr: i64);
}

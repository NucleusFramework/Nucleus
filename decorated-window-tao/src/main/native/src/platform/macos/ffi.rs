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
    pub(crate) fn nucleus_tao_activate_input_context(ns_view_handle: i64);
    /// Pushes the focused field's committed text (a bounded UTF-16 window),
    /// selection and composition so the swizzled `NSTextInputClient` getters
    /// can answer AppKit like a document-backed client (Chromium's
    /// `setTextSelectionText:offset:range:` cache). Negative selection
    /// offsets invalidate the cache (no focused field).
    pub(crate) fn nucleus_tao_set_ime_document(
        ns_view_handle: i64,
        utf16: *const u16,
        utf16_len: i64,
        offset: i64,
        sel_start: i64,
        sel_end: i64,
    );
    pub(crate) fn nucleus_tao_set_ime_local_rect(
        ns_view_handle: i64,
        x_px: f64,
        y_px: f64,
        w_px: f64,
        h_px: f64,
    );
    pub(crate) fn nucleus_tao_set_cursor_icon(code: i32);
    pub(crate) fn nucleus_tao_a11y_attach(ns_view_handle: i64);
    pub(crate) fn nucleus_tao_a11y_detach(ns_view_handle: i64);
    pub(crate) fn nucleus_tao_a11y_apply_snapshot(
        ns_view_handle: i64,
        bytes: *const u8,
        len: usize,
    ) -> i32;
    pub(crate) fn nucleus_tao_a11y_post_focus_changed(ns_view_handle: i64, node_id: u64);
    pub(crate) fn nucleus_tao_a11y_set_external_selection(
        ns_view_handle: i64,
        utf8: *const u8,
        len: i32,
    );
    pub(crate) fn nucleus_tao_a11y_is_voiceover_running() -> i32;
    pub(crate) fn nucleus_tao_a11y_is_active() -> i32;
    pub(crate) fn nucleus_tao_a11y_consume_resync() -> i32;
    pub(crate) fn nucleus_tao_a11y_note_pushed();
    pub(crate) fn nucleus_tao_install_drag_monitor();
    pub(crate) fn nucleus_tao_start_window_drag(ns_window_ptr: i64);
    /// Headful e2e: beginSheetModalForWindow on [ns_window_ptr], cancel, report status.
    pub(crate) fn nucleus_tao_probe_sheet_parent(ns_window_ptr: i64, ns_view_ptr: i64) -> i32;
    /// Headful e2e: Japanese Kotoeri is installed (may be disabled in the menu).
    pub(crate) fn nucleus_tao_kotoeri_available() -> i32;
    /// Headful e2e: enable+select Kotoeri Hiragana, activate [ns_view] input context.
    pub(crate) fn nucleus_tao_kotoeri_select(ns_view_ptr: i64) -> i32;
    /// Headful e2e: restore the input source saved by [nucleus_tao_kotoeri_select].
    pub(crate) fn nucleus_tao_kotoeri_restore();
    /// Headful e2e: deliver a real `keyDown:` / `keyUp:` to TaoView.
    /// [autorepeat] stamps `kCGKeyboardEventAutorepeat` so AppKit sees a held
    /// key (press-and-hold engages on the first repeat).
    pub(crate) fn nucleus_tao_post_key_to_view(
        ns_view_ptr: i64,
        key_code: i32,
        chars: *const std::os::raw::c_char,
        down: i32,
        autorepeat: i32,
    ) -> i32;
    /// Headful e2e: current TIS keyboard source id into [buf]. Returns 1 on success.
    pub(crate) fn nucleus_tao_current_input_source_id(
        buf: *mut std::os::raw::c_char,
        len: i32,
    ) -> i32;
    /// Headful e2e: query TaoView NSTextInputClient. [out_ranges] is 5×i64
    /// (marked loc/len, selected loc/len, characterIndex; NSNotFound → -1).
    pub(crate) fn nucleus_tao_query_text_input_client(
        ns_view_ptr: i64,
        out_ranges: *mut i64,
        substring_buf: *mut std::os::raw::c_char,
        substring_buf_len: i32,
    ) -> i32;
    /// Headful e2e: `[view setMarkedText:selectedRange:replacementRange:]`.
    pub(crate) fn nucleus_tao_inject_marked_text(
        ns_view_ptr: i64,
        utf8: *const std::os::raw::c_char,
        selected_loc: i32,
        selected_len: i32,
    ) -> i32;
    /// Headful e2e: `[view insertText:replacementRange:]`. A negative
    /// [rr_loc] means `{NSNotFound, 0}` (ordinary typing).
    pub(crate) fn nucleus_tao_inject_insert_text(
        ns_view_ptr: i64,
        utf8: *const std::os::raw::c_char,
        rr_loc: i64,
        rr_len: i64,
    ) -> i32;
    pub(crate) fn nucleus_tao_register_trackpad_gesture_callback(
        cb: extern "C" fn(
            ns_window_ptr: i64,
            kind: i32,
            phase: i32,
            x_px: f64,
            y_px: f64,
            value: f64,
        ),
    );
    pub(crate) fn nucleus_tao_install_trackpad_gesture_monitor();
}

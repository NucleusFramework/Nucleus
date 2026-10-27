//! macOS and iOS dictation, bridged to `SFSpeechRecognizer` and `AVAudioEngine`.
//!
//! The Swift side in `swift/NativeSpeech.swift` does the real work, including
//! asking for both permissions; this module is just the C ABI between the two.

use std::ffi::{c_char, CStr, CString};
use crate::{codes, emit, error_kind_from_code, NativeSpeechEvent, NativeSpeechOptions, SpeechError};

extern "C" {
    fn robius_speech_start(id: u64, locale: *const c_char, prefer_on_device: bool);
    fn robius_speech_stop(id: u64, cancel: bool);
}

pub(super) const ENGINE: &str = "apple-sfspeech";

pub(super) fn is_supported() -> bool { true }

pub(super) fn start(id: u64, options: &NativeSpeechOptions) -> Result<(), SpeechError> {
    let locale = CString::new(options.locale.as_deref().unwrap_or_default()).unwrap();
    unsafe { robius_speech_start(id, locale.as_ptr(), options.prefer_on_device); }
    Ok(())
}

pub(super) fn stop(id: u64, cancel: bool) {
    unsafe { robius_speech_stop(id, cancel); }
}

#[no_mangle]
extern "C" fn robius_speech_event(id: u64, kind: i32, text: *const c_char, level: f32) {
    let text = || {
        if text.is_null() { String::new() }
        else { unsafe { CStr::from_ptr(text) }.to_string_lossy().into_owned() }
    };
    let event = match kind {
        codes::STARTED => NativeSpeechEvent::Started,
        codes::PARTIAL => NativeSpeechEvent::Transcript { text: text(), is_final: false },
        codes::FINAL => NativeSpeechEvent::Transcript { text: text(), is_final: true },
        codes::LEVEL => NativeSpeechEvent::AudioLevel(if level.is_finite() { level.clamp(0.0, 1.0) } else { 0.0 }),
        codes::STOPPED => NativeSpeechEvent::Stopped,
        _ => NativeSpeechEvent::Error(SpeechError::new(error_kind_from_code(kind), text())),
    };
    emit(id, event);
}

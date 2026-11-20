//! Windows dictation through SAPI and its native microphone input.
//!
//! We use SAPI rather than the WinRT `SpeechRecognizer` because SAPI works in a
//! plain unpackaged desktop app, whereas WinRT dictation needs package identity.
//! SAPI also uses entirely on-device recognizers that are already installed.
//!
//! Note that every COM interface here stays on one dedicated MTA thread,
//! including when it gets released.
//! See <https://learn.microsoft.com/windows/apps/develop/input/enable-continuous-dictation>

use crate::{emit, NativeSpeechEvent, NativeSpeechOptions, SpeechError, SpeechErrorKind};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock, mpsc};
use std::sync::atomic::{AtomicU8, Ordering};
use std::time::{Duration, Instant};
use windows::core::{Interface, IUnknown, PCWSTR, PWSTR, HRESULT};
use windows::Win32::Globalization::LocaleNameToLCID;
use windows::Win32::Media::Speech::*;
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize,
    CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED,
};

const RECORDING: u8 = 0;
const STOPPING: u8 = 1;
const CANCELLED: u8 = 2;

struct Control {
    state: AtomicU8,
    wake: mpsc::Sender<()>,
}

static CONTROLS: OnceLock<Mutex<HashMap<u64, Arc<Control>>>> = OnceLock::new();
// Cancellation can release the public session before its native worker has
// closed the microphone. Serialize native ownership across that short interval.
static MICROPHONE: Mutex<()> = Mutex::new(());

fn controls() -> &'static Mutex<HashMap<u64, Arc<Control>>> {
    CONTROLS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(super) fn is_supported() -> bool {
    static SUPPORTED: OnceLock<bool> = OnceLock::new();
    *SUPPORTED.get_or_init(|| {
        // Only query installed engine tokens: do not open the microphone or
        // load a recognition model while checking whether to show the control.
        // A separate apartment also avoids changing the UI thread's COM model.
        std::thread::Builder::new().name("speech-availability".into()).spawn(|| {
            let Ok(_apartment) = Apartment::new() else { return false };
            unsafe {
                let Ok(category): Result<ISpObjectTokenCategory, _> =
                    CoCreateInstance(&SpObjectTokenCategory, None, CLSCTX_INPROC_SERVER)
                else { return false };
                if category.SetId(SPCAT_RECOGNIZERS, false).is_err() { return false; }
                let Ok(tokens) = category.EnumTokens(PCWSTR::null(), PCWSTR::null()) else { return false };
                let mut count = 0;
                tokens.GetCount(&mut count).is_ok() && count > 0
            }
        }).ok().and_then(|thread| thread.join().ok()).unwrap_or(false)
    })
}

pub(super) fn start(id: u64, options: &NativeSpeechOptions) -> Result<(), SpeechError> {
    let (wake, receiver) = mpsc::channel();
    let control = Arc::new(Control { state: AtomicU8::new(RECORDING), wake });
    controls().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).insert(id, control.clone());
    let options = options.clone();
    let spawned = std::thread::Builder::new().name("native-speech".into()).spawn(move || {
        worker(id, || {
            if control.state.load(Ordering::Acquire) != RECORDING {
                Ok(())
            } else {
                run(id, &options, &control, &receiver)
            }
        });
    });
    if let Err(error) = spawned {
        controls().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).remove(&id);
        return Err(SpeechError::new(SpeechErrorKind::Other, format!("Could not start Windows speech recognition: {error}")));
    }
    Ok(())
}

fn worker(id: u64, run: impl FnOnce() -> Result<(), SpeechError>) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _microphone = MICROPHONE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        run()
    })).unwrap_or_else(|_| Err(SpeechError::new(SpeechErrorKind::Other, "Windows speech recognition stopped unexpectedly. Please try again.")));
    controls().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).remove(&id);
    // COM/audio resources unwind before this terminal callback releases the
    // public session, including when an unexpected worker panic occurs.
    emit(id, match result {
        Ok(()) => NativeSpeechEvent::Stopped,
        Err(error) => NativeSpeechEvent::Error(error),
    });
}

pub(super) fn stop(id: u64, cancel: bool) {
    if let Some(control) = controls().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).get(&id) {
        // A repeated graceful stop must never undo a cancellation.
        control.state.fetch_max(if cancel { CANCELLED } else { STOPPING }, Ordering::AcqRel);
        let _ = control.wake.send(());
    }
}

struct Apartment;

impl Apartment {
    fn new() -> Result<Self, SpeechError> {
        unsafe { CoInitializeEx(None, COINIT_MULTITHREADED).ok() }
            .map_err(|error| failure("Could not initialize Windows speech recognition", error))?;
        Ok(Self)
    }
}

impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe { CoUninitialize() };
    }
}

struct Recognition {
    recognizer: ISpRecognizer,
    context: ISpRecoContext,
    _grammar: ISpRecoGrammar,
}

impl Drop for Recognition {
    fn drop(&mut self) {
        // This is our in-process recognizer, so stopping it cannot affect
        // another application's dictation or Windows voice controls.
        let _ = unsafe { self.recognizer.SetRecoState(SPRST_INACTIVE_WITH_PURGE) };
    }
}

pub(super) const ENGINE: &str = "windows-sapi";

fn failure(context: &str, error: windows::core::Error) -> SpeechError {
    failure_kind(SpeechErrorKind::Other, context, error)
}

fn failure_kind(kind: SpeechErrorKind, context: &str, error: windows::core::Error) -> SpeechError {
    SpeechError::new(kind, format!("{context}. Check Windows microphone access and that a speech recognition language is installed. {error}"))
}

fn run(
    id: u64,
    options: &NativeSpeechOptions,
    control: &Control,
    receiver: &mpsc::Receiver<()>,
) -> Result<(), SpeechError> {
    // Declared first so COM is uninitialized after every interface is dropped.
    let _apartment = Apartment::new()?;
    let recognition = create_recognition(options)?;
    if control.state.load(Ordering::Acquire) != RECORDING {
        return Ok(());
    }
    unsafe { recognition._grammar.SetDictationState(SPRS_ACTIVE) }
        .map_err(|error| failure("Could not open the microphone for dictation", error))?;
    if control.state.load(Ordering::Acquire) == CANCELLED {
        return Ok(());
    }
    emit(id, NativeSpeechEvent::Started);
    let mut stop_deadline = None;
    let mut partial = String::new();
    loop {
        let state = control.state.load(Ordering::Acquire);
        if state == CANCELLED {
            return Ok(());
        }
        if state == STOPPING && stop_deadline.is_none() {
            // INACTIVE closes native capture and lets buffered audio finish.
            // PURGE is reserved for cancellation and the cleanup guard.
            unsafe { recognition.recognizer.SetRecoState(SPRST_INACTIVE) }
                .map_err(|error| failure("Could not finish speech recognition", error))?;
            stop_deadline = Some(Instant::now() + Duration::from_secs(5));
        }
        while let Some(event) = next_event(&recognition.context)? {
            if control.state.load(Ordering::Acquire) == CANCELLED {
                return Ok(());
            }
            if handle_event(id, &event, &mut partial, control, stop_deadline.is_some())? {
                return Ok(());
            }
        }
        if stop_deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            // Some engines never send END_SR_STREAM on a silent recording.
            // Preserve the visible hypothesis and always release the device.
            finish_partial(id, &mut partial, control);
            return Ok(());
        }
        // SAPI queues events on its own worker threads. Poll only while this
        // session exists; stop/cancel wakes this wait immediately.
        let _ = receiver.recv_timeout(Duration::from_millis(20));
    }
}

/// Returns true when SAPI has ended this stream.
fn handle_event(id: u64, event: &Event, partial: &mut String, control: &Control, stopping: bool) -> Result<bool, SpeechError> {
    match SPEVENTENUM(event.0._bitfield & 0xffff) {
        SPEI_HYPOTHESIS | SPEI_RECOGNITION => {
            let text = event.text()?;
            let is_final = event.0._bitfield & 0xffff == SPEI_RECOGNITION.0;
            if is_final {
                partial.clear();
            } else {
                partial.clone_from(&text);
            }
            emit(id, NativeSpeechEvent::Transcript { text, is_final });
        }
        SPEI_FALSE_RECOGNITION => {
            if control.state.load(Ordering::Acquire) == STOPPING {
                // Stopping mid-phrase can make an engine reject its buffered
                // tail. Keep the words already shown instead of erasing them.
                finish_partial(id, partial, control);
            } else {
                // Remove a rejected utterance while recording, without
                // committing speech the engine did not recognize.
                partial.clear();
                emit(id, NativeSpeechEvent::Transcript { text: String::new(), is_final: true });
            }
        }
        SPEI_SR_AUDIO_LEVEL => {
            emit(id, NativeSpeechEvent::AudioLevel((event.0.wParam.0 as f32 / 100.0).clamp(0.0, 1.0)));
        }
        SPEI_END_SR_STREAM => {
            // Finalize the displayed hypothesis even when the stream ended
            // with an error, before the terminal callback releases the session.
            finish_partial(id, partial, control);
            let status = HRESULT(event.0.lParam.0 as i32);
            if let Err(error) = status.ok() {
                return Err(failure_kind(SpeechErrorKind::Audio, "Windows speech recognition lost its audio input", error));
            }
            if !stopping {
                return Err(SpeechError::new(SpeechErrorKind::Audio, "Windows speech recognition stopped receiving microphone audio. Check the microphone and try again."));
            }
            return Ok(true);
        }
        _ => {}
    }
    Ok(false)
}

fn finish_partial(id: u64, partial: &mut String, control: &Control) {
    if !partial.is_empty() && control.state.load(Ordering::Acquire) != CANCELLED {
        emit(id, NativeSpeechEvent::Transcript { text: std::mem::take(partial), is_final: true });
    }
}

fn create_recognition(options: &NativeSpeechOptions) -> Result<Recognition, SpeechError> {
    unsafe {
        let recognizer: ISpRecognizer = CoCreateInstance(&SpInprocRecognizer, None, CLSCTX_INPROC_SERVER)
            .map_err(|error| failure_kind(SpeechErrorKind::Unavailable, "Windows speech recognition is unavailable", error))?;
        // SAPI's recognizers run on-device, also when prefer_on_device is false.
        if let Some(locale) = options.locale.as_deref() {
            let name: Vec<u16> = locale.encode_utf16().chain(Some(0)).collect();
            let language = LocaleNameToLCID(PCWSTR(name.as_ptr()), 0) & 0xffff;
            if language == 0 {
                return Err(SpeechError::new(SpeechErrorKind::Language, format!("Windows does not recognize the speech language {locale}.")));
            }
            let category: ISpObjectTokenCategory = CoCreateInstance(&SpObjectTokenCategory, None, CLSCTX_INPROC_SERVER)
                .map_err(|error| failure("Could not find Windows speech recognition languages", error))?;
            category.SetId(SPCAT_RECOGNIZERS, false)
                .map_err(|error| failure("Could not find Windows speech recognition languages", error))?;
            let attributes: Vec<u16> = format!("Language={language:x}").encode_utf16().chain(Some(0)).collect();
            let tokens = category.EnumTokens(PCWSTR(attributes.as_ptr()), PCWSTR::null())
                .map_err(|error| failure_kind(SpeechErrorKind::Language, "Could not find the requested speech recognition language", error))?;
            let token = tokens.Item(0)
                .map_err(|_| SpeechError::new(SpeechErrorKind::Language, format!("Install the Windows speech recognition language for {locale} before dictating in that language.")))?;
            recognizer.SetRecognizer(&token)
                .map_err(|error| failure_kind(SpeechErrorKind::Language, "Could not use the requested speech recognition language", error))?;
        }
        let input: ISpObjectToken = default_token(SPCAT_AUDIOIN)
            .map_err(|error| failure_kind(SpeechErrorKind::Audio, "No Windows microphone is available", error))?;
        recognizer.SetInput(&input, true)
            .map_err(|error| failure_kind(SpeechErrorKind::Audio, "Could not select the Windows microphone", error))?;
        let context = recognizer.CreateRecoContext()
            .map_err(|error| failure("Could not create Windows dictation", error))?;
        // SPFEI also includes these two reserved flags, as required by SAPI.
        let interest = [SPEI_HYPOTHESIS, SPEI_RECOGNITION, SPEI_FALSE_RECOGNITION,
            SPEI_SR_AUDIO_LEVEL, SPEI_END_SR_STREAM, SPEI_RESERVED1, SPEI_RESERVED2]
            .into_iter().fold(0, |mask, event| mask | (1u64 << event.0));
        context.SetInterest(interest, interest)
            .map_err(|error| failure("Could not subscribe to Windows dictation", error))?;
        let grammar = context.CreateGrammar(1)
            .map_err(|error| failure("Could not create the dictation grammar", error))?;
        grammar.LoadDictation(PCWSTR::null(), SPLO_STATIC)
            .map_err(|error| failure_kind(SpeechErrorKind::Language, "The installed Windows speech language does not support dictation", error))?;
        Ok(Recognition { recognizer, context, _grammar: grammar })
    }
}

unsafe fn default_token(category_id: PCWSTR) -> windows::core::Result<ISpObjectToken> {
    unsafe {
        let category: ISpObjectTokenCategory = CoCreateInstance(&SpObjectTokenCategory, None, CLSCTX_INPROC_SERVER)?;
        category.SetId(category_id, false)?;
        let id = ComText(category.GetDefaultTokenId()?);
        let token: ISpObjectToken = CoCreateInstance(&SpObjectToken, None, CLSCTX_INPROC_SERVER)?;
        token.SetId(PCWSTR::null(), PCWSTR(id.0.0), false)?;
        Ok(token)
    }
}

struct ComText(PWSTR);

impl Drop for ComText {
    fn drop(&mut self) {
        unsafe { CoTaskMemFree(Some(self.0.0.cast())) };
    }
}

struct Event(SPEVENT);

impl Event {
    fn text(&self) -> Result<String, SpeechError> {
        let raw = self.0.lParam.0 as *mut std::ffi::c_void;
        unsafe {
            let result = ISpRecoResult::from_raw_borrowed(&raw)
                .ok_or_else(|| SpeechError::new(SpeechErrorKind::Other, "Windows speech recognition returned an empty result."))?;
            let mut text = ComText(PWSTR::null());
            result.GetText(0, u32::MAX, true, &mut text.0, None)
                .map_err(|error| failure("Could not read Windows dictation text", error))?;
            if text.0.is_null() {
                return Ok(String::new());
            }
            text.0.to_string().map_err(|_| SpeechError::new(SpeechErrorKind::Other, "Windows speech recognition returned invalid text."))
        }
    }
}

impl Drop for Event {
    fn drop(&mut self) {
        let pointer = self.0.lParam.0 as *mut std::ffi::c_void;
        if pointer.is_null() {
            return;
        }
        // SpClearEvent's ownership rules, including failed/ignored events.
        match SPEVENTLPARAMTYPE((self.0._bitfield >> 16) & 0xffff) {
            SPET_LPARAM_IS_TOKEN | SPET_LPARAM_IS_OBJECT => unsafe {
                drop(IUnknown::from_raw(pointer));
            },
            SPET_LPARAM_IS_POINTER | SPET_LPARAM_IS_STRING => unsafe {
                CoTaskMemFree(Some(pointer));
            },
            _ => {}
        }
    }
}

fn next_event(context: &ISpRecoContext) -> Result<Option<Event>, SpeechError> {
    let mut event = Event(SPEVENT::default());
    let mut fetched = 0;
    unsafe { context.GetEvents(1, &mut event.0, &mut fetched) }
        .map_err(|error| failure("Could not receive Windows dictation", error))?;
    Ok((fetched != 0).then_some(event))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn recording() -> (u64, Arc<Mutex<Vec<NativeSpeechEvent>>>) {
        let id = crate::NEXT_SESSION.fetch_add(1, Ordering::Relaxed) + 10_000;
        let events = Arc::new(Mutex::new(Vec::new()));
        let output = events.clone();
        crate::sessions().lock().unwrap().insert(id, Arc::new(move |event| {
            output.lock().unwrap().push(event);
        }));
        (id, events)
    }

    #[test]
    fn a_panicking_worker_releases_the_session_and_does_not_block_its_successor() {
        let (id, events) = recording();
        let (wake, _receiver) = mpsc::channel();
        controls().lock().unwrap().insert(id, Arc::new(Control { state: AtomicU8::new(RECORDING), wake }));
        worker(id, || panic!("injected worker failure; no COM or microphone is used"));
        assert!(matches!(events.lock().unwrap().as_slice(), [NativeSpeechEvent::Error(_)]));
        assert!(!crate::sessions().lock().unwrap().contains_key(&id));
        assert!(!controls().lock().unwrap().contains_key(&id));

        let (next, next_events) = recording();
        worker(next, || Ok(()));
        assert_eq!(*next_events.lock().unwrap(), vec![NativeSpeechEvent::Stopped]);
    }

    #[test]
    fn rejecting_the_last_utterance_during_stop_preserves_it_exactly_once() {
        let (id, events) = recording();
        let (wake, _receiver) = mpsc::channel();
        let control = Control { state: AtomicU8::new(STOPPING), wake };
        let mut pending = "last spoken words".to_owned();
        let mut event = Event(SPEVENT::default());
        worker(id, || {
            event.0._bitfield = SPEI_FALSE_RECOGNITION.0;
            assert!(!handle_event(id, &event, &mut pending, &control, true)?);
            event.0._bitfield = SPEI_END_SR_STREAM.0;
            assert!(handle_event(id, &event, &mut pending, &control, true)?);
            Ok(())
        });
        assert_eq!(*events.lock().unwrap(), vec![
            NativeSpeechEvent::Transcript { text: "last spoken words".into(), is_final: true },
            NativeSpeechEvent::Stopped,
        ]);
    }

    #[test]
    fn a_failed_stream_finalizes_visible_words_before_reporting_the_error() {
        let (id, events) = recording();
        let (wake, _receiver) = mpsc::channel();
        let control = Control { state: AtomicU8::new(STOPPING), wake };
        let mut pending = "keep this interrupted utterance".to_owned();
        let mut event = Event(SPEVENT::default());
        event.0._bitfield = SPEI_END_SR_STREAM.0;
        event.0.lParam.0 = 0x80004005u32 as i32 as isize; // E_FAIL, not an owned pointer.
        worker(id, || handle_event(id, &event, &mut pending, &control, true).map(|_| ()));
        let events = events.lock().unwrap();
        assert!(matches!(events.as_slice(), [
            NativeSpeechEvent::Transcript { text, is_final: true }, NativeSpeechEvent::Error(_)
        ] if text == "keep this interrupted utterance"));
    }

    #[test]
    fn normal_rejection_clears_the_hypothesis_and_cancellation_does_not_commit_it() {
        let (id, events) = recording();
        let (wake, _receiver) = mpsc::channel();
        let control = Control { state: AtomicU8::new(RECORDING), wake };
        let mut pending = "unrecognized words".to_owned();
        let mut event = Event(SPEVENT::default());
        event.0._bitfield = SPEI_FALSE_RECOGNITION.0;
        assert!(!handle_event(id, &event, &mut pending, &control, false).unwrap());
        assert_eq!(*events.lock().unwrap(), vec![NativeSpeechEvent::Transcript { text: String::new(), is_final: true }]);
        assert!(pending.is_empty());
        control.state.store(CANCELLED, Ordering::Release);
        pending = "discard this queued result".to_owned();
        finish_partial(id, &mut pending, &control);
        assert_eq!(events.lock().unwrap().len(), 1);
        crate::sessions().lock().unwrap().remove(&id);
    }
}

# Robius speech

`robius-speech` provides native streaming speech-to-text input and microphone capture for Rust apps.

* This crate currently only allows access to speech recognition (speech-to-text) services, not text-to-speech.
* This crate doesn't offer integration with third-party transcription services or model downloads.

| Platform | Native service | Requirements |
| --- | --- | --- |
| macOS | ✅ SFSpeechRecognizer and AVAudioEngine | macOS 11+, microphone and speech permission |
| iOS | ✅ SFSpeechRecognizer and AVAudioEngine | iOS 13+, microphone and speech permission |
| Android | ✅ SpeechRecognizer | API 26+, installed recognition service, microphone permission |
| Windows | ✅ SAPI dictation and native audio input | Installed system speech language and microphone access |
| Linux, Web | ❌ Unavailable | `is_supported()` returns false |

* Apple and Android prefer on-device recognition when supported, but each platform may use
the system provider's network service when a local recognizer is unavailable.
* Windows SAPI uses entirely on-device recognizers that are already installed.
* Linux simply doesn't offer a built-in platform-native speech to text service, so there's nothing we can do.


## Usage

```rust,no_run
use robius_speech::{NativeSpeechEvent, NativeSpeechOptions, NativeSpeechSession, SpeechError};

fn start_dictation() -> Result<NativeSpeechSession, SpeechError> {
    NativeSpeechSession::start(NativeSpeechOptions::default(), |event| {
        match event {
            NativeSpeechEvent::Transcript { text, is_final } => {
                // Forward to your UI thread: replace the current utterance on
                // partial results and commit it when is_final is true.
                println!("{text} (final: {is_final})");
            }
            NativeSpeechEvent::Error(error) => eprintln!("{} ({:?})", error, error.kind()),
            _ => {}
        }
    })
}
```

The session (`NativeSpeechSession` object) must stay alive for as long as you are dictating;
dropping it releases the microphone and ends recognition.
The `start()` function returns immediately rather than blocking on the permission prompt,
and the `Started` event tells you when permission has been granted and the microphone is actually recording.

Each `AudioLevel` event includes an amplitude level that's normalized between 0 and 1,
which can be used to display a level meter or similar sound wave animation.
Callbacks arrive on platform-dependent native OS threads, so you'll want to forward them
to your main UI thread or event loop.

Within a session, partial transcripts replace the current "utterance",
but final transcripts commit the full utterance, with recognition continuing across
as many utterances as the user speaks. An "utterance" is just a chunk of words spoken by the user.

Calling `stop()` closes the microphone but still allows the final result to arrive,
whereas `cancel()` (or just dropping the session) discards anything that's still pending.
Either way, the session ends with exactly one terminal event: `Stopped` or `Error`.
Note that it's possible for an in-progress callback to continue executing after you cancel it.
This crate also provides `cancel_all()` for things like suspending/quitting the app.

Importantly, only one session can be active at a time.

Errors have a `SpeechErrorKind` so that you can handle the exact cause.
For ex, `PermissionDenied` usually means that you should inform the user that they need
to enable audio and/or speech permissions in system settings.
Similarly, `Unavailable` means that the audio input or speech recognition service
just doesn't exist and retrying it will never work.


## Putting the words into a text field

Transcripts are only half the job; a text field wants "replace these bytes with this text".
`Dictation` does that bookkeeping for you, and it knows nothing about any UI toolkit:

```rust
use robius_speech::Dictation;

// Speech replaces the current selection and carries on from there.
let mut dictation = Dictation::new(&field.text(), field.selection());

// For each `Transcript` event, forwarded to your UI thread:
if let Some(edit) = dictation.transcript(&text, is_final) {
    field.replace_range(edit.range, &edit.text);
    dictation.applied();
}
```

Partials revise the current utterance in place and finals commit it, with a space kept
between the dictated words and whatever the user typed (but not before punctuation,
or after an opening bracket, or between CJK characters).

Users also keep typing and clicking while they dictate, and that shouldn't lose or repeat
a word. When you see the user about to change the field (a keystroke, a click that moves
the caret), call `interrupt()` before their edit lands, and `settle()` with the field's new
text and selection once it has. Dictation then picks up again from wherever the caret is,
adding only the words that aren't on screen yet. If the platform IME is mid-composition,
treat that as an interruption too, and don't `settle()` until it has committed.

Each `Replacement` also says whether it `continues` an earlier one, so you can group a
whole run of revisions into a single undo step.


## Platform integration

**Apple.** Your application must declare `NSMicrophoneUsageDescription` and
`NSSpeechRecognitionUsageDescription` in its Info.plist.
This crate will request both permissions by itself, if needed.
Sandboxed or hardened macOS applications (which is typical for any distributed app bundle)
will also need the `com.apple.security.device.audio-input` entitlement.

Note that running a raw executable (e.g., `cargo run`) won't work with audio/speech,
you need an app bundle that has an embedded plist.
The apple backend in this crate will detect that case and report an error.

**Android.** Declare `android.permission.RECORD_AUDIO` and a `queries` intent for
`android.speech.RecognitionService` in your manifest. The runtime permission
request is handled for you, through a headless fragment whose result routes back
to the crate rather than to your activity's `onRequestPermissionsResult`, so no
permission plumbing of your own is needed. This works just like other robius crates.

## Building and validation

Apple builds always compile the Swift bridge, and a missing toolchain will fail the build.
You need to install the Xcode command line tools (`xcode-select --install`), which are
already required by Rust itself... so you probably have that already taken care of.
On iOS, you need a full Xcode installation just like every other crate/app.

For macOS builds, set the `MACOSX_DEPLOYMENT_TARGET` env var to 11.0 or newer.
This mostly just matters on Intel x86 macs, not Apple silicon (ARM aarch64),
but it doesn't hurt to always set it.

Android builds need an SDK platform jar, `d8`, and a JDK, which the build script
locates through `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), `ANDROID_PLATFORM`,
`ANDROID_BUILD_TOOLS_VERSION`, and `JAVA_HOME`. When those tools are absent the
build still succeeds, emitting a warning and compiling a backend that reports
itself as unavailable, which keeps plain `cargo check` and rust-analyzer working.
If you install them afterwards, run `cargo clean -p robius-speech` so that the
build script looks for them again.

For this we recommend using the [`android-build`](https://crates.io/crates/android-build) crate
like all Makepad + Robius apps do.

```sh
MACOSX_DEPLOYMENT_TARGET=11.0 cargo test -p robius-speech
MACOSX_DEPLOYMENT_TARGET=11.0 cargo clippy -p robius-speech --all-targets -- -D warnings
bash crates/speech/swift/tests/run.sh
python3 crates/speech/tests/android_retry_test.py
```

The Swift test harness requires macOS, and the Android harness requires a JDK and a C compiler
on either macOS or Linux.

# speech-recognition

Native streaming speech-to-text for Kotlin Multiplatform, inspired by (and on desktop built on)
[`robius-speech`](https://github.com/project-robius/robius/tree/main/crates/speech).

```kotlin
implementation("dev.nucleusframework:nucleus.speech-recognition:<version>")
```

| Target | Service | Implementation |
| --- | --- | --- |
| JVM · Windows | SAPI dictation, always on-device | Rust JNI bridge over `robius-speech` |
| JVM · macOS 11+ | SFSpeechRecognizer + AVAudioEngine | Rust JNI bridge over `robius-speech` (its Swift bridge) |
| JVM · Linux | none — `isSupported` is `false` | no native library |
| Android (API 26+) | `android.speech.SpeechRecognizer` | Kotlin |
| iOS (arm64, simulator arm64) | SFSpeechRecognizer + AVAudioEngine | Kotlin/Native |

Rust is only used on desktop; the mobile targets call the platform APIs directly.

## Usage

```kotlin
if (SpeechRecognition.isSupported) {
    val session = SpeechRecognition.start(SpeechRecognitionOptions(locale = "en-US")) { event ->
        when (event) {
            SpeechEvent.Started -> println("recording")
            is SpeechEvent.Transcript -> println("${event.text} (final: ${event.isFinal})")
            is SpeechEvent.AudioLevel -> meter.value = event.level
            SpeechEvent.Stopped -> println("done")
            is SpeechEvent.Error -> println("${event.message} (${event.kind})")
        }
    }
    // Later:
    session.stop()   // close the microphone, the last words still arrive, then Stopped
    session.cancel() // or discard everything; no event follows
}
```

- `start()` returns at once and asks for the permissions itself; `Started` means the microphone is
  recording, a refusal arrives as `Error(PermissionDenied)`.
- A session ends with exactly one terminal event, `Stopped` or `Error` — unless it is cancelled,
  after which nothing is delivered.
- Only one session runs at a time (`SpeechException(Busy)` otherwise). `SpeechRecognition.cancelAll()`
  is for suspending or quitting.
- The listener runs on a platform thread (main thread on Android/Apple, a SAPI worker thread on
  Windows): hop to the UI thread yourself.
- Partial transcripts replace the current utterance, a final one commits it, and recognition carries
  on across utterances until stopped. Speech stays plain text: saying "enter" types "enter".

### Putting the words into a text field

`Dictation` turns transcripts into edits, without knowing any UI toolkit. Offsets are UTF-16
indices, like `String` and Compose's `TextRange`:

```kotlin
val dictation = Dictation(field.text, field.selection.start, field.selection.end)

// For each Transcript, on the UI thread:
dictation.transcript(event.text, event.isFinal)?.let { edit ->
    field.replace(edit.start, edit.end, edit.text)
    dictation.applied()
}
```

When the user is about to edit the field (a keystroke, a click moving the caret), call
`interrupt()` first and `settle(text, selectionStart, selectionEnd)` once the edit has landed:
dictation resumes at the caret without losing or repeating a word. `Replacement.continues` tells a
revision of the previous edit apart, for grouping undo.

## Platform setup

**macOS.** Recognition needs an app bundle whose Info.plist declares both usage descriptions — a
bare `./gradlew run` reports `PermissionDenied` ("Launch the application from its .app bundle").
A hardened-runtime (notarized) app also needs the `com.apple.security.device.audio-input`
entitlement, which Nucleus' default entitlements do not grant:

```kotlin
nucleus.application {
    nativeDistributions {
        macOS {
            infoPlist {
                extraKeysRawXml = """
                    <key>NSMicrophoneUsageDescription</key>
                    <string>Dictation uses the microphone.</string>
                    <key>NSSpeechRecognitionUsageDescription</key>
                    <string>Dictation transcribes your speech.</string>
                """.trimIndent()
            }
            entitlementsFile.set(project.file("entitlements.plist")) // + device.audio-input
        }
    }
}
```

Events are delivered on the main dispatch queue, which the Tao event loop drains; a headless app
without a Cocoa run loop on the main thread receives none.

**Windows.** Needs an installed Windows speech recognition language and microphone access
(Settings › Privacy › Microphone, "desktop apps"). No package identity is required.

**Android.** The library manifest already declares `RECORD_AUDIO` and the `RecognitionService`
query, and a content provider tracks the foreground Activity. The runtime permission prompt is
handled for you (headless fragment); a session ends when its Activity pauses.

**iOS.** Declare `NSMicrophoneUsageDescription` and `NSSpeechRecognitionUsageDescription`; both
permissions are requested for you. Recognition prefers on-device models and restarts its task
before Apple's one-minute limit.

## Development

- Native sources: `src/main/native` (Rust crate; `windows/build.bat`, `macos/build.sh`), built by
  `./gradlew :speech-recognition:buildNativeWindows` / `buildNativeMacOs`. `robius-speech` is a git
  dependency pinned by commit; bump `rev` in `Cargo.toml` and `cargo update -p robius-speech`.
- `./gradlew :speech-recognition:jvmTest -Dnucleus.speech.live=true` opens the real microphone for
  a few seconds (Windows/macOS).
- iOS klibs cross-compile on any host; iOS tests only run on macOS.

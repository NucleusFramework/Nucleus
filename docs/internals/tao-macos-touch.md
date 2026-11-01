# Direct touch on macOS (#892)

macOS 27 delivers touchscreen input (the MacBook Pro with PROLED XDR Display, and Sidecar on an iPad running iPadOS 27) **only to gesture recognizers** ([TN3212](https://developer.apple.com/documentation/technotes/tn3212-adopting-gesture-recognizers-for-sidecar-touch-support)). A direct touch never reaches the responder chain, `sendEvent:`, or NSEvent monitors. When a view has no recognizer of its own, AppKit turns the touch into mouse and trackpad events instead: a tap becomes a click, a pan becomes a scroll, a long press becomes a right click, and a pinch becomes magnify plus rotate.

## What Nucleus does

- **One forwarder per tao content view** (`macos/direct_touch.m`, `NucleusTaoTouchForwarder`). It is a contact observer in the spirit of Blender's `BlenderContactObserver` ([fa494ce9a0](https://projects.blender.org/blender/blender/commit/fa494ce9a0ada672f351d404dc6a1ce17c2e13a0)). It follows every `NSTouchTypeDirect` contact and forwards each update through `EventCallback.onTouchInput`, the wire the Windows touchscreen already uses. Positions are physical px × 1024 with a top-left origin, ids start at `0x10000` (clear of the mouse and the trackpad rotation contacts), and force is `FORCE_UNKNOWN`.
  - The Compose host aggregates the contacts with `TaoTouchContacts`, which is shared with Windows, so apps get real `PointerType.Touch` pointers: multi-touch, finger drags, and Compose's own pinch and rotate.
  - Blender instead translates touches into its own mouse and trackpad events.
- **Attached by the host, not at window creation**: `TaoComposeSceneHost.attach()` calls `NativeTaoMacOsTouchBridge.nativeAttachDirectTouch`. A window nobody reads touches from keeps the emulation. `-Dnucleus.tao.directTouch=false` keeps the emulation everywhere.
- **No `NSIsTouchNative`**. AppKit only injects its mouse-emulation recognizers (`_NS3PKitMouseEmulator`) into a hierarchy that has no direct-touch recognizer, so the forwarder's presence is enough to turn the emulation off for the view, long press included. An unbundled `gradlew run` has no Info.plist anyway. Views the forwarder does not cover keep the emulation:
  - the NSPanel popups;
  - NativeView children: `shouldReceiveTouch:` declines a touch whose hit test lands in a subview.
- **Delegate**:
  - `shouldAttemptToRecognizeWithEvent:` returns `NO`, so mouse and trackpad stay in the responder stream exactly as before;
  - simultaneous recognition with every other recognizer;
  - it recognizes (`Began` / `Changed`) on the first contact, which claims the touches;
  - it always ends in `Ended`, `Failed` or `Cancelled`;
  - `reset` reports any contact still down as cancelled;
  - `locationInView:` is overridden, as the macOS 27 SDK requires.
- **Exclusivity**: the view sets `exclusiveGestureBehavior = NotExclusive` (by selector, so older SDKs still build). Without it, a finger on a second window is dropped while the first one is down. The two-window e2e fails when the call is removed.
- **No coexistence with mouse-only events**, the same rule as the trackpad rotation contacts. An event lists every active pointer, so a mouse-only one would read as the fingers' release, i.e. a tap:
  - a finger landing ends an open trackpad pan and a rotation;
  - a cursor move or exit while fingers are down is dropped;
  - a click or scroll first cancels the touch interaction (`cancelPointerInput()`, then the Release).
- **Cancel order**: for a cancelled contact the host calls `cancelPointerInput()` *before* sending the Release. The other order delivers an ordinary touch-up, which `clickable` takes as a tap. The Windows host had that bug too; it is fixed in the same change.
- **Title bar by finger**: `performWindowDragWithEvent:` ignores a touch event (it returns at once and the window doesn't move, on macOS 27.0). So `nucleus_tao_start_window_drag` first asks the forwarder: if a finger is down on that window, the window follows the finger that landed last, in screen coordinates (`setFrameOrigin:`).
- **Popups**: their outside-click monitors only see mouse events. The forwarder posts `NucleusTaoDirectTouchBegan` when a touch sequence starts, and `popup_panel.m` dismisses on it like on an outside `mouseDown`.

## E2E without the hardware

`NativeMetalBridge.nativeDiagInjectDirectTouch` (gated by `NUCLEUS_TAO_INPUT_INJECTION=1`) builds what a touch display sends:

- **The event**: a CGEvent of the gesture type (29) whose field 110 is `kSLSHIDEventTypeDirectTouch` (59). It carries an IOHIDEvent digitizer collection (`SLEventSetIOHIDEvent`, which copies it): a hand event with one finger child per contact, with `IsDisplayIntegrated` (`0xB0019`) set. `+[NSEvent eventWithCGEvent:]` decodes it into `NSEventTypeDirectTouch`, and `postEvent:` sends it through AppKit's real touch routing: hit testing, recognizer gathering, exclusivity, and the emulation decision.
- **The fake device**: AppKit drops contacts from an unknown touch device. The first call creates a `sidecar` `NSTouchDevice` (`_touchDeviceWithIOHIDDictionary:`) and makes `+_touchDeviceFromTrueDeviceID:` fall back to it.
- **Coordinates**: a contact's normalized position times the device surface (mm at 72 pt per inch) is its location in **window** base coordinates. A real display is screen-absolute, so a test that moves the window must take the move out of each frame (see the title-bar case).
- **Phases**: they follow the `TaoTouchEvent` wire. A finger's event mask carries Range | Touch on landing and lift, Position on a move, and Cancel (`1 << 7`) for a cancel, which AppKit delivers as `touchesCancelled`.

`MacOsDirectTouchHeadfulCases` (`-Dnucleus.tao.headful.filter="#892"`) covers:

- a tap: a Touch press at the finger, one click, no emulated mouse;
- a drag through `detectDragGestures`, with no scroll or pan;
- a pinch and a rotation through `detectTransformGestures`;
- a cancel, which releases without clicking;
- a title-bar drag, which moves the window;
- an outside touch, which dismisses a native popup;
- a wheel while a finger is down, which cancels without tapping;
- a finger on each of two windows at once.

Run with `-Dnucleus.tao.directTouch=false`, the battery instead runs the emulation case: a tap is a mouse click.

## Answers to the issue's open questions (from injection, macOS 27.0)

1. The emulated trackpad events: not reached. With the forwarder, there is no emulation at all.
2. Scroll phases of the emulated pan: not reached, for the same reason.
3. Under injection, the emulation turns a one-finger pan into a single `mouseDown`, with no drag, `mouseUp` or scroll, on a bare AppKit view too. Only hardware can tell this apart from an injection artefact. The native path makes the question moot for tao views.
4. The tao content view takes part in hit testing, and its forwarder gets the touches. A touch over a NativeView child is declined by the delegate and left to that view; the e2e does not exercise this yet.
5. A drag area dragged by finger moves the window (see above). The native title-bar strip (traffic lights) has not been exercised.
6. No SDK link requirement: the forwarder works in a JVM linked against an older SDK, and the gesture recognizer API dates from 10.12.2.

**Still for hardware:** the emulation's real behaviour (question 3), a finger leaving the view mid-drag (cf. `pressedMouseButtons`), window activation by a touch on a background window, and the native title-bar strip.

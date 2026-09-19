package dev.nucleusframework.window.tao

import dev.nucleusframework.window.TitleBarHitTestTest
import dev.nucleusframework.window.tao.TaoWindowResizableTest
import dev.nucleusframework.window.tao.TaoWindowScrollTest
import dev.nucleusframework.window.tao.a11y.TaoA11yProjectionTest
import dev.nucleusframework.window.tao.event.LinuxWheelDeltaTest
import dev.nucleusframework.window.tao.event.MacOsWheelDeltaTest
import dev.nucleusframework.window.tao.event.TaoKeyMappingTest
import dev.nucleusframework.window.tao.event.TaoKeyboardModifiersDecodeTest
import dev.nucleusframework.window.tao.event.TaoSyntheticMouseWheelEventTest
import dev.nucleusframework.window.tao.event.TaoWheelPinchZoomTest
import dev.nucleusframework.window.tao.event.Win32WheelDeltaTest
import dev.nucleusframework.window.tao.popup.MacPopupPictureCullTest
import dev.nucleusframework.window.tao.popup.StandaloneFramePumpTest
import dev.nucleusframework.window.tao.popup.StandalonePopupRenderReentryTest
import dev.nucleusframework.window.tao.scene.LcdTextTest
import dev.nucleusframework.window.tao.scene.TaoSceneAnimationTest
import dev.nucleusframework.window.tao.scene.TaoSceneContentSwapTest
import dev.nucleusframework.window.tao.scene.TaoSceneExceptionHandlerTest
import dev.nucleusframework.window.tao.scene.TaoSceneExceptionRouterTest
import dev.nucleusframework.window.tao.scene.TaoSceneImeTest
import dev.nucleusframework.window.tao.scene.TaoSceneKeyboardTest
import dev.nucleusframework.window.tao.scene.TaoSceneOuterLocalsBridgeTest
import dev.nucleusframework.window.tao.scene.TaoScenePointerSlopTest
import dev.nucleusframework.window.tao.scene.TaoScenePointerTest
import dev.nucleusframework.window.tao.scene.TaoScenePopupTest
import dev.nucleusframework.window.tao.scene.TaoSceneRenderTest
import dev.nucleusframework.window.tao.scene.TaoSceneScrollTest
import dev.nucleusframework.window.tao.scene.TaoSceneSemanticsTest
import dev.nucleusframework.window.tao.scene.TaoSceneTrackpadPanTest
import dev.nucleusframework.window.tao.scene.TaoTrackpadPanRouterTest
import dev.nucleusframework.window.tao.workspace.DragControllerTest
import dev.nucleusframework.window.tao.workspace.HostGeometryTest
import dev.nucleusframework.window.tao.workspace.RelocatingSaveableStateRegistryTest
import dev.nucleusframework.window.tao.workspace.TransferDragTest
import dev.nucleusframework.window.tao.workspace.WindowGroupTest

/**
 * Programmatic, reflection-free registry of the stage-1 offscreen battery so
 * it can run inside a GraalVM native image (JUnit discovery needs reflection
 * metadata; direct calls need none). Kept in sync with the @Test methods by
 * [TaoSceneTestBatteryDriftTest], which fails the ordinary JUnit run when an
 * entry is missing, stale, or a new test class is neither registered here
 * nor declared JVM-only.
 */
@Suppress("LargeClass") // flat generated registry
public object TaoSceneTestBattery {
    public class CaseResult(
        public val name: String,
        public val failure: Throwable?,
    )

    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod") // flat generated registry
    public fun runAll(): List<CaseResult> {
        val results = mutableListOf<CaseResult>()

        fun run(
            name: String,
            body: () -> Unit,
        ) {
            val failure =
                try {
                    body()
                    null
                } catch (t: Throwable) {
                    t
                }
            results += CaseResult(name, failure)
        }

        run("TaoKeyMappingTest: mac layout-aware path maps produced characters over physical position") {
            TaoKeyMappingTest().`mac layout-aware path maps produced characters over physical position`()
        }
        run(
            "TaoKeyMappingTest: mac editing and whitespace keys",
        ) { TaoKeyMappingTest().`mac editing and whitespace keys`() }
        run("TaoKeyMappingTest: mac modifier keys carry left-right location") {
            TaoKeyMappingTest().`mac modifier keys carry left-right location`()
        }
        run("TaoKeyMappingTest: mac navigation and arrows") { TaoKeyMappingTest().`mac navigation and arrows`() }
        run("TaoKeyMappingTest: mac function keys F1 to F12") { TaoKeyMappingTest().`mac function keys F1 to F12`() }
        run("TaoKeyMappingTest: mac keypad keys carry numpad location and ignore the digit fast path") {
            TaoKeyMappingTest().`mac keypad keys carry numpad location and ignore the digit fast path`()
        }
        run("TaoKeyMappingTest: mac ctrl combos fall back to the physical letter table") {
            TaoKeyMappingTest().`mac ctrl combos fall back to the physical letter table`()
        }
        run(
            "TaoKeyMappingTest: mac unknown code maps to zero",
        ) { TaoKeyMappingTest().`mac unknown code maps to zero`() }
        run("TaoKeyMappingTest: linux latin keysyms map one-to-one") {
            TaoKeyMappingTest().`linux latin keysyms map one-to-one`()
        }
        run("TaoKeyMappingTest: linux layout-aware path wins over the keysym") {
            TaoKeyMappingTest().`linux layout-aware path wins over the keysym`()
        }
        run("TaoKeyMappingTest: linux editing and whitespace keysyms") {
            TaoKeyMappingTest().`linux editing and whitespace keysyms`()
        }
        run("TaoKeyMappingTest: linux modifiers carry left-right location and AltGr maps to right alt") {
            TaoKeyMappingTest().`linux modifiers carry left-right location and AltGr maps to right alt`()
        }
        run("TaoKeyMappingTest: linux ctrl combos fall back to the latin keysym") {
            TaoKeyMappingTest().`linux ctrl combos fall back to the latin keysym`()
        }
        run("TaoKeyMappingTest: linux function keys F1 to F12") {
            TaoKeyMappingTest().`linux function keys F1 to F12`()
        }
        run("TaoKeyMappingTest: linux keypad keys carry numpad location") {
            TaoKeyMappingTest().`linux keypad keys carry numpad location`()
        }
        run("TaoKeyMappingTest: linux navigation space caps lock and punctuation") {
            TaoKeyMappingTest().`linux navigation space caps lock and punctuation`()
        }
        run("TaoKeyboardModifiersDecodeTest: all sixteen combinations decode exactly") {
            TaoKeyboardModifiersDecodeTest().`all sixteen combinations decode exactly`()
        }
        run("TaoKeyboardModifiersDecodeTest: unknown high bits are ignored") {
            TaoKeyboardModifiersDecodeTest().`unknown high bits are ignored`()
        }
        run("TaoSyntheticMouseWheelEventTest: syntheticEventCarriesAwtScrollMetadata") {
            TaoSyntheticMouseWheelEventTest().syntheticEventCarriesAwtScrollMetadata()
        }
        run("Win32WheelDeltaTest: wheelTowardsUserMatchesTaoWindowAwtSign") {
            Win32WheelDeltaTest().wheelTowardsUserMatchesTaoWindowAwtSign()
        }
        run("Win32WheelDeltaTest: horizontalWheelIsNegatedToAwtSign") {
            Win32WheelDeltaTest().horizontalWheelIsNegatedToAwtSign()
        }
        run("Win32WheelDeltaTest: popupWndProcNotchCarriesWindowScrollAmount") {
            Win32WheelDeltaTest().popupWndProcNotchCarriesWindowScrollAmount()
        }
        run("LinuxWheelDeltaTest: popupButton5CarriesThreeLinesPerNotch") {
            LinuxWheelDeltaTest().popupButton5CarriesThreeLinesPerNotch()
        }
        run("MacOsWheelDeltaTest: scrollUpMatchesTaoWindowAwtSign") {
            MacOsWheelDeltaTest().scrollUpMatchesTaoWindowAwtSign()
        }
        run("MacOsWheelDeltaTest: horizontalDeltaFlipsLikeVertical") {
            MacOsWheelDeltaTest().horizontalDeltaFlipsLikeVertical()
        }
        run("MacOsWheelDeltaTest: precisePixelDeltaIgnoresDisplayScale") {
            MacOsWheelDeltaTest().precisePixelDeltaIgnoresDisplayScale()
        }
        run("MacOsWheelDeltaTest: precisePixelHorizontalFlipsAndDividesByTen") {
            MacOsWheelDeltaTest().precisePixelHorizontalFlipsAndDividesByTen()
        }
        run("MacOsWheelDeltaTest: lineDeltaCarriesMacOsScrollAmount") {
            MacOsWheelDeltaTest().lineDeltaCarriesMacOsScrollAmount()
        }
        run("MacOsWheelDeltaTest: preciseDeltaCarriesMacOsScrollAmount") {
            MacOsWheelDeltaTest().preciseDeltaCarriesMacOsScrollAmount()
        }
        run("MacOsWheelDeltaTest: gesturePhaseRidesAlongWhateverThePrecisionFlag") {
            MacOsWheelDeltaTest().gesturePhaseRidesAlongWhateverThePrecisionFlag()
        }
        run("StandaloneFramePumpTest: scheduleOnMainRunsInline") {
            StandaloneFramePumpTest().scheduleOnMainRunsInline()
        }
        run("StandaloneFramePumpTest: nestedScheduleFromRenderDoesNotReenter") {
            StandaloneFramePumpTest().nestedScheduleFromRenderDoesNotReenter()
        }
        run("StandaloneFramePumpTest: extraSchedulesWhileRenderingCoalesce") {
            StandaloneFramePumpTest().extraSchedulesWhileRenderingCoalesce()
        }
        run("StandaloneFramePumpTest: scheduleInsideNonReentrantBlockIsPosted") {
            StandaloneFramePumpTest().scheduleInsideNonReentrantBlockIsPosted()
        }
        run("StandaloneFramePumpTest: inlineRenderResumesAfterNonReentrantBlock") {
            StandaloneFramePumpTest().inlineRenderResumesAfterNonReentrantBlock()
        }
        run("StandaloneFramePumpTest: scheduleAfterDisposeIsNoOp") {
            StandaloneFramePumpTest().scheduleAfterDisposeIsNoOp()
        }
        run("StandaloneFramePumpTest: disposedPostedFrameIsDropped") {
            StandaloneFramePumpTest().disposedPostedFrameIsDropped()
        }
        run(
            "StandalonePopupRenderReentryTest: guarded scrollbar drag posts the frame " +
                "instead of re-entering the render pass",
        ) {
            StandalonePopupRenderReentryTest()
                .`guarded scrollbar drag posts the frame instead of re-entering the render pass`()
        }
        run(
            "StandalonePopupRenderReentryTest: unguarded scene dispatch re-enters the render pass " +
                "- the failure mode the guard exists for",
        ) {
            StandalonePopupRenderReentryTest()
                .`unguarded scene dispatch re-enters the render pass - the failure mode the guard exists for`()
        }
        run("TaoWheelPinchZoomTest: fullWheelDeltaProducesModerateZoomStep") {
            TaoWheelPinchZoomTest().fullWheelDeltaProducesModerateZoomStep()
        }
        run("TaoWheelPinchZoomTest: fractionalDeltasAccumulateLikeOneFullDelta") {
            TaoWheelPinchZoomTest().fractionalDeltasAccumulateLikeOneFullDelta()
        }
        run("TaoWheelPinchZoomTest: zoomOutIsInverseOfZoomIn") { TaoWheelPinchZoomTest().zoomOutIsInverseOfZoomIn() }
        run("TaoWindowScrollTest: lineScrollKeepsWheelRotationSeparateFromScrollAmount") {
            TaoWindowScrollTest().lineScrollKeepsWheelRotationSeparateFromScrollAmount()
        }
        run("TaoWindowScrollTest: pixelScrollMirrorsMacOsAwtPreciseWheelRotationScale") {
            TaoWindowScrollTest().pixelScrollMirrorsMacOsAwtPreciseWheelRotationScale()
        }
        run("TaoWindowScrollTest: scrollGestureIsShapedLikePixelScrollWithItsPhase") {
            TaoWindowScrollTest().scrollGestureIsShapedLikePixelScrollWithItsPhase()
        }
        run("TaoWindowScrollTest: unknownGestureWireCodeDegradesToPlainPreciseScroll") {
            TaoWindowScrollTest().unknownGestureWireCodeDegradesToPlainPreciseScroll()
        }
        run("TaoTrackpadPanRouterTest: swipe without momentum ends after the grace period") {
            TaoTrackpadPanRouterTest().`swipe without momentum ends after the grace period`()
        }
        run("TaoTrackpadPanRouterTest: terminal steps carrying a delta still pan when no gesture is open") {
            TaoTrackpadPanRouterTest().`terminal steps carrying a delta still pan when no gesture is open`()
        }
        run("TaoTrackpadPanRouterTest: momentum tail continues the pan and ends it once") {
            TaoTrackpadPanRouterTest().`momentum tail continues the pan and ends it once`()
        }
        run("TaoTrackpadPanRouterTest: a momentum tail arriving after the pan closed is handed back unhandled") {
            TaoTrackpadPanRouterTest().`a momentum tail arriving after the pan closed is handed back unhandled`()
        }
        run("TaoTrackpadPanRouterTest: fingers resting on the glass during the tail close the pan at once") {
            TaoTrackpadPanRouterTest().`fingers resting on the glass during the tail close the pan at once`()
        }
        run("TaoTrackpadPanRouterTest: a truncated stream is closed by the stall watchdog") {
            TaoTrackpadPanRouterTest().`a truncated stream is closed by the stall watchdog`()
        }
        run("TaoTrackpadPanRouterTest: finger steps move the deadline without re-scheduling the timer") {
            TaoTrackpadPanRouterTest().`finger steps move the deadline without re-scheduling the timer`()
        }
        run("TaoTrackpadPanRouterTest: finishNow closes an open pan and is a no-op otherwise") {
            TaoTrackpadPanRouterTest().`finishNow closes an open pan and is a no-op otherwise`()
        }
        run("TaoTrackpadPanRouterTest: pan offsets pass through unchanged and zero deltas send no move") {
            TaoTrackpadPanRouterTest().`pan offsets pass through unchanged and zero deltas send no move`()
        }
        run("TaoTrackpadPanRouterTest: cancelled closes immediately and may-begin alone is silent") {
            TaoTrackpadPanRouterTest().`cancelled closes immediately and may-begin alone is silent`()
        }
        run("TaoTrackpadPanRouterTest: a new swipe during the grace period keeps the same pan open") {
            TaoTrackpadPanRouterTest().`a new swipe during the grace period keeps the same pan open`()
        }
        run("TaoTrackpadPanRouterTest: cancel drops the pending end without sending PanEnd") {
            TaoTrackpadPanRouterTest().`cancel drops the pending end without sending PanEnd`()
        }
        run("TaoWindowResizableTest: reflectsCreationFlag") { TaoWindowResizableTest().reflectsCreationFlag() }
        run("WindowWrapContentTest: creationSizeUsesSpecifiedAxis") {
            WindowWrapContentTest().creationSizeUsesSpecifiedAxis()
        }
        run("WindowWrapContentTest: wrapHeightKeepsRequestedWidth") {
            WindowWrapContentTest().wrapHeightKeepsRequestedWidth()
        }
        run("WindowWrapContentTest: wrapBothUsesMeasuredPixels") {
            WindowWrapContentTest().wrapBothUsesMeasuredPixels()
        }
        run("WindowWrapContentTest: wrapWaitsForPositiveMeasuredAxis") {
            WindowWrapContentTest().wrapWaitsForPositiveMeasuredAxis()
        }
        run("WindowWrapContentTest: wrapHonoursMinimumSizeFloor") {
            WindowWrapContentTest().wrapHonoursMinimumSizeFloor()
        }
        run("TaoSceneRenderTest: solid background fills the whole frame") {
            TaoSceneRenderTest().`solid background fills the whole frame`()
        }
        run("TaoSceneRenderTest: box is drawn at its layout position") {
            TaoSceneRenderTest().`box is drawn at its layout position`()
        }
        run("TaoSceneRenderTest: density scales layout to physical pixels") {
            TaoSceneRenderTest().`density scales layout to physical pixels`()
        }
        run("TaoSceneRenderTest: state change recomposes and repaints on the next frame") {
            TaoSceneRenderTest().`state change recomposes and repaints on the next frame`()
        }
        run("TaoSceneRenderTest: hover enter and exit drive pointer-event state") {
            TaoSceneRenderTest().`hover enter and exit drive pointer-event state`()
        }
        run("TaoSceneKeyboardTest: typing inserts text into a focused BasicTextField") {
            TaoSceneKeyboardTest().`typing inserts text into a focused BasicTextField`()
        }
        run("TaoSceneKeyboardTest: backspace removes the last character through the named-key table") {
            TaoSceneKeyboardTest().`backspace removes the last character through the named-key table`()
        }
        run("TaoSceneKeyboardTest: backspace then typed accent replaces the last character") {
            TaoSceneKeyboardTest().`backspace then typed accent replaces the last character`()
        }
        run("TaoSceneKeyboardTest: typed text lands in the semantics tree") {
            TaoSceneKeyboardTest().`typed text lands in the semantics tree`()
        }
        run("TaoSceneKeyboardTest: control combos are not inserted as text") {
            TaoSceneKeyboardTest().`control combos are not inserted as text`()
        }
        run("TaoSceneKeyboardTest: mac function-key code points are filtered from text insertion") {
            TaoSceneKeyboardTest().`mac function-key code points are filtered from text insertion`()
        }
        run("TaoSceneKeyboardTest: preview key handler consumes the event before the scene") {
            TaoSceneKeyboardTest().`preview key handler consumes the event before the scene`()
        }
        run("TaoSceneKeyboardTest: fallback key handler fires only when the scene does not consume") {
            TaoSceneKeyboardTest().`fallback key handler fires only when the scene does not consume`()
        }
        run("TaoSceneImeTest: IME preedit is shown in the field while composing") {
            TaoSceneImeTest().`IME preedit is shown in the field while composing`()
        }
        run("TaoSceneImeTest: IME preedit is an active composition, not committed text") {
            TaoSceneImeTest().`IME preedit is an active composition, not committed text`()
        }
        run("TaoSceneImeTest: IME commit replaces the preedit without inserting a newline") {
            TaoSceneImeTest().`IME commit replaces the preedit without inserting a newline`()
        }
        run("TaoSceneImeTest: shortening the preedit does not delete committed text") {
            TaoSceneImeTest().`shortening the preedit does not delete committed text`()
        }
        run("TaoSceneImeTest: committed text replaces the preedit") {
            TaoSceneImeTest().`committed text replaces the preedit`()
        }
        run("TaoSceneImeTest: cancelled composition removes the preedit") {
            TaoSceneImeTest().`cancelled composition removes the preedit`()
        }
        run("TaoSceneImeTest: empty IME commit while composing does not wipe the preedit") {
            TaoSceneImeTest().`empty IME commit while composing does not wipe the preedit`()
        }
        run("TaoSceneImeTest: typing after a commit works normally") {
            TaoSceneImeTest().`typing after a commit works normally`()
        }
        run("TaoSceneImeTest: replacement commit replaces the range the picker names") {
            TaoSceneImeTest().`replacement commit replaces the range the picker names`()
        }
        run("TaoSceneImeTest: replacement commit leaves surrounding text intact and typing continues") {
            TaoSceneImeTest().`replacement commit leaves surrounding text intact and typing continues`()
        }
        run("TaoSceneImeTest: replacement commit with an out-of-bounds range is clamped") {
            TaoSceneImeTest().`replacement commit with an out-of-bounds range is clamped`()
        }
        run("TaoScenePointerTest: click on a clickable box fires exactly once") {
            TaoScenePointerTest().`click on a clickable box fires exactly once`()
        }
        run("TaoScenePointerTest: click outside a clickable does nothing") {
            TaoScenePointerTest().`click outside a clickable does nothing`()
        }
        run("TaoScenePointerTest: host guard - button event before any cursor move is dropped") {
            TaoScenePointerTest().`host guard - button event before any cursor move is dropped`()
        }
        run("TaoScenePointerTest: host guard - stray release without press is dropped") {
            TaoScenePointerTest().`host guard - stray release without press is dropped`()
        }
        run("TaoScenePointerTest: host guard - double press closes the stale interaction first") {
            TaoScenePointerTest().`host guard - double press closes the stale interaction first`()
        }
        run("TaoScenePointerTest: right button reaches compose as secondary") {
            TaoScenePointerTest().`right button reaches compose as secondary`()
        }
        run("TaoScenePointerTest: press move release drives a drag gesture") {
            TaoScenePointerTest().`press move release drives a drag gesture`()
        }
        run("TaoScenePointerTest: hover exit resets hover state via exitPointer") {
            TaoScenePointerTest().`hover exit resets hover state via exitPointer`()
        }
        run("TaoScenePointerSlopTest: sub-pixel jitter between press and release must not eat the click") {
            TaoScenePointerSlopTest().`sub-pixel jitter between press and release must not eat the click`()
        }
        run("TaoScenePointerSlopTest: sub-pixel jitter must not eat the click on a HiDPI display") {
            TaoScenePointerSlopTest().`sub-pixel jitter must not eat the click on a HiDPI display`()
        }
        run("TaoScenePointerSlopTest: real motion past the deadband still drags and cancels the click") {
            TaoScenePointerSlopTest().`real motion past the deadband still drags and cancels the click`()
        }
        run("TaoScenePointerSlopTest: moves below one dp are suppressed and real motion keeps sub-pixel precision") {
            TaoScenePointerSlopTest().`moves below one dp are suppressed and real motion keeps sub-pixel precision`()
        }
        run("TaoScenePointerSlopTest: press after suppressed jitter dispatches at the last dispatched position") {
            TaoScenePointerSlopTest().`press after suppressed jitter dispatches at the last dispatched position`()
        }
        run("TaoScenePointerSlopTest: touch slop is density-scaled like the AWT backend") {
            TaoScenePointerSlopTest().`touch slop is density-scaled like the AWT backend`()
        }
        run("TitleBarHitTestTest: opaque overlay bar does not leak clicks to the content below") {
            TitleBarHitTestTest().`opaque overlay bar does not leak clicks to the content below`()
        }
        run("TitleBarHitTestTest: pass-through overlay bar keeps content in the bar band interactive") {
            TitleBarHitTestTest().`pass-through overlay bar keeps content in the bar band interactive`()
        }
        run("TitleBarHitTestTest: content consuming the press vetoes the window drag") {
            TitleBarHitTestTest().`content consuming the press vetoes the window drag`()
        }
        run("TitleBarHitTestTest: an unclaimed press on the bar still drags the window") {
            TitleBarHitTestTest().`an unclaimed press on the bar still drags the window`()
        }
        run("TitleBarHitTestTest: a consumer that stops consuming mid-gesture never hands over the drag") {
            TitleBarHitTestTest().`a consumer that stops consuming mid-gesture never hands over the drag`()
        }
        run("TitleBarHitTestTest: a drag gesture under a pass-through bar is not stolen by the window move") {
            TitleBarHitTestTest().`a drag gesture under a pass-through bar is not stolen by the window move`()
        }
        run("TitleBarHitTestTest: a drag area does not leak clicks to an overlapping sibling") {
            TitleBarHitTestTest().`a drag area does not leak clicks to an overlapping sibling`()
        }
        run("TaoSceneScrollTest: wheel down scrolls a vertical column") {
            TaoSceneScrollTest().`wheel down scrolls a vertical column`()
        }
        run("TaoSceneScrollTest: wheel up at top is a no-op") { TaoSceneScrollTest().`wheel up at top is a no-op`() }
        run(
            "TaoSceneScrollTest: scroll direction is symmetric",
        ) { TaoSceneScrollTest().`scroll direction is symmetric`() }
        run("TaoSceneScrollTest: larger scrollAmount scrolls further per notch") {
            TaoSceneScrollTest().`larger scrollAmount scrolls further per notch`()
        }
        run("TaoSceneScrollTest: scrolled content repaints at the new offset") {
            TaoSceneScrollTest().`scrolled content repaints at the new offset`()
        }
        run("TaoSceneTrackpadPanTest: positive vertical pan scrolls a column down") {
            TaoSceneTrackpadPanTest().`positive vertical pan scrolls a column down`()
        }
        run("TaoSceneTrackpadPanTest: positive horizontal pan scrolls a row forward") {
            TaoSceneTrackpadPanTest().`positive horizontal pan scrolls a row forward`()
        }
        run("TaoSceneTrackpadPanTest: negative pan at the origin is a no-op") {
            TaoSceneTrackpadPanTest().`negative pan at the origin is a no-op`()
        }
        run("TaoSceneTrackpadPanTest: pan moves content by its pixel offset") {
            TaoSceneTrackpadPanTest().`pan moves content by its pixel offset`()
        }
        run("TaoSceneTrackpadPanTest: routed gesture steps pan a column and close after the grace") {
            TaoSceneTrackpadPanTest().`routed gesture steps pan a column and close after the grace`()
        }
        run("TaoSceneTrackpadPanTest: with pan events disabled gesture steps scroll as wheel events") {
            TaoSceneTrackpadPanTest().`with pan events disabled gesture steps scroll as wheel events`()
        }
        run("TaoSceneTrackpadPanTest: an orphaned momentum tail scrolls as wheel events instead of stalling") {
            TaoSceneTrackpadPanTest().`an orphaned momentum tail scrolls as wheel events instead of stalling`()
        }
        run("TaoSceneScrollTest: one wheel unit scrolls ten dp on macOS") {
            TaoSceneScrollTest().`one wheel unit scrolls ten dp on macOS`()
        }
        run("NativePopupLayersTest: a Popup inside NativePopupLayers is built by the window's native layer factory") {
            NativePopupLayersTest().`a Popup inside NativePopupLayers is built by the window's native layer factory`()
        }
        run("NativePopupLayersTest: a Popup outside NativePopupLayers keeps drawing in the scene") {
            NativePopupLayersTest().`a Popup outside NativePopupLayers keeps drawing in the scene`()
        }
        run("NativePopupLayersTest: without a native layer factory NativePopupLayers is a no-op") {
            NativePopupLayersTest().`without a native layer factory NativePopupLayers is a no-op`()
        }
        run("NativePopupLayersTest: closing the Popup closes the native layer") {
            NativePopupLayersTest().`closing the Popup closes the native layer`()
        }
        run("MacPopupPictureCullTest: a dimmed popup keeps its content") {
            MacPopupPictureCullTest().`a dimmed popup keeps its content`()
        }
        run("MacPopupPictureCullTest: an origin-rooted cull rect drops a dimmed popup's whole frame") {
            MacPopupPictureCullTest().`an origin-rooted cull rect drops a dimmed popup's whole frame`()
        }
        run("MacPopupPictureCullTest: a dimmed popup records more than one op") {
            MacPopupPictureCullTest().`a dimmed popup records more than one op`()
        }
        run("MacPopupPictureCullTest: an undimmed popup keeps its content") {
            MacPopupPictureCullTest().`an undimmed popup keeps its content`()
        }
        run("MacPopupPictureCullTest: a bare Compose scene records as one op and is unrolled") {
            MacPopupPictureCullTest().`a bare Compose scene records as one op and is unrolled`()
        }
        run("TaoScenePopupTest: popup renders above the window content") {
            TaoScenePopupTest().`popup renders above the window content`()
        }
        run("TaoScenePopupTest: popup disappears when its state is cleared") {
            TaoScenePopupTest().`popup disappears when its state is cleared`()
        }
        run("TaoScenePopupTest: outside click dismisses a focusable popup") {
            TaoScenePopupTest().`outside click dismisses a focusable popup`()
        }
        run("TaoScenePopupTest: two stacked popups keep independent pixels") {
            TaoScenePopupTest().`two stacked popups keep independent pixels`()
        }
        run("TaoScenePopupTest: click inside a focusable popup does not dismiss it") {
            TaoScenePopupTest().`click inside a focusable popup does not dismiss it`()
        }
        run("TaoScenePopupTest: buffer scale alignment rounds up and never collapses to zero") {
            TaoScenePopupTest().`buffer scale alignment rounds up and never collapses to zero`()
        }
        run(
            "TaoSceneOuterLocalsBridgeTest: wrapping window content in outer locals with " +
                "CompositionLocalProvider breaks Popup",
        ) {
            TaoSceneOuterLocalsBridgeTest()
                .`wrapping window content in outer locals with CompositionLocalProvider breaks Popup`()
        }
        run(
            "TaoSceneOuterLocalsBridgeTest: bridging outer locals through the scene's own compositionLocalContext " +
                "property does not break Popup",
        ) {
            TaoSceneOuterLocalsBridgeTest()
                .`bridging outer locals through the scene's own compositionLocalContext property does not break Popup`()
        }
        run(
            "TaoSceneOuterLocalsBridgeTest: bridged outer locals do not carry the outer layout direction into content",
        ) {
            TaoSceneOuterLocalsBridgeTest()
                .`bridged outer locals do not carry the outer layout direction into content`()
        }
        run("TaoSceneAnimationTest: tween advances exactly with virtual frames") {
            TaoSceneAnimationTest().`tween advances exactly with virtual frames`()
        }
        run("TaoSceneAnimationTest: same frame sequence produces the same pixels twice") {
            TaoSceneAnimationTest().`same frame sequence produces the same pixels twice`()
        }
        run("TaoSceneAnimationTest: frameUntilIdle settles a finite animation") {
            TaoSceneAnimationTest().`frameUntilIdle settles a finite animation`()
        }
        run("TaoSceneSemanticsTest: semantics owner is exposed through the platform context hook") {
            TaoSceneSemanticsTest().`semantics owner is exposed through the platform context hook`()
        }
        run("TaoSceneSemanticsTest: text nodes are discoverable by text") {
            TaoSceneSemanticsTest().`text nodes are discoverable by text`()
        }
        run("TaoSceneSemanticsTest: test tags are discoverable and carry bounds") {
            TaoSceneSemanticsTest().`test tags are discoverable and carry bounds`()
        }
        run("TaoSceneSemanticsTest: clickNode clicks through semantics bounds") {
            TaoSceneSemanticsTest().`clickNode clicks through semantics bounds`()
        }
        run("TaoSceneSemanticsTest: semantics updates track recomposition") {
            TaoSceneSemanticsTest().`semantics updates track recomposition`()
        }
        run("TaoSceneSemanticsTest: clickable nodes expose an onClick action") {
            TaoSceneSemanticsTest().`clickable nodes expose an onClick action`()
        }

        run("TaoSceneContentSwapTest: swapping a scrollable page of buttons does not crash RectList") {
            TaoSceneContentSwapTest().`swapping a scrollable page of buttons does not crash RectList`()
        }
        run("TaoSceneContentSwapTest: clicking a tab remounts the body without a RectList crash") {
            TaoSceneContentSwapTest().`clicking a tab remounts the body without a RectList crash`()
        }

        run("TaoSceneExceptionHandlerTest: composition failure reaches the handler") {
            TaoSceneExceptionHandlerTest().`composition failure reaches the handler`()
        }
        run("TaoSceneExceptionHandlerTest: a swallowed composition failure leaves the scene unable to recompose") {
            TaoSceneExceptionHandlerTest()
                .`a swallowed composition failure leaves the scene unable to recompose`()
        }
        run("TaoSceneExceptionHandlerTest: a dead scene does not spin the frame scheduler") {
            TaoSceneExceptionHandlerTest().`a dead scene does not spin the frame scheduler`()
        }
        run("TaoSceneExceptionHandlerTest: layout failure reaches the handler") {
            TaoSceneExceptionHandlerTest().`layout failure reaches the handler`()
        }
        run("TaoSceneExceptionHandlerTest: draw failure reaches the handler and the scene keeps rendering") {
            TaoSceneExceptionHandlerTest().`draw failure reaches the handler and the scene keeps rendering`()
        }
        run("TaoSceneExceptionHandlerTest: a swallowed draw failure keeps state updates flowing") {
            TaoSceneExceptionHandlerTest().`a swallowed draw failure keeps state updates flowing`()
        }
        run("TaoSceneExceptionHandlerTest: a scene that survived a swallowed failure still accepts new content") {
            TaoSceneExceptionHandlerTest().`a scene that survived a swallowed failure still accepts new content`()
        }
        run("TaoSceneExceptionHandlerTest: input dispatch failure reaches the handler") {
            TaoSceneExceptionHandlerTest().`input dispatch failure reaches the handler`()
        }
        run("TaoSceneExceptionHandlerTest: a handler that rethrows propagates the failure") {
            TaoSceneExceptionHandlerTest().`a handler that rethrows propagates the failure`()
        }
        run("TaoSceneExceptionHandlerTest: without a handler the failure propagates") {
            TaoSceneExceptionHandlerTest().`without a handler the failure propagates`()
        }

        run("TaoSceneExceptionRouterTest: a failure with no window handler takes the fatal path") {
            TaoSceneExceptionRouterTest().`a failure with no window handler takes the fatal path`()
        }
        run("TaoSceneExceptionRouterTest: a handler that rethrows takes the fatal path") {
            TaoSceneExceptionRouterTest().`a handler that rethrows takes the fatal path`()
        }
        run("TaoSceneExceptionRouterTest: a handler may substitute the throwable it rethrows") {
            TaoSceneExceptionRouterTest().`a handler may substitute the throwable it rethrows`()
        }
        run("TaoSceneExceptionRouterTest: a handler that returns normally swallows the failure") {
            TaoSceneExceptionRouterTest().`a handler that returns normally swallows the failure`()
        }
        run("TaoSceneExceptionRouterTest: swallowing a failure the scene cannot survive is logged") {
            TaoSceneExceptionRouterTest().`swallowing a failure the scene cannot survive is logged`()
        }
        run("TaoSceneExceptionRouterTest: swallowing a survivable failure is not logged") {
            TaoSceneExceptionRouterTest().`swallowing a survivable failure is not logged`()
        }
        run("TaoSceneExceptionRouterTest: a failure during teardown is logged instead of taking the app down") {
            TaoSceneExceptionRouterTest()
                .`a failure during teardown is logged instead of taking the app down`()
        }

        run("TaoA11yProjectionTest: compose semantics are projected into the a11y node snapshot") {
            TaoA11yProjectionTest().`compose semantics are projected into the a11y node snapshot`()
        }
        run("TaoA11yProjectionTest: semantics changes propagate into the next snapshot") {
            TaoA11yProjectionTest().`semantics changes propagate into the next snapshot`()
        }
        run("TaoA11yProjectionTest: projected snapshot round-trips through the v7 wire format") {
            TaoA11yProjectionTest().`projected snapshot round-trips through the v7 wire format`()
        }

        run("LcdTextTest: transparent windows disable LCD surface props") {
            LcdTextTest().`transparent windows disable LCD surface props`()
        }
        run("LcdTextTest: opaque windows on Windows keep RGB or BGR geometry") {
            LcdTextTest().`opaque windows on Windows keep RGB or BGR geometry`()
        }
        run("LcdTextTest: macOS and Linux stay grayscale") {
            LcdTextTest().`macOS and Linux stay grayscale`()
        }
        run("LcdTextTest: ClearType off means no LCD surface props") {
            LcdTextTest().`ClearType off means no LCD surface props`()
        }
        run("LcdTextTest: Compose LCD text on an RGB surface has chromatic edges") {
            LcdTextTest().`Compose LCD text on an RGB surface has chromatic edges`()
        }

        run("WindowPositionerTest: right to left anchoring hangs the child off the right edge of the parent") {
            WindowPositionerTest().`right to left anchoring hangs the child off the right edge of the parent`()
        }
        run("WindowPositionerTest: offset is applied after the anchors meet") {
            WindowPositionerTest().`offset is applied after the anchors meet`()
        }
        run("WindowPositionerTest: centre to centre puts the child on the middle of the parent") {
            WindowPositionerTest().`centre to centre puts the child on the middle of the parent`()
        }
        run("WindowPositionerTest: a sub-rectangle of the parent anchors the child to that rectangle") {
            WindowPositionerTest().`a sub-rectangle of the parent anchors the child to that rectangle`()
        }
        run("WindowPositionerTest: the anchor point is clamped to the parent rectangle") {
            WindowPositionerTest().`the anchor point is clamped to the parent rectangle`()
        }
        run("WindowPositionerTest: no adjustment leaves the child outside the work area") {
            WindowPositionerTest().`no adjustment leaves the child outside the work area`()
        }
        run("WindowPositionerTest: flip mirrors the child to the other side when it would overhang") {
            WindowPositionerTest().`flip mirrors the child to the other side when it would overhang`()
        }
        run("WindowPositionerTest: slide translates the child back inside the work area") {
            WindowPositionerTest().`slide translates the child back inside the work area`()
        }
        run("WindowPositionerTest: flip is preferred over slide") {
            WindowPositionerTest().`flip is preferred over slide`()
        }
        run("WindowPositionerTest: resize shrinks the child when nothing else fits") {
            WindowPositionerTest().`resize shrinks the child when nothing else fits`()
        }
        run("WindowPositionerTest: vertical flip mirrors a bottom anchored child upwards") {
            WindowPositionerTest().`vertical flip mirrors a bottom anchored child upwards`()
        }
        run("WindowPositionerTest: an unconstrained placement is returned untouched by every adjustment") {
            WindowPositionerTest().`an unconstrained placement is returned untouched by every adjustment`()
        }

        run(
            "SatelliteDockedGeometryTest: docking from a floating window brings its size along as the panel extent",
        ) {
            SatelliteDockedGeometryTest()
                .`docking from a floating window brings its size along as the panel extent`()
        }
        run(
            "SatelliteDockedGeometryTest: re-docking keeps the extent along the same axis and re-seeds it across axes",
        ) {
            SatelliteDockedGeometryTest()
                .`re-docking keeps the extent along the same axis and re-seeds it across axes`()
        }
        run(
            "SatelliteDockedGeometryTest: docked extent and weight are clamped and ignored for a floating satellite",
        ) {
            SatelliteDockedGeometryTest().`docked extent and weight are clamped and ignored for a floating satellite`()
        }
        run("SatelliteDockedGeometryTest: a panel moved between docks seeds its new side with the width it had") {
            SatelliteDockedGeometryTest().`a panel moved between docks seeds its new side with the width it had`()
        }
        run("SatelliteDockedGeometryTest: a snapshot carries every panel's own extent and weight") {
            SatelliteDockedGeometryTest().`a snapshot carries every panel's own extent and weight`()
        }
        run("SatelliteDockedGeometryTest: a docked placement refuses a weight that is not positive") {
            SatelliteDockedGeometryTest().`a docked placement refuses a weight that is not positive`()
        }
        run("SatelliteDockedGeometryTest: every side has an opposite across the content") {
            SatelliteDockedGeometryTest().`every side has an opposite across the content`()
        }
        run("DockLandingRectTest: a bottom preview spans the bottom band, not the layout") {
            DockLandingRectTest().`a bottom preview spans the bottom band, not the layout`()
        }
        run("DockLandingRectTest: a layered side previews a new innermost layer") {
            DockLandingRectTest().`a layered side previews a new innermost layer`()
        }
        run("DockLandingRectTest: a split side with a stack previews the stack the panel joins") {
            DockLandingRectTest().`a split side with a stack previews the stack the panel joins`()
        }
        run("DockLandingRectTest: an empty side previews a strip at the edge of its band") {
            DockLandingRectTest().`an empty side previews a strip at the edge of its band`()
        }
        run("DockLandingRectTest: the side the dragged panel frees is counted as already gone") {
            DockLandingRectTest().`the side the dragged panel frees is counted as already gone`()
        }
        run("DockLandingRectTest: a side the dragged panel shares with another is not freed") {
            DockLandingRectTest().`a side the dragged panel shares with another is not freed`()
        }
        run("DockLandingRectTest: without a measured band the layout itself is the band") {
            DockLandingRectTest().`without a measured band the layout itself is the band`()
        }
        run("DockZoneHintSidesTest: a floating satellite is offered every side") {
            DockZoneHintSidesTest().`a floating satellite is offered every side`()
        }
        run("DockZoneHintSidesTest: a docked panel is not offered the side it is alone on") {
            DockZoneHintSidesTest().`a docked panel is not offered the side it is alone on`()
        }
        run("DockZoneHintSidesTest: a docked panel with a neighbour is offered its own side, to be ranked among them") {
            DockZoneHintSidesTest().`a docked panel with a neighbour is offered its own side, to be ranked among them`()
        }
        run(
            "DockDropSlotsTest: a layered side is cut at the layers' centres, from its edge through the strip",
        ) {
            DockDropSlotsTest()
                .`a layered side is cut at the layers' centres, from its edge through the strip`()
        }
        run("DockDropSlotsTest: a split side is cut along its length, from the band's start") {
            DockDropSlotsTest().`a split side is cut along its length, from the band's start`()
        }
        run("DockDropSlotsTest: no slots without another panel, or before it is placed") {
            DockDropSlotsTest().`no slots without another panel, or before it is placed`()
        }
        run("DockDropSlotsTest: the pointer picks the slot it is in, else the nearest end") {
            DockDropSlotsTest().`the pointer picks the slot it is in, else the nearest end`()
        }
        run("DockDropSlotsTest: a pinned layer hides the ranks in front of it, for itself and for the others") {
            DockDropSlotsTest().`a pinned layer hides the ranks in front of it, for itself and for the others`()
        }
        run("DockDropSlotsTest: a layer dropped at a rank is drawn where that rank puts it, at its own extent") {
            DockDropSlotsTest().`a layer dropped at a rank is drawn where that rank puts it, at its own extent`()
        }
        run("DockDropSlotsTest: a panel dropped in a split stack is drawn as the share the weights give it") {
            DockDropSlotsTest().`a panel dropped in a split stack is drawn as the share the weights give it`()
        }
        run("DockDropSlotsTest: dropped on an empty side, the space is the strip along its edge") {
            DockDropSlotsTest().`dropped on an empty side, the space is the strip along its edge`()
        }
        run("DockZoneHintSidesTest: another window offers the side too, since dropping there is a move") {
            DockZoneHintSidesTest().`another window offers the side too, since dropping there is a move`()
        }
        run("DockTargetFromDraggedRectTest: the dragged rect decides the zone, not the pointer") {
            DockTargetFromDraggedRectTest().`the dragged rect decides the zone, not the pointer`()
        }
        run("DockTargetFromDraggedRectTest: an inset zone is the target, not the window's own edge") {
            DockTargetFromDraggedRectTest().`an inset zone is the target, not the window's own edge`()
        }
        run(
            "DockTargetFromDraggedRectTest: the pointer over a stack picks a rank, and beats a strip across its corner",
        ) {
            DockTargetFromDraggedRectTest()
                .`the pointer over a stack picks a rank, and beats a strip across its corner`()
        }
        run("DockTargetFromDraggedRectTest: a dragged rect covering every zone is resolved by the pointer") {
            DockTargetFromDraggedRectTest().`a dragged rect covering every zone is resolved by the pointer`()
        }

        run("SatelliteWorkspaceTest: the first member to join owns the satellites until focus moves") {
            SatelliteWorkspaceTest().`the first member to join owns the satellites until focus moves`()
        }
        run("SatelliteWorkspaceTest: pinning overrides focus until released") {
            SatelliteWorkspaceTest().`pinning overrides focus until released`()
        }
        run("SatelliteWorkspaceTest: without follow focus the owner is the pinned or first member") {
            SatelliteWorkspaceTest().`without follow focus the owner is the pinned or first member`()
        }
        run("SatelliteWorkspaceTest: docking a floating satellite seeds the side extent and hosts it in the owner") {
            SatelliteWorkspaceTest().`docking a floating satellite seeds the side extent and hosts it in the owner`()
        }
        run("SatelliteDragKindTest: a pointer drag is carried by the window, and the kind clears with it") {
            SatelliteDragKindTest().`a pointer drag is carried by the window, and the kind clears with it`()
        }
        run("SatelliteDragKindTest: a transfer drag is carried by the platform session, and publishes no ghost") {
            SatelliteDragKindTest().`a transfer drag is carried by the platform session, and publishes no ghost`()
        }
        run("SatelliteDragKindTest: a window that is not a native Wayland surface places on screen") {
            SatelliteDragKindTest().`a window that is not a native Wayland surface places on screen`()
        }
        run("SatelliteFixedPanelTest: undock refuses a fixed panel") {
            SatelliteFixedPanelTest().`undock refuses a fixed panel`()
        }
        run("SatelliteFixedPanelTest: a fixed satellite must be declared docked") {
            SatelliteFixedPanelTest().`a fixed satellite must be declared docked`()
        }
        run("SatelliteFixedPanelTest: a drag released over the content leaves a fixed panel docked, with no ghost") {
            SatelliteFixedPanelTest().`a drag released over the content leaves a fixed panel docked, with no ghost`()
        }
        run("SatelliteFixedPanelTest: a transfer drag with no record leaves a fixed panel docked") {
            SatelliteFixedPanelTest().`a transfer drag with no record leaves a fixed panel docked`()
        }
        run("SatelliteFixedPanelTest: a snapshot that floats a fixed panel is ignored, but its open state is not") {
            SatelliteFixedPanelTest().`a snapshot that floats a fixed panel is ignored, but its open state is not`()
        }
        run(
            "SatelliteFixedPanelTest: a pinned panel is offered no rank and its drag changes nothing",
        ) {
            SatelliteFixedPanelTest()
                .`a pinned panel is offered no rank and its drag changes nothing`()
        }
        run("SatelliteFixedPanelTest: another panel is docked after the pinned ones, whatever rank it asks for") {
            SatelliteFixedPanelTest().`another panel is docked after the pinned ones, whatever rank it asks for`()
        }
        run("SatelliteFixedPanelTest: a panel that can go nowhere is no drag handle") {
            SatelliteFixedPanelTest().`a panel that can go nowhere is no drag handle`()
        }
        run("SatelliteDockSidesTest: dock refuses a side the satellite was not declared for") {
            SatelliteDockSidesTest().`dock refuses a side the satellite was not declared for`()
        }
        run(
            "SatelliteDockSidesTest: floating-only never docks, the preferred side follows the declaration",
        ) {
            SatelliteDockSidesTest()
                .`floating-only never docks, the preferred side follows the declaration`()
        }
        run("SatelliteDockSidesTest: a declared docked placement must name an allowed side") {
            SatelliteDockSidesTest().`a declared docked placement must name an allowed side`()
        }
        run(
            "SatelliteDockSidesTest: a refused side is not hinted nor previewed, a release there keeps it floating",
        ) {
            SatelliteDockSidesTest()
                .`a refused side is not hinted nor previewed, a release there keeps it floating`()
        }
        run("SatelliteDockSidesTest: a snapshot naming a refused side leaves the placement alone") {
            SatelliteDockSidesTest().`a snapshot naming a refused side leaves the placement alone`()
        }
        run("SatelliteDockRankTest: dock order inserts at that rank and keeps the side contiguous") {
            SatelliteDockRankTest().`dock order inserts at that rank and keeps the side contiguous`()
        }
        run("SatelliteDockRankTest: a satellite docked again on the side it left returns to its rank") {
            SatelliteDockRankTest().`a satellite docked again on the side it left returns to its rank`()
        }
        run(
            "SatelliteDockRankTest: a satellite new to a side is appended there and keeps its rank elsewhere",
        ) {
            SatelliteDockRankTest()
                .`a satellite new to a side is appended there and keeps its rank elsewhere`()
        }
        run("SatelliteDockRankTest: a closed panel keeps its rank and the weight comes back with it") {
            SatelliteDockRankTest().`a closed panel keeps its rank and the weight comes back with it`()
        }
        run("SatelliteWorkspaceTest: undock without host geometry returns to the last floating placement") {
            SatelliteWorkspaceTest().`undock without host geometry returns to the last floating placement`()
        }
        run("SatelliteWorkspaceTest: a member leaving rehosts the satellites docked into it") {
            SatelliteWorkspaceTest().`a member leaving rehosts the satellites docked into it`()
        }
        run("SatelliteWorkspaceTest: open close and toggle only touch the open flag") {
            SatelliteWorkspaceTest().`open close and toggle only touch the open flag`()
        }
        run("SatelliteWorkspaceTest: restore clamps a dock extent that would make the splitter unreachable") {
            SatelliteWorkspaceTest().`restore clamps a dock extent that would make the splitter unreachable`()
        }
        run("SatelliteWorkspaceTest: the planned extent of an untouched side is the satellite's own size") {
            SatelliteWorkspaceTest().`the planned extent of an untouched side is the satellite's own size`()
        }
        run("SatelliteWorkspaceTest: snapshot and restore round trip including a satellite declared later") {
            SatelliteWorkspaceTest().`snapshot and restore round trip including a satellite declared later`()
        }
        run("SatelliteWorkspaceTest: dock target is the zone strip inside each edge of a registered layout") {
            SatelliteWorkspaceTest().`dock target is the zone strip inside each edge of a registered layout`()
        }
        run("SatelliteWorkspaceTest: a floating drag moves the window along and docks where it is released") {
            SatelliteWorkspaceTest().`a floating drag moves the window along and docks where it is released`()
        }
        run("SatelliteWorkspaceTest: a docked drag released over content lifts the panel out under the pointer") {
            SatelliteWorkspaceTest().`a docked drag released over content lifts the panel out under the pointer`()
        }
        run("SatelliteWorkspaceTest: a docked drag released in another zone re-docks and inside its own panel stays") {
            SatelliteWorkspaceTest().`a docked drag released in another zone re-docks and inside its own panel stays`()
        }
        run("SatelliteDockRankTest: a docked drag dropped on its own stack takes the rank under the pointer") {
            SatelliteDockRankTest().`a docked drag dropped on its own stack takes the rank under the pointer`()
        }
        run("SatelliteWorkspaceTest: a cancelled drag leaves no feedback and no placement change") {
            SatelliteWorkspaceTest().`a cancelled drag leaves no feedback and no placement change`()
        }
        run("SatelliteWorkspaceTest: a teleporting pointer lands on the zone it was released in") {
            SatelliteWorkspaceTest().`a teleporting pointer lands on the zone it was released in`()
        }
        run("SatelliteWorkspaceTest: non-finite pointer samples are ignored and leave the last position standing") {
            SatelliteWorkspaceTest().`non-finite pointer samples are ignored and leave the last position standing`()
        }
        run("SatelliteWorkspaceTest: a superseded drag stops acting and cannot clear the live one") {
            SatelliteWorkspaceTest().`a superseded drag stops acting and cannot clear the live one`()
        }
        run("SatelliteWorkspaceTest: ending or cancelling twice is a no-op") {
            SatelliteWorkspaceTest().`ending or cancelling twice is a no-op`()
        }
        run("SatelliteWorkspaceTest: the tear-out ghost carries the host scale, not the composition's") {
            SatelliteWorkspaceTest().`the tear-out ghost carries the host scale, not the composition's`()
        }
        run("SatelliteWorkspaceTest: a drag whose host leaves mid-gesture still resolves") {
            SatelliteWorkspaceTest().`a drag whose host leaves mid-gesture still resolves`()
        }
        run("SatelliteWorkspaceTest: a drag whose satellite is closed mid-gesture changes nothing") {
            SatelliteWorkspaceTest().`a drag whose satellite is closed mid-gesture changes nothing`()
        }
        run("SatelliteWorkspaceTest: dock and undock churn keeps one consistent placement") {
            SatelliteWorkspaceTest().`dock and undock churn keeps one consistent placement`()
        }
        run("SatelliteWorkspaceTest: interleaved drags of two satellites keep their own placements") {
            SatelliteWorkspaceTest().`interleaved drags of two satellites keep their own placements`()
        }
        run("SatelliteWorkspaceTest: a drop resolves against the state a restore left behind") {
            SatelliteWorkspaceTest().`a drop resolves against the state a restore left behind`()
        }
        run("SatelliteWorkspaceTest: re-registering an id keeps the workspace's memory of it") {
            SatelliteWorkspaceTest().`re-registering an id keeps the workspace's memory of it`()
        }
        run("SatelliteWorkspaceTest: a minimized member is skipped as a drop target") {
            SatelliteWorkspaceTest().`a minimized member is skipped as a drop target`()
        }
        run("SatelliteWorkspaceTest: overlapping layouts resolve to the owner then the last focused member") {
            SatelliteWorkspaceTest().`overlapping layouts resolve to the owner then the last focused member`()
        }

        run("RelocatingSaveableStateRegistryTest: keys relocate across hosts by rotation of the anchor delta") {
            RelocatingSaveableStateRegistryTest().`keys relocate across hosts by rotation of the anchor delta`()
        }
        run("RelocatingSaveableStateRegistryTest: values keep their order when providers unregister in reverse") {
            RelocatingSaveableStateRegistryTest().`values keep their order when providers unregister in reverse`()
        }
        run("RelocatingSaveableStateRegistryTest: a re-registering provider keeps its place among the values") {
            RelocatingSaveableStateRegistryTest().`a re-registering provider keeps its place among the values`()
        }
        run("RelocatingSaveableStateRegistryTest: restored values never consumed survive another host change") {
            RelocatingSaveableStateRegistryTest().`restored values never consumed survive another host change`()
        }
        run("RelocatingSaveableStateRegistryTest: a slot snapshot prefers the live registry over the last save") {
            RelocatingSaveableStateRegistryTest().`a slot snapshot prefers the live registry over the last save`()
        }

        run("WindowGroupTest: the owner is the pinned member, else the last focused, else the first joined") {
            WindowGroupTest().`the owner is the pinned member, else the last focused, else the first joined`()
        }
        run("WindowGroupTest: a leaving owner hands over to the member focused before it") {
            WindowGroupTest().`a leaving owner hands over to the member focused before it`()
        }
        run("WindowGroupTest: members by recency put the owner first and never-focused members last in join order") {
            WindowGroupTest().`members by recency put the owner first and never-focused members last in join order`()
        }
        run("WindowGroupTest: a pin to a non-member is kept but ignored until it joins") {
            WindowGroupTest().`a pin to a non-member is kept but ignored until it joins`()
        }
        run("WindowGroupTest: join is idempotent, leaving a stranger is a no-op, and the hooks see both") {
            WindowGroupTest().`join is idempotent, leaving a stranger is a no-op, and the hooks see both`()
        }
        run("WindowGroupTest: without follow focus the owner ignores focus and takes the pin or the first member") {
            WindowGroupTest().`without follow focus the owner ignores focus and takes the pin or the first member`()
        }

        run("HostGeometryTest: client origin splits the side borders evenly and matches them at the bottom") {
            HostGeometryTest().`client origin splits the side borders evenly and matches them at the bottom`()
        }
        run("HostGeometryTest: screen rect is unknown until both the container size and the outer frame are") {
            HostGeometryTest().`screen rect is unknown until both the container size and the outer frame are`()
        }
        run("HostGeometryTest: scale falls back to one while the window reports none") {
            HostGeometryTest().`scale falls back to one while the window reports none`()
        }
        run("HostGeometryTest: the registry keeps one geometry per window and only that one can unregister") {
            HostGeometryTest().`the registry keeps one geometry per window and only that one can unregister`()
        }
        run("HostGeometryTest: ordered lists the given hosts first and the rest in registration order") {
            HostGeometryTest().`ordered lists the given hosts first and the rest in registration order`()
        }

        run("DragControllerTest: begin supersedes the live session and clears the feedback once") {
            DragControllerTest().`begin supersedes the live session and clears the feedback once`()
        }
        run("DragControllerTest: release ignores a session that is not live and is idempotent for the live one") {
            DragControllerTest().`release ignores a session that is not live and is idempotent for the live one`()
        }
        run("DragControllerTest: release of null ends whichever session is live") {
            DragControllerTest().`release of null ends whichever session is live`()
        }

        run("TransferDragTest: nearest edge within the zone wins") {
            TransferDragTest().`nearest edge within the zone wins`()
        }
        run("TransferDragTest: a corner resolves to the closer of its two edges") {
            TransferDragTest().`a corner resolves to the closer of its two edges`()
        }
        run("TransferDragTest: content and points outside the layout are no zone") {
            TransferDragTest().`content and points outside the layout are no zone`()
        }
        run("TransferDragTest: a zone wider than the layout still resolves to exactly one side") {
            TransferDragTest().`a zone wider than the layout still resolves to exactly one side`()
        }
        run("TransferDragTest: the private payload round-trips under its own flavor only") {
            TransferDragTest().`the private payload round-trips under its own flavor only`()
        }
        run("TransferDragTest: an ordinary transferable carries no token") {
            TransferDragTest().`an ordinary transferable carries no token`()
        }

        run("TransferDragTest: the transfer ends the drag when the platform reports the session over") {
            TransferDragTest().`the transfer ends the drag when the platform reports the session over`()
        }
        run("TransferDragTest: the transfer carries the private token and a Move action only") {
            TransferDragTest().`the transfer carries the private token and a Move action only`()
        }
        run("TransferDragTest: the decoration offset puts the hotspot under the pointer, clamped to the icon") {
            TransferDragTest().`the decoration offset puts the hotspot under the pointer, clamped to the icon`()
        }

        run("TransferDragTest: without a picture the icon is the title card, one to one") {
            TransferDragTest().`without a picture the icon is the title card, one to one`()
        }
        run("TransferDragTest: a picture is shown reduced and capped on its longer edge") {
            TransferDragTest().`a picture is shown reduced and capped on its longer edge`()
        }
        run("TransferDragTest: the hotspot follows the grab point into the reduced picture of a region") {
            TransferDragTest().`the hotspot follows the grab point into the reduced picture of a region`()
        }

        run("TabHoverPreviewTest: the strip reports the tab the pointer rests on, and nothing once it leaves") {
            TabHoverPreviewTest().`the strip reports the tab the pointer rests on, and nothing once it leaves`()
        }
        run("TabHoverPreviewTest: a press puts the card away until the pointer has been elsewhere") {
            TabHoverPreviewTest().`a press puts the card away until the pointer has been elsewhere`()
        }
        run("TabHoverPreviewTest: a press on a tab the pointer is not on changes nothing") {
            TabHoverPreviewTest().`a press on a tab the pointer is not on changes nothing`()
        }
        run("TabHoverPreviewTest: no card while a tab is being dragged") {
            TabHoverPreviewTest().`no card while a tab is being dragged`()
        }
        run("TabHoverPreviewTest: a tab that has left the group is no longer hovered") {
            TabHoverPreviewTest().`a tab that has left the group is no longer hovered`()
        }
        run("TabHoverPreviewTest: the anchor of a card is the tab's own slot, and nothing before it is placed") {
            TabHoverPreviewTest().`the anchor of a card is the tab's own slot, and nothing before it is placed`()
        }
        run("TabHoverPreviewTest: the card hangs from the tab's leading edge, below it") {
            TabHoverPreviewTest().`the card hangs from the tab's leading edge, below it`()
        }
        run("TabHoverPreviewTest: a right-to-left strip hangs the card from the tab's right edge") {
            TabHoverPreviewTest().`a right-to-left strip hangs the card from the tab's right edge`()
        }
        run("TabHoverPreviewTest: a card that would run off the window is slid back in") {
            TabHoverPreviewTest().`a card that would run off the window is slid back in`()
        }
        run("TabHoverPreviewTest: the selected tab has no card") {
            TabHoverPreviewTest().`the selected tab has no card`()
        }

        run("TabWorkspaceTest: a drag selects the tab it lifted, so a click that drifts is never lost") {
            TabWorkspaceTest().`a drag selects the tab it lifted, so a click that drifts is never lost`()
        }
        run("TabWorkspaceTest: taking a tab in hand inside its own strip selects it too") {
            TabWorkspaceTest().`taking a tab in hand inside its own strip selects it too`()
        }
        run("TabWorkspaceTest: a right-to-left strip resolves its insertion indices from the right") {
            TabWorkspaceTest().`a right-to-left strip resolves its insertion indices from the right`()
        }
        run("TabWorkspaceTest: the first tab opens a window and the next ones join it") {
            TabWorkspaceTest().`the first tab opens a window and the next ones join it`()
        }
        run("TabWorkspaceTest: a named group is created on demand and keeps its name") {
            TabWorkspaceTest().`a named group is created on demand and keeps its name`()
        }
        run("TabWorkspaceTest: re-registering an id keeps its place and only refreshes the title") {
            TabWorkspaceTest().`re-registering an id keeps its place and only refreshes the title`()
        }
        run("TabWorkspaceTest: closing the selected tab selects its right neighbour, then its left") {
            TabWorkspaceTest().`closing the selected tab selects its right neighbour, then its left`()
        }
        run("TabWorkspaceTest: closing an unselected tab leaves the selection alone") {
            TabWorkspaceTest().`closing an unselected tab leaves the selection alone`()
        }
        run("TabWorkspaceTest: the last tab of a window takes the window with it") {
            TabWorkspaceTest().`the last tab of a window takes the window with it`()
        }
        run("TabWorkspaceTest: closing an unknown tab is a no-op") {
            TabWorkspaceTest().`closing an unknown tab is a no-op`()
        }
        run("TabWorkspaceTest: a move to another group inserts at the index and selects there") {
            TabWorkspaceTest().`a move to another group inserts at the index and selects there`()
        }
        run("TabWorkspaceTest: a move index beyond the strip appends and a negative one prepends") {
            TabWorkspaceTest().`a move index beyond the strip appends and a negative one prepends`()
        }
        run("TabWorkspaceTest: a move within its own group is a reorder and keeps the selection") {
            TabWorkspaceTest().`a move within its own group is a reorder and keeps the selection`()
        }
        run("TabWorkspaceTest: a move into a dropped group and of an unknown tab are both no-ops") {
            TabWorkspaceTest().`a move into a dropped group and of an unknown tab are both no-ops`()
        }
        run("TabWorkspaceTest: tearing a tab off a multi-tab window opens a window at the rect") {
            TabWorkspaceTest().`tearing a tab off a multi-tab window opens a window at the rect`()
        }
        run("TabWorkspaceTest: tearing off the only tab of a window moves that window instead") {
            TabWorkspaceTest().`tearing off the only tab of a window moves that window instead`()
        }
        run("TabWorkspaceTest: a tear-off rect measured at an unusable scale falls back to one") {
            TabWorkspaceTest().`a tear-off rect measured at an unusable scale falls back to one`()
        }
        run("TabWorkspaceTest: tearing off an unknown tab changes nothing") {
            TabWorkspaceTest().`tearing off an unknown tab changes nothing`()
        }
        run("TabWorkspaceTest: the card entering a strip is a drop before the pointer reaches it") {
            TabWorkspaceTest().`the card entering a strip is a drop before the pointer reaches it`()
        }
        run("TabWorkspaceTest: a dragged window's own strip never answers for the card either") {
            TabWorkspaceTest().`a dragged window's own strip never answers for the card either`()
        }
        run("TabWorkspaceTest: a drop resolves to the strip under the pointer and the index it falls at") {
            TabWorkspaceTest().`a drop resolves to the strip under the pointer and the index it falls at`()
        }
        run("TabWorkspaceTest: the dragged tab's own slot is counted out of the index") {
            TabWorkspaceTest().`the dragged tab's own slot is counted out of the index`()
        }
        run("TabWorkspaceTest: a minimized window is never a drop target") {
            TabWorkspaceTest().`a minimized window is never a drop target`()
        }
        run("TabWorkspaceTest: overlapping strips resolve to the window focused most recently") {
            TabWorkspaceTest().`overlapping strips resolve to the window focused most recently`()
        }
        run("TabWorkspaceTest: an excluded group is skipped for the strip underneath it") {
            TabWorkspaceTest().`an excluded group is skipped for the strip underneath it`()
        }
        run("TabWorkspaceTest: a strip with no slots published yet resolves to index zero") {
            TabWorkspaceTest().`a strip with no slots published yet resolves to index zero`()
        }
        run("TabWorkspaceTest: dragging one of several tabs shows a ghost and inserts where it is dropped") {
            TabWorkspaceTest().`dragging one of several tabs shows a ghost and inserts where it is dropped`()
        }
        run("TabWorkspaceTest: dragging one of several tabs into empty space tears off a window under the pointer") {
            TabWorkspaceTest().`dragging one of several tabs into empty space tears off a window under the pointer`()
        }
        run("TabWorkspaceTest: dragging the only tab of a window moves the window and shows no ghost") {
            TabWorkspaceTest().`dragging the only tab of a window moves the window and shows no ghost`()
        }
        run("TabWorkspaceTest: dropping the only tab of a window on another strip merges and closes it") {
            TabWorkspaceTest().`dropping the only tab of a window on another strip merges and closes it`()
        }
        run("TabWorkspaceTest: a teleporting pointer lands on the strip it was released over") {
            TabWorkspaceTest().`a teleporting pointer lands on the strip it was released over`()
        }
        run("TabWorkspaceTest: non-finite samples are ignored and leave the last position standing") {
            TabWorkspaceTest().`non-finite samples are ignored and leave the last position standing`()
        }
        run("TabWorkspaceTest: a beginDrag with a non-finite pointer is refused") {
            TabWorkspaceTest().`a beginDrag with a non-finite pointer is refused`()
        }
        run("TabWorkspaceTest: a drag is refused while the strip has published no geometry") {
            TabWorkspaceTest().`a drag is refused while the strip has published no geometry`()
        }
        run("TabWorkspaceTest: a superseded drag stops acting and cannot clear the live one") {
            TabWorkspaceTest().`a superseded drag stops acting and cannot clear the live one`()
        }
        run("TabWorkspaceTest: ending or cancelling twice is a no-op") {
            TabWorkspaceTest().`ending or cancelling twice is a no-op`()
        }
        run("TabWorkspaceTest: a drag whose window closes mid-gesture still resolves") {
            TabWorkspaceTest().`a drag whose window closes mid-gesture still resolves`()
        }
        run("TabWorkspaceTest: a drag whose tab is closed mid-gesture leaves the workspace alone") {
            TabWorkspaceTest().`a drag whose tab is closed mid-gesture leaves the workspace alone`()
        }
        run("TabWorkspaceTest: tear-off and merge churn keeps every tab in exactly one window") {
            TabWorkspaceTest().`tear-off and merge churn keeps every tab in exactly one window`()
        }
        run("TabWorkspaceTest: snapshot and restore round trip including a tab declared later") {
            TabWorkspaceTest().`snapshot and restore round trip including a tab declared later`()
        }
        run("TabWorkspaceTest: a restore rebuilds strip order whatever order the tabs are declared in") {
            TabWorkspaceTest().`a restore rebuilds strip order whatever order the tabs are declared in`()
        }
        run("TabWorkspaceTest: a restore moves a window that is already open and bumps its placement") {
            TabWorkspaceTest().`a restore moves a window that is already open and bumps its placement`()
        }
        run("TabWorkspaceTest: a snapshot falls back to the recorded placement without a live window") {
            TabWorkspaceTest().`a snapshot falls back to the recorded placement without a live window`()
        }
        run("TabWorkspaceTest: restoring an empty snapshot leaves the workspace alone") {
            TabWorkspaceTest().`restoring an empty snapshot leaves the workspace alone`()
        }

        return results
    }
}

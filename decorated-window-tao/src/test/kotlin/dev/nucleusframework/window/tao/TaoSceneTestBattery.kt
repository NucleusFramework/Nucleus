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
import dev.nucleusframework.window.tao.popup.StandaloneFramePumpTest
import dev.nucleusframework.window.tao.popup.StandalonePopupRenderReentryTest
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

/**
 * Programmatic, reflection-free registry of the stage-1 offscreen battery so
 * it can run inside a GraalVM native image (JUnit discovery needs reflection
 * metadata; direct calls need none). Kept in sync with the @Test methods by
 * [TaoSceneTestBatteryDriftTest], which fails the ordinary JUnit run when an
 * entry is missing, stale, or a new test class is neither registered here
 * nor declared JVM-only.
 */
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
        run("MacOsWheelDeltaTest: gesturePhaseRidesAlongForPreciseEventsOnly") {
            MacOsWheelDeltaTest().gesturePhaseRidesAlongForPreciseEventsOnly()
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
        run("TaoTrackpadPanRouterTest: swipe without momentum ends after the grace period") {
            TaoTrackpadPanRouterTest().`swipe without momentum ends after the grace period`()
        }
        run("TaoTrackpadPanRouterTest: terminal steps carrying a delta still pan when no gesture is open") {
            TaoTrackpadPanRouterTest().`terminal steps carrying a delta still pan when no gesture is open`()
        }
        run("TaoTrackpadPanRouterTest: momentum tail continues the pan and ends it once") {
            TaoTrackpadPanRouterTest().`momentum tail continues the pan and ends it once`()
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

        return results
    }
}

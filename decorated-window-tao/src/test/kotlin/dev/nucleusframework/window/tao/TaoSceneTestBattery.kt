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
        run("MacOsWheelDeltaTest: horizontalDeltaKeepsAppKitX") {
            MacOsWheelDeltaTest().horizontalDeltaKeepsAppKitX()
        }
        run("MacOsWheelDeltaTest: precisePixelDeltaMatchesTaoWindowScale") {
            MacOsWheelDeltaTest().precisePixelDeltaMatchesTaoWindowScale()
        }
        run("MacOsWheelDeltaTest: precisePixelHorizontalMatchesTaoWindowScale") {
            MacOsWheelDeltaTest().precisePixelHorizontalMatchesTaoWindowScale()
        }
        run("MacOsWheelDeltaTest: lineDeltaCarriesMacOsScrollAmount") {
            MacOsWheelDeltaTest().lineDeltaCarriesMacOsScrollAmount()
        }
        run("MacOsWheelDeltaTest: preciseDeltaCarriesMacOsScrollAmount") {
            MacOsWheelDeltaTest().preciseDeltaCarriesMacOsScrollAmount()
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
        run("SatelliteWorkspaceTest: dock order appends after the panels already on that side") {
            SatelliteWorkspaceTest().`dock order appends after the panels already on that side`()
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

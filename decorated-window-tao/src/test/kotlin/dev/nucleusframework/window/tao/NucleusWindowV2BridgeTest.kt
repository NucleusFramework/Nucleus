@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.window.tao.v2.DialogState
import dev.nucleusframework.window.tao.v2.WindowBoundsProvider
import dev.nucleusframework.window.tao.v2.WindowPositionProvider
import dev.nucleusframework.window.tao.v2.WindowScreenProvider
import dev.nucleusframework.window.tao.v2.WindowSizeProvider
import dev.nucleusframework.window.tao.v2.WindowState
import dev.nucleusframework.window.tao.v2.WindowStateWithBounds
import dev.nucleusframework.window.tao.v2.evaluateScreen
import dev.nucleusframework.window.tao.v2.screenScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AWT-free clone's whole point: providers that are inert on Compose's own
 * v2 types (they need an AWT `WindowGeometryProviderScope`) resolve here.
 */
class NucleusWindowV2BridgeTest {
    private val primaryAvailable get() = screenScope(window = null).defaultScreen.availableBounds

    @Test
    fun fixedSizeAndAbsolutePositionAreApplied() {
        val state =
            WindowState(
                initialBoundsProvider =
                    WindowBoundsProvider(
                        sizeProvider = WindowSizeProvider.Fixed(640.dp, 480.dp),
                        positionProvider = WindowPositionProvider.Absolute(40.dp, 60.dp),
                    ),
            )
        val v1 = nucleusWindowStateToV1(state)
        assertEquals(DpSize(640.dp, 480.dp), v1.size)
        assertEquals(WindowPosition.Absolute(40.dp, 60.dp), v1.position)
    }

    @Test
    fun requestSizeIsHonoured() {
        // The regression this clone exists for: WindowState.requestSize builds a
        // WindowBoundsProvider(sizeProvider, positionProvider), which Compose can
        // only evaluate with an AWT window.
        val state = WindowState()
        state.requestSize(DpSize(1280.dp, 800.dp))
        assertEquals(DpSize(1280.dp, 800.dp), nucleusWindowStateToV1(state).size)
    }

    @Test
    fun requestPositionIsHonoured() {
        val state = WindowState()
        state.requestPosition(DpOffset(120.dp, 140.dp))
        assertEquals(WindowPosition.Absolute(120.dp, 140.dp), nucleusWindowStateToV1(state).position)
    }

    @Test
    fun centeredOnScreenResolvesAgainstTheScreenWorkArea() {
        val size = DpSize(400.dp, 300.dp)
        val state =
            WindowState(
                initialBoundsProvider =
                    WindowBoundsProvider(
                        sizeProvider = WindowSizeProvider.Fixed(size),
                        positionProvider = WindowPositionProvider.CenteredOnScreen,
                    ),
            )
        val position = assertIs<WindowPosition.Absolute>(nucleusWindowStateToV1(state).position)
        val available = primaryAvailable
        val expectedX = available.left + ((available.right - available.left - size.width).value / 2f).dp
        val expectedY = available.top + ((available.bottom - available.top - size.height).value / 2f).dp
        assertEquals(expectedX.value, position.x.value, absoluteTolerance = 1f)
        assertEquals(expectedY.value, position.y.value, absoluteTolerance = 1f)
    }

    @Test
    fun alignedToScreenPlacesTheWindowInsideTheWorkArea() {
        val size = DpSize(300.dp, 200.dp)
        val state =
            WindowState(
                initialBoundsProvider =
                    WindowBoundsProvider(
                        sizeProvider = WindowSizeProvider.Fixed(size),
                        positionProvider = WindowPositionProvider.AlignedToScreen(Alignment.BottomEnd),
                    ),
            )
        val position = assertIs<WindowPosition.Absolute>(nucleusWindowStateToV1(state).position)
        val available = primaryAvailable
        assertEquals((available.right - size.width).value, position.x.value, absoluteTolerance = 1f)
        assertEquals((available.bottom - size.height).value, position.y.value, absoluteTolerance = 1f)
    }

    @Test
    fun scopedLambdaProviderCanReadWindowMetrics() {
        // WindowBoundsProvider { windowMetrics.… } is the shape that logs
        // "Ignoring a Compose WindowBoundsProvider…" on the Compose v2 path.
        val state =
            WindowState(
                initialBoundsProvider =
                    WindowBoundsProvider {
                        val available = windowMetrics.screen.availableBounds
                        DpRect(
                            left = available.left + 10.dp,
                            top = available.top + 20.dp,
                            right = available.left + 810.dp,
                            bottom = available.top + 620.dp,
                        )
                    },
            )
        val v1 = nucleusWindowStateToV1(state)
        val available = primaryAvailable
        assertEquals(WindowPosition.Absolute(available.left + 10.dp, available.top + 20.dp), v1.position)
        assertEquals(DpSize(800.dp, 600.dp), v1.size)
    }

    @Test
    fun unconstrainedSizeBecomesWrapContent() {
        val state = WindowState(initialBoundsProvider = WindowBoundsProvider(WindowSizeProvider.Unconstrained))
        val size = nucleusWindowStateToV1(state).size
        assertEquals(Dp.Unspecified, size.width)
        assertEquals(Dp.Unspecified, size.height)
    }

    @Test
    fun preferredWidthWrapsOnlyThatAxis() {
        val state =
            WindowState(initialBoundsProvider = WindowBoundsProvider(WindowSizeProvider.PreferredWidth(480.dp)))
        val size = nucleusWindowStateToV1(state).size
        assertEquals(Dp.Unspecified, size.width)
        assertEquals(480.dp, size.height)
    }

    @Test
    fun defaultProvidersKeepThePlatformDefault() {
        val v1 = nucleusWindowStateToV1(WindowState())
        assertEquals(WindowPosition.PlatformDefault, v1.position)
        assertEquals(DpSize(800.dp, 600.dp), v1.size)
    }

    @Test
    fun placementAndMinimizedRequestsSurviveTheConversion() {
        val state =
            WindowState(
                initialPlacement = WindowPlacement.Maximized,
                initiallyMinimized = true,
            )
        val v1 = nucleusWindowStateToV1(state)
        assertEquals(WindowPlacement.Maximized, v1.placement)
        assertTrue(v1.isMinimized)
    }

    @Test
    fun initialConversionIsIdempotent() {
        // Draining the request channels is destructive: a window that leaves and
        // re-enters composition before ever being shown must still land on the
        // geometry it asked for.
        val state = WindowStateWithBounds(initialSize = DpSize(640.dp, 480.dp), initiallyMinimized = true)
        val first = nucleusWindowStateToV1(state)
        val second = nucleusWindowStateToV1(state)
        assertEquals(first.size, second.size)
        assertEquals(first.position, second.position)
        assertEquals(DpSize(640.dp, 480.dp), second.size)
        assertTrue(second.isMinimized)
    }

    @Test
    fun screenProviderPicksAnAttachedScreen() {
        val scope = screenScope(window = null)
        val target = scope.screens.last()
        val state = WindowState(initialScreenProvider = WindowScreenProvider.ById(target.id))
        // The screen only shows up in the conversion through the geometry it
        // constrains, so assert on the provider itself as well.
        assertEquals(target, scope.evaluateScreen(WindowScreenProvider.ById(target.id)))
        assertNotNull(nucleusWindowStateToV1(state))
    }

    @Test
    fun unknownScreenIdFallsBackToTheDefaultScreen() {
        val scope = screenScope(window = null)
        assertEquals(scope.defaultScreen, scope.evaluateScreen(WindowScreenProvider.ById("no-such-display")))
    }

    @Test
    fun screenInsetsMatchTheWorkArea() {
        val screen = screenScope(window = null).defaultScreen
        assertEquals(screen.availableBounds.left - screen.bounds.left, screen.insets.left)
        assertEquals(screen.bounds.bottom - screen.availableBounds.bottom, screen.insets.bottom)
    }

    @Test
    fun dialogStateResolvesItsOwnProviders() {
        val state = DialogState()
        state.requestSize(DpSize(500.dp, 400.dp))
        assertEquals(DpSize(500.dp, 400.dp), nucleusDialogStateToV1(state).size)
    }

    @Test
    fun uninitializedStateRefusesToReportGeometry() {
        val state = WindowState()
        assertFailsWith<IllegalStateException> { state.bounds }
        assertFailsWith<IllegalStateException> { state.screenId }
        assertFailsWith<IllegalStateException> { state.placement }
    }

    @Test
    fun absoluteProviderRejectsUnspecifiedBounds() {
        assertFailsWith<IllegalArgumentException> {
            WindowBoundsProvider.Absolute(DpRect(Dp.Unspecified, 0.dp, 100.dp, 100.dp))
        }
        assertFailsWith<IllegalArgumentException> {
            WindowSizeProvider.Fixed(DpSize.Unspecified)
        }
    }

    @Test
    fun windowStateSaverRoundTripsAnInitializedState() {
        val state = WindowState()
        state.isInitialized = true
        state.screenIdOrNull = "display-1"
        state.placementOrNull = WindowPlacement.Maximized
        state.minimizedOrNull = false
        state.boundsOrNull = DpRect(10.dp, 20.dp, 810.dp, 620.dp)

        val saved = with(WindowState.Saver) { AlwaysSaveScope.save(state) }
        val restored = assertNotNull(WindowState.Saver.restore(assertNotNull(saved)))
        assertEquals("display-1", restored.screenId)
        assertEquals(WindowPlacement.Maximized, restored.placement)
        assertEquals(DpRect(10.dp, 20.dp, 810.dp, 620.dp), restored.bounds)
    }

    @Test
    fun windowStateSaverDropsAnUninitializedState() {
        // Nothing observed yet, nothing to persist: listSaver turns the empty
        // list into "no saved value", so the state is rebuilt from its initial
        // providers on restore.
        assertNull(with(WindowState.Saver) { AlwaysSaveScope.save(WindowState()) })
    }

    @Test
    fun dialogStateSaverRoundTrips() {
        val state = DialogState()
        state.isInitialized = true
        state.screenIdOrNull = "display-2"
        state.boundsOrNull = DpRect(1.dp, 2.dp, 3.dp, 4.dp)
        val saved = with(DialogState.Saver) { AlwaysSaveScope.save(state) }
        val restored = assertNotNull(DialogState.Saver.restore(assertNotNull(saved)))
        assertEquals("display-2", restored.screenId)
        assertEquals(DpRect(1.dp, 2.dp, 3.dp, 4.dp), restored.bounds)
    }

    private object AlwaysSaveScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }
}

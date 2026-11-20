@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every Tao window used to resolve `LocalViewModelStoreOwner` and
 * `LocalLifecycleOwner` to Compose's one process-wide fallback owner: no
 * `PlatformContext` overrode `architectureComponentsOwner`. Two windows calling
 * `viewModel { }` got the same instance, a closed window's view models were
 * never cleared, and every lifecycle sat at `RESUMED` for good.
 *
 * Exercised at the plumbing level, like [TaoSceneOuterLocalsBridgeTest]: two
 * real `CanvasLayersComposeScene`s stand in for two windows, their platform
 * contexts wired the way the three hosts wire theirs.
 */
class TaoArchitectureComponentsTest {
    @Test
    fun `two scenes without their own owner share one view model`() {
        // The bug, reproduced: the fallback the hosts used to leave in place.
        // That fallback is process-wide, so empty its store afterwards rather
        // than leave this test's view model to whichever test runs next.
        var fallback: ViewModelStoreOwner? = null
        try {
            val first = viewModelOfScene(architectureComponents = null) { fallback = it }
            val second = viewModelOfScene(architectureComponents = null)
            assertSame(first, second)
        } finally {
            fallback?.viewModelStore?.clear()
        }
    }

    @Test
    fun `two scenes with their own owners get their own view models`() {
        val first = viewModelOfScene(attachedComponents())
        val second = viewModelOfScene(attachedComponents())
        assertNotSame(first, second)
    }

    @Test
    fun `scene resolves the view-model store and lifecycle owners to its window's`() {
        val components = attachedComponents()
        var storeOwner: ViewModelStoreOwner? = null
        var lifecycleOwner: LifecycleOwner? = null
        runTaoSceneTest(architectureComponents = components) {
            setContent {
                storeOwner = LocalViewModelStoreOwner.current
                lifecycleOwner = LocalLifecycleOwner.current
            }
            frame()
        }
        assertSame(components.owner, storeOwner)
        assertSame(components.owner, lifecycleOwner)
    }

    @Test
    fun `lifecycle follows attach, focus, minimize and detach`() {
        val components = TaoArchitectureComponents(enforceMainThread = false)
        val lifecycle = components.owner.lifecycle
        assertEquals(Lifecycle.State.INITIALIZED, lifecycle.currentState)

        components.onAttached(focused = true, minimized = false)
        assertEquals(Lifecycle.State.RESUMED, lifecycle.currentState)

        components.onFocusChanged(false)
        assertEquals(Lifecycle.State.STARTED, lifecycle.currentState)

        components.onMinimizedChanged(true)
        assertEquals(Lifecycle.State.CREATED, lifecycle.currentState)

        components.onFocusChanged(true)
        assertEquals(Lifecycle.State.CREATED, lifecycle.currentState, "a minimized window is not resumed")

        components.onMinimizedChanged(false)
        assertEquals(Lifecycle.State.RESUMED, lifecycle.currentState)

        components.onDetaching()
        assertEquals(Lifecycle.State.CREATED, lifecycle.currentState, "stopped before the scene closes")

        components.onFocusChanged(true)
        components.onMinimizedChanged(false)
        assertEquals(Lifecycle.State.CREATED, lifecycle.currentState, "a closing window does not resume")

        components.onDetached()
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.currentState)

        // Late events after the window is gone are ignored, not thrown.
        components.onFocusChanged(false)
        components.onMinimizedChanged(true)
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.currentState)
    }

    @Test
    fun `detach clears the window's view models`() {
        val components = attachedComponents()
        val viewModel = viewModelOfScene(components)
        components.onDetached()
        assertTrue(viewModel.cleared)
    }

    @Test
    fun `detaching stops the window but keeps its view models`() {
        // The hosts call onDetaching before closing the scene and onDetached
        // after, so content disposed with the scene still finds its view models.
        val components = attachedComponents()
        val viewModel = viewModelOfScene(components)
        components.onDetaching()
        assertFalse(viewModel.cleared)
    }

    @Test
    fun `a window destroyed before it was attached does not throw`() {
        val components = TaoArchitectureComponents(enforceMainThread = false)
        components.onDetached()
        assertEquals(Lifecycle.State.DESTROYED, components.owner.lifecycle.currentState)
    }

    private fun attachedComponents(): TaoArchitectureComponents =
        TaoArchitectureComponents(enforceMainThread = false).apply { onAttached(focused = true, minimized = false) }

    private fun viewModelOfScene(
        architectureComponents: TaoArchitectureComponents?,
        onStoreOwner: (ViewModelStoreOwner) -> Unit = {},
    ): RecordingViewModel {
        var result: RecordingViewModel? = null
        runTaoSceneTest(architectureComponents = architectureComponents) {
            setContent {
                LocalViewModelStoreOwner.current?.let(onStoreOwner)
                result = viewModel { RecordingViewModel() }
            }
            frame()
        }
        return checkNotNull(result) { "the scene never composed" }
    }

    private class RecordingViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }
}

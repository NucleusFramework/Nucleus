@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nucleusframework.window.tao.dispatch.LifecycleMainDispatcherPriming
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    @BeforeTest
    fun primeLifecycleMainThread() {
        // The hosts run on the Tao main thread, which TaoApplication primes;
        // here the test thread plays that part.
        LifecycleMainDispatcherPriming.primeWithCurrentThread()
    }

    @Test
    fun `two scenes without their own owner share one view model`() {
        // The bug, reproduced: the fallback the hosts used to leave in place.
        val first = viewModelOfScene(architectureComponents = null)
        val second = viewModelOfScene(architectureComponents = null)
        assertSame(first, second)
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
        val components = TaoArchitectureComponents()
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
    fun `a window destroyed before it was attached does not throw`() {
        val components = TaoArchitectureComponents()
        components.onDetached()
        assertEquals(Lifecycle.State.DESTROYED, components.owner.lifecycle.currentState)
    }

    private fun attachedComponents(): TaoArchitectureComponents =
        TaoArchitectureComponents().apply { onAttached(focused = true, minimized = false) }

    private fun viewModelOfScene(architectureComponents: TaoArchitectureComponents?): RecordingViewModel {
        var result: RecordingViewModel? = null
        runTaoSceneTest(architectureComponents = architectureComponents) {
            setContent { result = viewModel { RecordingViewModel() } }
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

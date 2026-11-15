package dev.nucleusframework.window.tao.workspace

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TabWorkspace
import dev.nucleusframework.window.tao.scene.TaoArchitectureComponents
import dev.nucleusframework.window.tao.scene.runTaoSceneTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * View models of relocatable content (a tab, a satellite panel) live in its
 * [RelocatableSlot], not in the window composing it: they follow the content
 * from one window to the next, as its `rememberSaveable` values do, and are
 * cleared only when the content is gone for good.
 *
 * Two real scenes, each with its own window owner ([TaoArchitectureComponents]),
 * stand in for the two windows a tab moves between.
 */
class RelocatedViewModelStoreTest {
    @Test
    fun `a view model follows its content to another window`() {
        val slot = RelocatableSlot()
        val inFirstWindow = viewModelIn(slot, attachedWindow())
        val inSecondWindow = viewModelIn(slot, attachedWindow())
        assertSame(inFirstWindow, inSecondWindow)
    }

    @Test
    fun `two pieces of content get their own view model of the same class`() {
        val window = attachedWindow()
        assertNotSame(viewModelIn(RelocatableSlot(), window), viewModelIn(RelocatableSlot(), window))
    }

    @Test
    fun `closing the window a tab left does not clear its view model`() {
        val slot = RelocatableSlot()
        val firstWindow = attachedWindow()
        val viewModel = viewModelIn(slot, firstWindow)
        viewModelIn(slot, attachedWindow())
        firstWindow.onDetached()
        assertFalse(viewModel.cleared)
    }

    @Test
    fun `clearing the slot clears its view models`() {
        val slot = RelocatableSlot()
        val viewModel = viewModelIn(slot, attachedWindow())
        slot.clear()
        assertTrue(viewModel.cleared)
    }

    @Test
    fun `closing a tab clears its view models`() {
        val workspace = TabWorkspace()
        val entry = workspace.register(id = "editor", title = "Editor", groupId = null)
        val viewModel = viewModelIn(entry.stateSlot, attachedWindow())
        workspace.close("editor")
        assertTrue(viewModel.cleared)
    }

    @Test
    fun `a tab whose declaration leaves has its view models cleared`() {
        val workspace = TabWorkspace()
        val entry = workspace.register(id = "editor", title = "Editor", groupId = null)
        val viewModel = viewModelIn(entry.stateSlot, attachedWindow())
        workspace.unregister(entry)
        assertTrue(viewModel.cleared)
    }

    @Test
    fun `a satellite whose declaration leaves has its view models cleared`() {
        val workspace = SatelliteWorkspace()
        val entry = workspace.register("tools", "Tools", SatellitePlacement.Floating(), initiallyOpen = true)
        val viewModel = viewModelIn(entry.stateSlot, attachedWindow())
        workspace.unregister(entry)
        assertTrue(viewModel.cleared)
    }

    @Test
    fun `a clear while the content is on screen waits until it is disposed`() {
        val slot = RelocatableSlot()
        var shown by mutableStateOf(true)
        var viewModel: RecordingViewModel? = null
        var clearedWhenDisposed: Boolean? = null
        runTaoSceneTest(architectureComponents = attachedWindow()) {
            setContent {
                if (shown) {
                    RelocatedContentHost(slot, Unit) {
                        val vm = viewModel { RecordingViewModel() }
                        viewModel = vm
                        DisposableEffect(vm) { onDispose { clearedWhenDisposed = vm.cleared } }
                    }
                }
            }
            frame()
            slot.clear()
            assertFalse(checkNotNull(viewModel).cleared, "cleared while still composed")
            shown = false
            frame()
        }
        assertEquals(false, clearedWhenDisposed, "the content's onDispose saw a cleared view model")
        assertTrue(checkNotNull(viewModel).cleared)
    }

    @Test
    fun `a content declared again before its pending clear keeps its view models`() {
        val slot = RelocatableSlot()
        var viewModel: RecordingViewModel? = null
        runTaoSceneTest(architectureComponents = attachedWindow()) {
            setContent { RelocatedContentHost(slot, Unit) { viewModel = viewModel { RecordingViewModel() } } }
            frame()
            slot.clear()
            slot.cancelPendingClear()
        }
        assertFalse(checkNotNull(viewModel).cleared)
    }

    @Test
    fun `a saved-state handle follows its content to another window`() {
        val slot = RelocatableSlot()
        val inFirstWindow = handleViewModelIn(slot, attachedWindow())
        inFirstWindow.handle["draft"] = "hello"
        val inSecondWindow = handleViewModelIn(slot, attachedWindow())
        assertSame(inFirstWindow, inSecondWindow)
        assertEquals("hello", inSecondWindow.handle.get<String>("draft"))
    }

    private fun handleViewModelIn(
        slot: RelocatableSlot,
        window: TaoArchitectureComponents,
    ): HandleViewModel {
        var result: HandleViewModel? = null
        runTaoSceneTest(architectureComponents = window) {
            setContent {
                RelocatedContentHost(slot, Unit) { result = viewModel { HandleViewModel(createSavedStateHandle()) } }
            }
            frame()
        }
        return checkNotNull(result) { "the content never composed" }
    }

    private fun attachedWindow(): TaoArchitectureComponents =
        TaoArchitectureComponents(enforceMainThread = false).apply { onAttached(focused = true, minimized = false) }

    private fun viewModelIn(
        slot: RelocatableSlot,
        window: TaoArchitectureComponents,
    ): RecordingViewModel {
        var result: RecordingViewModel? = null
        runTaoSceneTest(architectureComponents = window) {
            setContent {
                RelocatedContentHost(slot, Unit) { result = viewModel { RecordingViewModel() } }
            }
            frame()
        }
        return checkNotNull(result) { "the content never composed" }
    }

    private class RecordingViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private class HandleViewModel(
        val handle: androidx.lifecycle.SavedStateHandle,
    ) : ViewModel()
}

package dev.nucleusframework.window.tao.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nucleusframework.window.tao.TabWorkspace
import dev.nucleusframework.window.tao.scene.TaoArchitectureComponents
import dev.nucleusframework.window.tao.scene.runTaoSceneTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `disposing a workspace clears every tab's view models`() {
        val workspace = TabWorkspace()
        val first = viewModelIn(workspace.register("a", "A", null).stateSlot, attachedWindow())
        val second = viewModelIn(workspace.register("b", "B", null).stateSlot, attachedWindow())
        workspace.dispose()
        assertTrue(first.cleared)
        assertTrue(second.cleared)
    }

    @Test
    fun `a saved-state handle works inside relocatable content`() {
        var handleViewModel: HandleViewModel? = null
        runTaoSceneTest(architectureComponents = attachedWindow()) {
            setContent {
                RelocatedContentHost(RelocatableSlot(), Unit) {
                    handleViewModel = viewModel { HandleViewModel(createSavedStateHandle()) }
                }
            }
            frame()
        }
        assertNotNull(handleViewModel)
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
        @Suppress("unused") val handle: androidx.lifecycle.SavedStateHandle,
    ) : ViewModel()
}

package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformArchitectureComponentsOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.enableSavedStateHandles

/**
 * One window's [PlatformArchitectureComponentsOwner]: the `ViewModelStoreOwner`,
 * `LifecycleOwner`, `SavedStateRegistryOwner` and back-navigation dispatcher
 * that `LocalViewModelStoreOwner`, `LocalLifecycleOwner` and
 * `LocalSavedStateRegistryOwner` resolve to inside its scene.
 *
 * A scene whose `PlatformContext` does not override
 * `architectureComponentsOwner` gets Compose's single process-wide owner,
 * forever `RESUMED`. Every window then shares one `ViewModelStore`, so
 * `viewModel { }` returns another window's instance, and a view model is never
 * cleared when its window closes. Compose Desktop avoids that by giving each
 * `ComposeContainer` its own owner; this is the Tao counterpart, with the same
 * lifecycle rules (`ComposeContainer.updateLifecycleState`):
 *
 * - `CREATED` before [onAttached] and while minimized,
 * - `RESUMED` while focused, `STARTED` otherwise,
 * - `DESTROYED` after [onDetached], which clears the view models.
 *
 * Popup layers and standalone popups need nothing of their own: they replay
 * their owner window's composition locals, so they share its owner, as the
 * popup layers of a `ComposeContainer` do.
 *
 * Threading: every method must run on the Tao main thread, which the
 * lifecycle main-thread check recognises (see `LifecycleMainDispatcherPriming`).
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoArchitectureComponents {
    val owner: DefaultArchitectureComponentsOwner =
        DefaultArchitectureComponentsOwner().apply { enableSavedStateHandles() }

    private var isAttached = false
    private var isDetached = false
    private var isMinimized = false
    private var isFocused = false

    /** The window is on screen and its scene is live. */
    fun onAttached(
        focused: Boolean,
        minimized: Boolean,
    ) {
        isAttached = true
        isFocused = focused
        isMinimized = minimized
        updateLifecycleState()
    }

    /** The window is gone: moves to `DESTROYED`, which clears its view models. Terminal. */
    fun onDetached() {
        isDetached = true
        updateLifecycleState()
    }

    fun onFocusChanged(focused: Boolean) {
        isFocused = focused
        updateLifecycleState()
    }

    fun onMinimizedChanged(minimized: Boolean) {
        isMinimized = minimized
        updateLifecycleState()
    }

    private fun updateLifecycleState() {
        // DESTROYED is terminal: a late focus or minimize event must not try to
        // move a destroyed lifecycle, which LifecycleRegistry rejects.
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        // LifecycleRegistry refuses INITIALIZED -> DESTROYED, the path of a
        // window destroyed before it was ever attached.
        if (isDetached && owner.lifecycle.currentState == Lifecycle.State.INITIALIZED) {
            owner.setLifecycleState(Lifecycle.State.CREATED)
        }
        owner.setLifecycleState(
            when {
                isDetached -> Lifecycle.State.DESTROYED
                !isAttached || isMinimized -> Lifecycle.State.CREATED
                isFocused -> Lifecycle.State.RESUMED
                else -> Lifecycle.State.STARTED
            },
        )
    }
}

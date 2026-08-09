package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import dev.nucleusframework.window.tao.scene.LocalTaoGlTextureHost
import dev.nucleusframework.window.tao.scene.LocalTaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.LocalTaoWindowsTextureHost
import dev.nucleusframework.window.tao.scene.TaoGlTextureHost
import dev.nucleusframework.window.tao.scene.TaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.TaoWindowsTextureHost
import org.jetbrains.skia.DirectContext

/**
 * The graphics backend a [TaoGpuRenderContext] draws with.
 */
public enum class TaoRenderBackend {
    /** Metal — macOS. */
    METAL,

    /**
     * OpenGL (ES) — Linux (native EGL/GLES) and Windows (ANGLE-on-Direct3D-11
     * GLES). On Windows the context is *not* a desktop OpenGL context: it is
     * ANGLE's ES 3 context translated to D3D11, and its entry points must be
     * resolved through ANGLE's `eglGetProcAddress` (the same way the scene's
     * own Skia interface is assembled).
     */
    OPENGL,
}

/**
 * The GPU render context of the enclosing Compose surface: the Skia
 * [DirectContext] the scene is drawn with plus, per backend, the native device
 * it renders on — published so an in-process renderer (a map engine, a 3D
 * viewport, a charting engine) can allocate its render target **on the
 * window's own device** and let Compose sample it directly: no second GPU
 * device, no shareable allocation, no per-frame cross-device copy.
 *
 * This is the complement of `TextureView`'s import sources, not a replacement:
 * `TextureView` remains the contract for **foreign** producers (out-of-process
 * renderers, hardware video decoders on their own device), while this API is
 * for renderers willing to create their Skia/GPU objects on the very context
 * that paints the scene.
 *
 * Obtain it with [rememberTaoGpuRenderContext]; the concrete subtype
 * ([TaoMetalRenderContext] or [TaoOpenGlRenderContext]) tells the backend
 * apart, as does [backend].
 *
 * ### Ownership and lifetime
 *
 * Every handle exposed here is **borrowed**. Consumers must never retain,
 * release or free them, and must stop using them the moment the surface
 * publishes a different context (or none). [rememberTaoGpuRenderContext]
 * returns a **new instance** whenever the underlying context is rebuilt —
 * window detach, a Wayland hide/show cycle (which rebuilds the whole EGL
 * stack), popup or tray-panel teardown — so keying on the instance
 * (`remember(renderContext) { ... }`) is the intended way to rebuild
 * renderer-side state, and a `DisposableEffect(renderContext)` the intended
 * place to free it (inside [runOnGpuThread] / `withContextCurrent`).
 *
 * ### Threading
 *
 * Every member must be called from the thread the composition runs on (the Tao
 * event-loop thread; on macOS, the process main thread). GPU work goes through
 * [runOnGpuThread] — see its contract.
 */
@Stable
public sealed interface TaoGpuRenderContext {
    /** Which backend this context draws with. Matches the runtime subtype. */
    public val backend: TaoRenderBackend

    /**
     * The Skia context the enclosing surface is drawn with. GPU resources
     * created against it (a `BackendRenderTarget`, an adopted texture) are
     * sampled by the scene with no import step. Only touch it from
     * [runOnGpuThread] (Metal) or `withContextCurrent` (OpenGL).
     */
    public val skiaContext: DirectContext

    /**
     * Runs [action] **synchronously** with exclusive access to [skiaContext]
     * — never overlapping the scene replaying a frame — and returns its
     * result, propagating whatever it throws.
     *
     * Must be called from the composition thread. On macOS the action hops to
     * the render thread that owns the Skia Metal context and blocks until it
     * returns; that is safe from composition, layout/draw and disposal because
     * the render thread is idle at those points. On Linux and Windows
     * everything already runs on the event-loop thread, so the action runs
     * inline (after the same thread check).
     */
    public fun <T> runOnGpuThread(action: () -> T): T
}

/**
 * The Metal render context of a macOS surface.
 *
 * [metalDevicePtr] is the `id<MTLDevice>` the scene renders with; a renderer
 * allocates its textures and command queues on it and hands Skia wrappers of
 * them to [skiaContext][TaoGpuRenderContext.skiaContext]. Borrowed — never
 * retain or release it (no ARC bridging that transfers ownership).
 */
@Stable
public sealed interface TaoMetalRenderContext : TaoGpuRenderContext {
    /** Raw `id<MTLDevice>` pointer the scene's Skia context renders with. */
    public val metalDevicePtr: Long
}

/**
 * The OpenGL (ES) render context of a Linux or Windows surface.
 *
 * GL state is bound to whichever context is current on the calling thread, so
 * instead of a raw context handle this carries [withContextCurrent].
 */
@Stable
public sealed interface TaoOpenGlRenderContext : TaoGpuRenderContext {
    /**
     * Runs [action] with this surface's GL context current on the calling
     * thread and returns its result — or `null` when the context could not be
     * bound (the surface is being torn down). Safe to nest, and safe to call
     * from composition, layout/draw and disposal; must be called from the
     * composition thread.
     *
     * Whatever was current before is put back afterwards, so a disposal
     * running inside *another* surface's render pass cannot corrupt that
     * surface's frame. After [action] returns, Skia's GL state cache is
     * invalidated (`resetGLAll`) — the action's GL calls happened behind
     * Skia's back — so consumers don't need to.
     */
    public fun <T> withContextCurrent(action: () -> T): T?
}

/**
 * The [TaoGpuRenderContext] of the enclosing Compose surface, or `null` while
 * the surface's GPU context does not exist (bring-up, teardown, or a
 * non-hardware environment).
 *
 * Resolved per surface: inside a `DecoratedWindow` this is the window scene's
 * context; inside a native popup layer, a `NativeView` overlay or a
 * `TaoStandalonePopup` tray panel it is that surface's own context on
 * platforms where surfaces own private contexts (macOS, Linux), and the shared
 * window context on Windows.
 *
 * The returned instance is **identity-stable** for the lifetime of the
 * underlying GPU context and is replaced (a fresh instance, triggering
 * recomposition) whenever that context is rebuilt — see the lifetime contract
 * on [TaoGpuRenderContext].
 */
@Composable
public fun rememberTaoGpuRenderContext(): TaoGpuRenderContext? {
    val metalHost = LocalTaoMetalTextureHost.current
    val glHost = LocalTaoGlTextureHost.current
    val windowsHost = LocalTaoWindowsTextureHost.current
    val context =
        remember(metalHost, glHost, windowsHost) {
            when {
                metalHost != null -> MetalRenderContext(metalHost)
                glHost != null -> LinuxOpenGlRenderContext(glHost)
                windowsHost != null -> WindowsOpenGlRenderContext(windowsHost)
                else -> null
            }
        }
    if (context != null) {
        // Composition-lifetime consumer mark: the Windows host keeps VSync
        // on through the OS modal resize/move loop while its DirectContext
        // has GPU-context consumers (#484). A RememberObserver so an
        // abandoned composition can never leak the mark.
        remember(context) { TaoGpuRenderContextLease(context.skiaContext) }
    }
    return context
}

/**
 * Ledger of the [DirectContext]s whose composition currently holds a
 * [TaoGpuRenderContext]. Such a consumer typically drives per-frame GPU work
 * off `withFrameNanos`; the Windows host consults this (together with the
 * `TextureView` import ledger) to keep VSync on through the OS modal
 * resize/move loop, where an unpaced frame clock would otherwise free-run at
 * event-pump speed (#484).
 */
internal object TaoGpuRenderContextConsumers {
    private val counts = java.util.concurrent.ConcurrentHashMap<DirectContext, Int>()

    fun retain(context: DirectContext) {
        counts.merge(context, 1, Int::plus)
    }

    fun release(context: DirectContext) {
        counts.computeIfPresent(context) { _, n -> if (n <= 1) null else n - 1 }
    }

    /** Whether [context]'s composition holds at least one live consumer. */
    fun isActive(context: DirectContext): Boolean = counts.containsKey(context)
}

/**
 * Marks a [DirectContext] as having a live [TaoGpuRenderContext] consumer for
 * exactly as long as the `remember` that produced the context stays in the
 * composition. `onAbandoned` covers compositions computed but never applied.
 */
private class TaoGpuRenderContextLease(
    private val context: DirectContext,
) : androidx.compose.runtime.RememberObserver {
    private var retained = false

    override fun onRemembered() {
        retained = true
        TaoGpuRenderContextConsumers.retain(context)
    }

    override fun onForgotten() {
        if (!retained) return
        retained = false
        TaoGpuRenderContextConsumers.release(context)
    }

    override fun onAbandoned(): Unit = onForgotten()
}

/**
 * Base of the concrete contexts: pins the thread the context was created on
 * (the composition thread of its surface) and rejects calls from any other —
 * a dispatch would be unsafe to fake (the event loop may be blocked on the
 * caller), so misuse fails fast instead of deadlocking or racing.
 */
private abstract class ThreadPinnedRenderContext : TaoGpuRenderContext {
    private val owningThread: Thread = Thread.currentThread()

    protected fun checkThread() {
        check(Thread.currentThread() === owningThread) {
            "TaoGpuRenderContext used from thread '${Thread.currentThread().name}', " +
                "but it belongs to '${owningThread.name}' (the surface's composition thread)"
        }
    }
}

private class MetalRenderContext(
    private val host: TaoMetalTextureHost,
) : ThreadPinnedRenderContext(),
    TaoMetalRenderContext {
    override val backend: TaoRenderBackend get() = TaoRenderBackend.METAL
    override val skiaContext: DirectContext get() = host.directContext
    override val metalDevicePtr: Long get() = host.metalDevicePtr

    override fun <T> runOnGpuThread(action: () -> T): T {
        checkThread()
        return host.runOnRenderThread(action)
    }
}

private class LinuxOpenGlRenderContext(
    private val host: TaoGlTextureHost,
) : ThreadPinnedRenderContext(),
    TaoOpenGlRenderContext {
    override val backend: TaoRenderBackend get() = TaoRenderBackend.OPENGL
    override val skiaContext: DirectContext get() = host.directContext

    override fun <T> runOnGpuThread(action: () -> T): T {
        checkThread()
        return action()
    }

    override fun <T> withContextCurrent(action: () -> T): T? {
        checkThread()
        return host.withContextCurrent {
            try {
                action()
            } finally {
                // The action's GL calls changed state behind Skia's back; the
                // reset must land before this surface's next flushAndSubmit.
                host.directContext.resetGLAll()
            }
        }
    }
}

private class WindowsOpenGlRenderContext(
    private val host: TaoWindowsTextureHost,
) : ThreadPinnedRenderContext(),
    TaoOpenGlRenderContext {
    override val backend: TaoRenderBackend get() = TaoRenderBackend.OPENGL
    override val skiaContext: DirectContext get() = host.directContext

    override fun <T> runOnGpuThread(action: () -> T): T {
        checkThread()
        return action()
    }

    override fun <T> withContextCurrent(action: () -> T): T? {
        checkThread()
        return host.withContextCurrent {
            try {
                action()
            } finally {
                // Same protocol as the TextureView import path: reset now, not
                // at the next frame entry, because this can run from inside
                // ComposeScene.render() and the stale cache would be consumed
                // by this very frame's flushAndSubmit.
                host.markGlStateDirtied()
            }
        }
    }
}

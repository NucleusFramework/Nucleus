package io.github.kdroidfilter.nucleus.window.tao.render

/**
 * Mirrors `UIKitInteropTransaction` from Compose Multiplatform iOS.
 *
 * Mutations to AppKit subviews embedded via `NativeView` (frame, corner
 * radius, add/remove, overlay reposition) are not applied immediately —
 * they're enqueued as lambdas into a [MutableTaoInteropTransaction]
 * owned by `TaoComposeSceneHost`. The host drains the queue once per
 * frame **after** Skia/Metal has rendered but **inside** an explicit
 * `CATransaction` that also carries the drawable present. Result: the
 * Compose pixels and the AppKit subview position commit to the screen
 * as a single visual update — no one-frame mismatch during scroll,
 * resize, or animation.
 *
 * This is the macOS port of the iOS sync model: same queue, same
 * `presentsWithTransaction` + `waitUntilScheduled` + `[drawable present]`
 * sequencing, just delivered via JNI from Kotlin/JVM rather than
 * Kotlin/Native.
 */
internal typealias TaoInteropAction = () -> Unit

internal interface TaoInteropTransaction {
    val actions: List<TaoInteropAction>

    /**
     * `true` when at least one `NativeView` is currently mounted. Used
     * by the host to decide whether to flip
     * `CAMetalLayer.presentsWithTransaction` ON (tracking embedded
     * AppKit subviews) or back OFF (purely Compose content, fast async
     * present).
     */
    val isInteropActive: Boolean

    fun performTransaction() {
        for (action in actions) action()
    }
}

internal class MutableTaoInteropTransaction(
    override var isInteropActive: Boolean,
) : TaoInteropTransaction {
    private val _actions = ArrayList<TaoInteropAction>()
    override val actions: List<TaoInteropAction> get() = _actions
    fun add(action: TaoInteropAction) { _actions.add(action) }
}

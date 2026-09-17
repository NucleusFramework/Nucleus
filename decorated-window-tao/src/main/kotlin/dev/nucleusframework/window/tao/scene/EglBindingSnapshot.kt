package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTextureBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoTextureBridge

/**
 * The per-thread EGL binding snapshot both EGL backends expose, behind one
 * contract. Used by code that binds a surface of its own from a path that can
 * run inside **another** surface's render pass — a tray panel's bring-up, render
 * and teardown all arrive from the caller's composition, i.e. from inside the
 * window scene's `ComposeScene.render()`.
 *
 * The contract, identical on both platforms:
 * - [save] snapshots what is current and returns false when a snapshot is
 *   already outstanding on this thread. Nesting is refused rather than stacked:
 *   a second snapshot would overwrite the outer one, and the outer scope is the
 *   one that owns putting the binding back.
 * - [restore] consumes the snapshot. It returns false when nothing was current
 *   at [save] time — what happens to the binding the caller made in between is
 *   then the platform's business (see the two implementations).
 *
 * Single-slot and *not* thread-local on Windows: every entry point of that
 * bridge is documented event-loop-thread only, and `__declspec(thread)` needs
 * the CRT's `_tls_used`, which `/NODEFAULTLIB` drops. Linux uses a real
 * `__thread` slot, since its test producer reaches the bridge from its own
 * thread.
 */
internal interface EglBindingSnapshot {
    /** Whether the native bridge backing the snapshot is loaded at all. */
    val isAvailable: Boolean

    /** Snapshots the current binding; false when one is already outstanding. */
    fun save(): Boolean

    /** Puts the snapshot back; false when nothing was current when it was taken. */
    fun restore(): Boolean
}

/**
 * Linux: `restore` leaves the caller's own context current when nothing was
 * displaced, so [withEglContextCurrent] unbinds explicitly in that case —
 * `eglMakeCurrent` needs a display even to unbind, and the attachment is what
 * knows one.
 */
internal object LinuxEglBindingSnapshot : EglBindingSnapshot {
    override val isAvailable: Boolean get() = NativeTaoLinuxTextureBridge.isLoaded

    override fun save(): Boolean = NativeTaoLinuxTextureBridge.nativeSaveCurrentBinding()

    override fun restore(): Boolean = NativeTaoLinuxTextureBridge.nativeRestoreBinding()
}

/**
 * Windows: `restore` unbinds natively when nothing was displaced — the process
 * EGL display is reachable from the bridge, so it needs no help, and no caller
 * has to deal with the case.
 */
internal object WindowsEglBindingSnapshot : EglBindingSnapshot {
    override val isAvailable: Boolean get() = NativeTaoTextureBridge.isLoaded

    override fun save(): Boolean = NativeTaoTextureBridge.nativeSaveCurrentBinding()

    override fun restore(): Boolean = NativeTaoTextureBridge.nativeRestoreBinding()
}

/**
 * Runs [block] and puts back the EGL binding it displaced, using [snapshot].
 *
 * Degrades to running [block] unwrapped when the native bridge is missing, and
 * when a snapshot is already outstanding on this thread — the enclosing scope
 * restores the binding when it unwinds, which is the same guarantee.
 *
 * The platform wrappers ([preservingEglBinding], [preservingAngleBinding]) are
 * the entry points; they exist separately so each can document what its backend
 * actually protects.
 */
internal inline fun <T> preservingBinding(
    snapshot: EglBindingSnapshot,
    block: () -> T,
): T {
    if (!snapshot.isAvailable) return block()
    if (!snapshot.save()) return block()
    return try {
        block()
    } finally {
        snapshot.restore()
    }
}

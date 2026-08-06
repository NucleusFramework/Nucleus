package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Borrowed [xdg_foreign](https://wayland.app/protocols/xdg-foreign-unstable-v2)
 * export of a Tao window's Wayland surface.
 *
 * The [handle] is the **unprefixed** opaque token issued by the compositor.
 * XDG Desktop Portal (and FileKit) want it as `wayland:<handle>` — see
 * [portalParent]. Keep this export open until every portal dialog that uses it
 * has completed; [close] unexports the surface.
 *
 * Not a raw `wl_surface*` pointer and not a Tao event-loop identity.
 */
public class XdgForeignExport internal constructor(
    /** Unprefixed opaque xdg_foreign token. */
    public val handle: String,
    private val windowHandle: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /**
     * Full XDG Desktop Portal `parent_window` value:
     * `wayland:<unprefixed-handle>`.
     */
    public val portalParent: String
        get() = "wayland:$handle"

    public val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            if (NativeTaoBridge.isLoaded) {
                NativeTaoBridge.nativeLinuxUnexportXdgForeignHandle(windowHandle)
            }
        }
    }

    override fun toString(): String = "XdgForeignExport(closed=$isClosed)"
}

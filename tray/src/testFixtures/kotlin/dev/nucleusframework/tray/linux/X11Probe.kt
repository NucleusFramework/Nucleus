package dev.nucleusframework.tray.linux

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * The X server's view of this process's windows (tests only, Xlib through JNA): what is mapped on
 * screen, where, and the desktop's work area. Independent of Tao and GDK, so it can check them.
 *
 * Declared by hand with the exact C types of x86_64 Linux (XIDs are `unsigned long`); the
 * generic `jna-platform` X11 mapping got `XGetWindowProperty` refused with BadRequest here.
 */
public object X11Probe {
    data class Box(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
        val centerX: Int get() = x + width / 2

        override fun toString(): String = "[$x,$y ${width}x$height]"
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface Xlib : Library {
        fun XOpenDisplay(name: String?): Pointer?

        fun XDefaultRootWindow(display: Pointer): Long

        fun XInternAtom(
            display: Pointer,
            name: String,
            onlyIfExists: Int,
        ): Long

        fun XQueryTree(
            display: Pointer,
            window: Long,
            root: LongByReference,
            parent: LongByReference,
            children: PointerByReference,
            count: IntByReference,
        ): Int

        fun XGetWindowAttributes(
            display: Pointer,
            window: Long,
            attributes: Pointer,
        ): Int

        fun XTranslateCoordinates(
            display: Pointer,
            src: Long,
            dst: Long,
            x: Int,
            y: Int,
            outX: IntByReference,
            outY: IntByReference,
            child: LongByReference,
        ): Int

        fun XGetWindowProperty(
            display: Pointer,
            window: Long,
            property: Long,
            offset: Long,
            length: Long,
            delete: Int,
            reqType: Long,
            actualType: LongByReference,
            actualFormat: IntByReference,
            items: LongByReference,
            bytesAfter: LongByReference,
            data: PointerByReference,
        ): Int

        fun XFree(data: Pointer): Int

        fun XGetInputFocus(
            display: Pointer,
            focus: LongByReference,
            revert: IntByReference,
        ): Int

        fun XSetInputFocus(
            display: Pointer,
            window: Long,
            revert: Int,
            time: Long,
        ): Int

        fun XChangeProperty(
            display: Pointer,
            window: Long,
            property: Long,
            type: Long,
            format: Int,
            mode: Int,
            data: ByteArray,
            count: Int,
        ): Int

        fun XSync(
            display: Pointer,
            discard: Int,
        ): Int
    }

    private val x by lazy { Native.load("X11", Xlib::class.java) }
    private val display: Pointer by lazy { x.XOpenDisplay(null) ?: error("no X display") }
    private val pid = ProcessHandle.current().pid()

    // XWindowAttributes on x86_64: width @8, height @12, map_state @92; 136 bytes in all.
    private const val ATTRS_SIZE = 136L
    private const val IS_VIEWABLE = 2
    private const val STANDALONE_POPUP = "NucleusStandalonePopup"

    val isAvailable: Boolean by lazy { System.getenv("DISPLAY") != null && runCatching { display }.isSuccess }

    /** The X window holding the input focus (0 = None, 1 = PointerRoot). */
    @Synchronized
    fun focus(): Long {
        val focus = LongByReference()
        x.XGetInputFocus(display, focus, IntByReference())
        return focus.value
    }

    /** The X ids of this process's viewable windows, same order as [ownWindows]. */
    @Synchronized
    fun ownWindowIds(): List<Long> {
        val out = ArrayList<Pair<Long, Box>>()
        visitIds(x.XDefaultRootWindow(display), 0, out)
        return out.map { it.first }
    }

    @Synchronized
    fun setFocus(window: Long) {
        x.XSetInputFocus(display, window, 2, 0)
        x.XSync(display, 0)
    }

    private fun visitIds(
        window: Long,
        depth: Int,
        out: MutableList<Pair<Long, Box>>,
    ) {
        val root = LongByReference()
        val parent = LongByReference()
        val children = PointerByReference()
        val count = IntByReference()
        if (x.XQueryTree(display, window, root, parent, children, count) == 0) return
        val ids =
            if (count.value == 0 ||
                children.value == null
            ) {
                LongArray(0)
            } else {
                children.value.getLongArray(0, count.value)
            }
        children.value?.let { x.XFree(it) }
        for (child in ids) {
            val attrs = Memory(ATTRS_SIZE)
            if (!viewable(child, attrs)) continue
            val windowPid = cardinals(child, "_NET_WM_PID")?.firstOrNull()
            val ours = windowPid == pid || (windowPid == null && text(child, "WM_NAME") == STANDALONE_POPUP)
            if (ours) {
                out += child to Box(0, 0, attrs.getInt(8), attrs.getInt(12))
            } else if (depth < 2) {
                visitIds(child, depth + 1, out)
            }
        }
    }

    /** Viewable windows of this process (by `_NET_WM_PID`, or Tao's popup name), in root coordinates. */
    @Synchronized
    fun ownWindows(): List<Box> {
        val out = ArrayList<Box>()
        visit(x.XDefaultRootWindow(display), 0, out)
        return out
    }

    private fun visit(
        window: Long,
        depth: Int,
        out: MutableList<Box>,
    ) {
        val root = LongByReference()
        val parent = LongByReference()
        val children = PointerByReference()
        val count = IntByReference()
        if (x.XQueryTree(display, window, root, parent, children, count) == 0) return
        val ids =
            if (count.value == 0 ||
                children.value == null
            ) {
                LongArray(0)
            } else {
                children.value.getLongArray(0, count.value)
            }
        children.value?.let { x.XFree(it) }
        val mapped = ids.toList().mapNotNull { id -> Memory(ATTRS_SIZE).takeIf { viewable(id, it) }?.let { id to it } }
        for ((child, attrs) in mapped) {
            // Tao's standalone popup is a raw X11 window without _NET_WM_PID: known by its name.
            val windowPid = cardinals(child, "_NET_WM_PID")?.firstOrNull()
            val ours = windowPid == pid || (windowPid == null && text(child, "WM_NAME") == STANDALONE_POPUP)
            if (ours) {
                val rx = IntByReference()
                val ry = IntByReference()
                x.XTranslateCoordinates(display, child, x.XDefaultRootWindow(display), 0, 0, rx, ry, LongByReference())
                out += Box(rx.value, ry.value, attrs.getInt(8), attrs.getInt(12))
            } else if (depth < 2) {
                // A window manager frame: the client sits one or two levels down.
                visit(child, depth + 1, out)
            }
        }
    }

    /** Reads [window]'s attributes into [attrs]; true when it is mapped and viewable. */
    private fun viewable(
        window: Long,
        attrs: Memory,
    ): Boolean {
        attrs.clear()
        return x.XGetWindowAttributes(display, window, attrs) != 0 && attrs.getInt(92) == IS_VIEWABLE
    }

    private fun cardinals(
        window: Long,
        name: String,
    ): LongArray? {
        val atom = x.XInternAtom(display, name, 1)
        if (atom == 0L) return null
        val actualType = LongByReference()
        val format = IntByReference()
        val items = LongByReference()
        val after = LongByReference()
        val data = PointerByReference()
        val r = x.XGetWindowProperty(display, window, atom, 0, 64, 0, 0, actualType, format, items, after, data)
        val ptr = data.value ?: return null
        try {
            if (r != 0 || format.value != 32 || items.value == 0L) return null
            // Format-32 properties are C longs in memory.
            return ptr.getLongArray(0, items.value.toInt())
        } finally {
            x.XFree(ptr)
        }
    }

    private fun text(
        window: Long,
        name: String,
    ): String? {
        val atom = x.XInternAtom(display, name, 1)
        if (atom == 0L) return null
        val data = PointerByReference()
        val format = IntByReference()
        val items = LongByReference()
        val r =
            x.XGetWindowProperty(
                display,
                window,
                atom,
                0,
                256,
                0,
                0,
                LongByReference(),
                format,
                items,
                LongByReference(),
                data,
            )
        val ptr = data.value ?: return null
        try {
            if (r != 0 || format.value != 8) return null
            return String(ptr.getByteArray(0, items.value.toInt()), Charsets.UTF_8)
        } finally {
            x.XFree(ptr)
        }
    }

    /**
     * Sets `Xft.dpi` in the root window's `RESOURCE_MANAGER`, as `gsd-xsettings` does on a GNOME
     * session (96 x the Xwayland scale). Only for a display the test owns.
     */
    @Synchronized
    fun setXftDpi(dpi: Int) {
        val root = x.XDefaultRootWindow(display)
        val kept =
            text(root, "RESOURCE_MANAGER")
                .orEmpty()
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("Xft.dpi:") }
        val db = (kept + "Xft.dpi:\t$dpi").joinToString("\n", postfix = "\n").toByteArray()
        val atom = x.XInternAtom(display, "RESOURCE_MANAGER", 0)
        x.XChangeProperty(display, root, atom, XA_STRING, 8, PROP_MODE_REPLACE, db, db.size)
        x.XSync(display, 0)
    }

    private const val XA_STRING = 31L
    private const val PROP_MODE_REPLACE = 0

    /** `_NET_WORKAREA` of the current desktop (screen minus panels), physical pixels. */
    @Synchronized
    fun workArea(): Box? {
        val root = x.XDefaultRootWindow(display)
        val area = cardinals(root, "_NET_WORKAREA") ?: return null
        val desktop = cardinals(root, "_NET_CURRENT_DESKTOP")?.firstOrNull()?.toInt() ?: 0
        val base = (desktop * 4).takeIf { it + 3 < area.size } ?: 0
        return Box(area[base].toInt(), area[base + 1].toInt(), area[base + 2].toInt(), area[base + 3].toInt())
    }
}

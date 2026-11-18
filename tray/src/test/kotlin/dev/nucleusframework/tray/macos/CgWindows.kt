package dev.nucleusframework.tray.macos

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * The window server's view of this process' windows (`CGWindowListCopyWindowInfo`): what is
 * really on screen, independent of AppKit's bookkeeping. Bounds are in points, top-left origin of
 * the primary display, like the status-item positions. Reading bounds of one's own windows needs
 * no Screen Recording permission.
 */
internal object CgWindows {
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

    data class Window(
        val number: Long,
        val layer: Int,
        val bounds: Box,
        val onScreen: Boolean,
        val alpha: Double,
    )

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFArrayGetCount(array: Pointer): Long

        fun CFArrayGetValueAtIndex(
            array: Pointer,
            index: Long,
        ): Pointer?

        fun CFDictionaryGetValue(
            dict: Pointer,
            key: Pointer,
        ): Pointer?

        fun CFNumberGetValue(
            number: Pointer,
            type: Long,
            out: Pointer,
        ): Boolean

        fun CFBooleanGetValue(value: Pointer): Boolean

        fun CFRelease(ref: Pointer)
    }

    @Suppress("FunctionName")
    private interface CoreGraphics : Library {
        fun CGWindowListCopyWindowInfo(
            option: Int,
            relativeToWindow: Int,
        ): Pointer?

        fun CGRectMakeWithDictionaryRepresentation(
            dict: Pointer,
            rect: CGRect,
        ): Boolean
    }

    @Structure.FieldOrder("x", "y", "width", "height")
    class CGRect : Structure() {
        @JvmField var x: Double = 0.0

        @JvmField var y: Double = 0.0

        @JvmField var width: Double = 0.0

        @JvmField var height: Double = 0.0
    }

    private const val ON_SCREEN_ONLY = 1
    private const val NUMBER_SINT64 = 4L
    private const val NUMBER_DOUBLE = 13L

    private val cf by lazy { Native.load("CoreFoundation", CoreFoundation::class.java) }
    private val cg by lazy { Native.load("CoreGraphics", CoreGraphics::class.java) }
    private val cgLib by lazy { NativeLibrary.getInstance("CoreGraphics") }

    private fun key(name: String): Pointer = cgLib.getGlobalVariableAddress(name).getPointer(0)

    private fun long(
        dict: Pointer,
        key: String,
    ): Long? {
        val value = cf.CFDictionaryGetValue(dict, key(key)) ?: return null
        val out = Memory(8)
        return if (cf.CFNumberGetValue(value, NUMBER_SINT64, out)) out.getLong(0) else null
    }

    private fun double(
        dict: Pointer,
        key: String,
    ): Double? {
        val value = cf.CFDictionaryGetValue(dict, key(key)) ?: return null
        val out = Memory(8)
        return if (cf.CFNumberGetValue(value, NUMBER_DOUBLE, out)) out.getDouble(0) else null
    }

    private fun window(
        dict: Pointer,
        pid: Long,
    ): Window? {
        if (long(dict, "kCGWindowOwnerPID") != pid) return null
        val boundsDict = cf.CFDictionaryGetValue(dict, key("kCGWindowBounds")) ?: return null
        val rect = CGRect()
        if (!cg.CGRectMakeWithDictionaryRepresentation(boundsDict, rect)) return null
        rect.read()
        val onScreen =
            cf.CFDictionaryGetValue(dict, key("kCGWindowIsOnscreen"))?.let { cf.CFBooleanGetValue(it) } ?: false
        return Window(
            number = long(dict, "kCGWindowNumber") ?: 0,
            layer = long(dict, "kCGWindowLayer")?.toInt() ?: 0,
            bounds = Box(rect.x.toInt(), rect.y.toInt(), rect.width.toInt(), rect.height.toInt()),
            onScreen = onScreen,
            alpha = double(dict, "kCGWindowAlpha") ?: 1.0,
        )
    }

    /** On-screen windows (or all of them with [onScreenOnly] false) owned by [pid], front to back. */
    fun of(
        pid: Long = ProcessHandle.current().pid(),
        onScreenOnly: Boolean = true,
    ): List<Window> {
        val list = cg.CGWindowListCopyWindowInfo(if (onScreenOnly) ON_SCREEN_ONLY else 0, 0) ?: return emptyList()
        try {
            val out = ArrayList<Window>()
            for (i in 0 until cf.CFArrayGetCount(list)) {
                cf.CFArrayGetValueAtIndex(list, i)?.let { dict -> window(dict, pid)?.let { out += it } }
            }
            return out
        } finally {
            cf.CFRelease(list)
        }
    }
}

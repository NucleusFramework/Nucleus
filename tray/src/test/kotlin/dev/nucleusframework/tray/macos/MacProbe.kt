package dev.nucleusframework.tray.macos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.tray.internal.MacTrayInitializer
import dev.nucleusframework.tray.utils.ComposableIconUtils
import dev.nucleusframework.tray.utils.IconRenderProperties
import dev.nucleusframework.tray.windows.MenuFuzzer
import kotlin.test.fail

/**
 * Reads what AppKit actually holds for a tray (the `NSStatusItem`, its button and the `NSMenu`
 * tree), through `nativeDebugDescribe`, and what it must hold for a [MenuFuzzer] model.
 */
internal object MacProbe {
    val isMac: Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")

    /** A valid PNG, rendered once per test JVM. */
    val pngPath: String by lazy {
        ComposableIconUtils.renderComposableToPngFile(IconRenderProperties.forCurrentOperatingSystem()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF2E7D32)))
        }
    }

    data class Item(
        val separator: Boolean,
        val enabled: Boolean,
        val checked: Boolean,
        val keyEquivalent: String,
        val modifierMask: Long,
        val hasImage: Boolean,
        val hasAction: Boolean,
        val title: String,
        val children: List<Item>,
    )

    data class Snapshot(
        val exists: Boolean,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val tooltip: String? = null,
        val menuOpen: Boolean = false,
        /** Current run-loop mode of the main thread: `NSEventTrackingRunLoopMode` while any menu tracks. */
        val mainMode: String = "",
        /** Whether a tray menu (this tray's: `self`, another's: `other`) is tracking, or `none`. */
        val tracking: String = "",
        val windowVisible: Boolean = false,
        /** Status item window frame and its screen frame (AppKit, bottom-left origin), for diagnostics. */
        val windowFrame: String = "",
        val menu: List<Item> = emptyList(),
    )

    /** What a user sees for one menu entry. */
    data class Shape(
        val title: String?,
        val separator: Boolean,
        val disabled: Boolean,
        val checked: Boolean,
        val keyEquivalent: String,
        val modifierMask: Long,
        val children: List<Shape>,
    )

    fun snapshot(id: String): Snapshot? = MacTrayInitializer.debugDescribe(id)?.let(::parse)

    /**
     * Status items alive in AppKit, counted through weak references: neither the backend nor AppKit
     * may keep the item of a disposed tray. (Status-bar windows are no oracle in a plain JVM: AppKit
     * frees them from autorelease pools only `NSApp`'s own loop drains.)
     */
    fun statusItems(): Int = MacTrayInitializer.debugStatusItemCount()

    /** Waits until [expected] status items are alive. */
    fun awaitStatusItems(
        expected: Int,
        what: String,
        timeoutMs: Long = 10_000,
    ) {
        var last = -2
        try {
            await(what, timeoutMs) { statusItems().also { last = it } == expected }
        } catch (e: AssertionError) {
            fail("$what: expected $expected status items, got $last", e)
        }
    }

    fun parse(text: String): Snapshot {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "item") return Snapshot(exists = false)
        var snapshot = Snapshot(exists = true)
        val flat = ArrayList<Pair<Int, Item>>()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            when (f[0]) {
                "image" -> snapshot = snapshot.copy(imageWidth = f[1].toInt(), imageHeight = f[2].toInt())
                "tooltip" -> snapshot = snapshot.copy(tooltip = f[1].takeUnless { it == "<nil>" }?.let(::unescape))
                "open" -> snapshot = snapshot.copy(menuOpen = f[1] == "1")
                "mode" -> snapshot = snapshot.copy(mainMode = f[1])
                "tracking" -> snapshot = snapshot.copy(tracking = f[1])
                "window" ->
                    snapshot =
                        snapshot.copy(windowVisible = f[2] == "1", windowFrame = f.drop(3).joinToString(" on "))
                "item" -> {
                    val g = line.split('\t', limit = 10)
                    flat +=
                        g[1].toInt() to
                        Item(
                            separator = g[2] == "1",
                            enabled = g[3] == "1",
                            checked = g[4] == "1",
                            keyEquivalent = unescape(g[5]),
                            modifierMask = g[6].toLong(),
                            hasImage = g[7] == "1",
                            hasAction = g[8] == "1",
                            title = unescape(g[9]),
                            children = emptyList(),
                        )
                }
            }
        }
        return snapshot.copy(menu = tree(flat, 0, 0).first)
    }

    private fun tree(
        flat: List<Pair<Int, Item>>,
        start: Int,
        depth: Int,
    ): Pair<List<Item>, Int> {
        val out = ArrayList<Item>()
        var i = start
        while (i < flat.size && flat[i].first >= depth) {
            val item = flat[i].second
            val (children, next) = tree(flat, i + 1, depth + 1)
            out += item.copy(children = children)
            i = next
        }
        return out to i
    }

    private fun unescape(s: String): String =
        buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'u' -> {
                            append(s.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

    /** The label as AppKit must store it: NUL dropped, unpaired surrogates replaced, nothing escaped. */
    fun macTitle(label: String): String =
        buildString {
            var i = 0
            while (i < label.length) {
                val c = label[i]
                when {
                    c == '\u0000' -> {}
                    c.isHighSurrogate() && i + 1 < label.length && label[i + 1].isLowSurrogate() -> {
                        append(c).append(label[i + 1])
                        i++
                    }
                    c.isSurrogate() -> append('�')
                    else -> append(c)
                }
                i++
            }
        }

    /** AppKit reports every item under a disabled submenu as disabled ([parentDisabled]). */
    fun expected(
        nodes: List<MenuFuzzer.Node>,
        parentDisabled: Boolean = false,
    ): List<Shape> =
        nodes.map { node ->
            when (node) {
                is MenuFuzzer.Plain ->
                    Shape(
                        macTitle(node.label),
                        false,
                        parentDisabled || !node.enabled,
                        false,
                        node.shortcut?.toMacKeyEquivalent().orEmpty(),
                        node.shortcut?.toMacModifierMask() ?: 0L,
                        emptyList(),
                    )
                is MenuFuzzer.Check ->
                    Shape(
                        macTitle(node.label),
                        false,
                        parentDisabled || !node.enabled,
                        node.checked,
                        "",
                        0L,
                        emptyList(),
                    )
                is MenuFuzzer.Sub -> {
                    val disabled = parentDisabled || !node.enabled
                    Shape(macTitle(node.label), false, disabled, false, "", 0L, expected(node.children, disabled))
                }
                MenuFuzzer.Divider -> Shape(null, true, false, false, "", 0L, emptyList())
            }
        }

    fun shape(items: List<Item>): List<Shape> =
        items.map {
            if (it.separator) {
                Shape(null, true, false, false, "", 0L, emptyList())
            } else {
                // AppKit reports the modifier mask of an item without a key equivalent as Command.
                val mask = if (it.keyEquivalent.isEmpty()) 0L else it.modifierMask
                Shape(it.title, false, !it.enabled, it.checked, it.keyEquivalent, mask, shape(it.children))
            }
        }

    /** First difference between two menus, as a readable path, or null when they match. */
    fun firstDiff(
        expected: List<Shape>,
        actual: List<Shape>,
        path: String = "menu",
    ): String? {
        for (i in 0 until maxOf(expected.size, actual.size)) {
            val e = expected.getOrNull(i)
            val a = actual.getOrNull(i)
            if (e == null || a == null) return "$path[$i]: expected $e, got $a"
            if (e.copy(children = emptyList()) !=
                a.copy(children = emptyList())
            ) {
                return "$path[$i]: expected $e, got $a"
            }
            firstDiff(e.children, a.children, "$path[$i]")?.let { return it }
        }
        return null
    }

    fun <T : Any> awaitValue(
        what: String,
        timeoutMs: Long = 5000,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(10)
        }
        fail("timed out after ${timeoutMs}ms waiting for $what")
    }

    fun await(
        what: String,
        timeoutMs: Long = 5000,
        probe: () -> Boolean,
    ) {
        awaitValue(what, timeoutMs) { if (probe()) Unit else null }
    }

    /** Waits for tray [id] to exist in AppKit and returns its snapshot. */
    fun awaitItem(id: String): Snapshot = awaitValue("status item of $id") { snapshot(id)?.takeIf { it.exists } }

    /**
     * Waits until tray [id] is placed in the menu bar: only then can a user click it, and AppKit
     * shows no menu for an item it has not laid out yet.
     */
    fun awaitClickable(id: String): Snapshot {
        awaitItem(id)
        val xy = IntArray(2)
        await("$id laid out in the menu bar", timeoutMs = 30_000) { MacTrayInitializer.statusItemPositionFor(id, xy) }
        return awaitItem(id)
    }

    /** Right-clicks tray [id], waits for its menu to be tracking, then closes it. */
    fun openAndClose(id: String): Snapshot {
        awaitClickable(id)
        MacTrayInitializer.debugClick(id, secondary = true)
        var last: Snapshot? = null
        val open =
            try {
                awaitValue("menu of $id open") { snapshot(id)?.also { last = it }?.takeIf { it.menuOpen } }
            } catch (e: AssertionError) {
                fail(
                    "menu of $id never opened: tracking=${last?.tracking} mode=${last?.mainMode} " +
                        "frame=${last?.windowFrame}",
                    e,
                )
            }
        MacTrayInitializer.debugCancelMenu(id)
        await("menu of $id closed") { snapshot(id)?.menuOpen == false }
        return open
    }
}

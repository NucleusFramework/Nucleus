package dev.nucleusframework.tray.linux

import dev.nucleusframework.tray.internal.LinuxTrayInitializer
import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.windows.MenuFuzzer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.fail

/**
 * What the Linux torture suites share: one private bus and panel per test JVM ([LinuxTestBus]),
 * tray setup and teardown (every test ends with no native tray left), and the menu oracle.
 *
 * Seeded: every random test prints its seed; rerun one with `-PtraySeed=<seed>`, and scale the
 * fuzzers with `-PtrayIterations=<n>`.
 */
abstract class LinuxTrayTestBase {
    internal val created = ConcurrentLinkedQueue<String>()
    internal val iterations = System.getProperty("nucleus.tray.torture.iterations")?.toIntOrNull() ?: 60

    internal fun seed(): Long = System.getProperty("nucleus.tray.torture.seed")?.toLongOrNull() ?: System.nanoTime()

    internal val panel: SniPanel get() = LinuxTestBus.panel

    @AfterTest
    fun cleanup() {
        if (!LinuxTestBus.isAvailable) return
        created.forEach { runCatching { LinuxTrayInitializer.dispose(it) } }
        created.clear()
        awaitTrue("every native tray closed", 10_000) { LinuxTrayInitializer.debugLiveNativeTrays() == 0 }
    }

    // ---------------------------------------------------------------- helpers

    internal fun init(
        id: String,
        tooltip: String = id,
        iconPath: String = LinuxFixtures.png(),
        onLeftClick: (() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
        menu: (TrayMenuBuilder.() -> Unit)?,
    ) {
        created += id
        LinuxTrayInitializer.initialize(id, iconPath, tooltip, onLeftClick, menu, onMenuOpened)
    }

    internal fun linuxOnly(block: () -> Unit) {
        if (!LinuxTestBus.isAvailable) return
        panel // set up the private bus before any tray connects
        block()
    }

    /** Waits until the menu read from the bus matches [expected], and returns it. */
    internal fun awaitMenu(
        service: String,
        expected: List<Shape>,
        timeoutMs: Long = 5_000,
    ): List<SniPanel.Node> {
        var lastDiff: String? = null
        return try {
            awaitValue("menu of $service", timeoutMs) {
                val menu = panel.menu(service)
                lastDiff = firstDiff(expected, shape(menu))
                if (lastDiff == null) menu else null
            }
        } catch (e: AssertionError) {
            fail("${e.message}; first difference: $lastDiff", e)
        }
    }

    // ---------------------------------------------------------------- oracle

    /** One menu entry as a user sees it. */
    data class Shape(
        val text: String?,
        val separator: Boolean,
        val disabled: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val submenu: Boolean,
        val shortcut: List<String>?,
        val children: List<Shape>,
    )

    /** The label as it must reach the host: real UTF-8, NUL dropped, lone surrogates replaced. */
    internal fun displayed(label: String): String =
        buildString {
            var i = 0
            while (i < label.length) {
                val c = label[i]
                when {
                    c == '\u0000' -> Unit
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

    internal fun KeyShortcut.expected(): List<String> =
        buildList {
            if (ctrl) add("Control")
            if (shift) add("Shift")
            if (alt) add("Alt")
            if (meta) add("Super")
            add(toLinuxKey())
        }

    internal fun expected(nodes: List<MenuFuzzer.Node>): List<Shape> =
        nodes.map { node ->
            when (node) {
                is MenuFuzzer.Plain ->
                    Shape(
                        displayed(node.label),
                        false,
                        !node.enabled,
                        false,
                        false,
                        false,
                        node.shortcut?.expected(),
                        emptyList(),
                    )
                is MenuFuzzer.Check ->
                    Shape(displayed(node.label), false, !node.enabled, true, node.checked, false, null, emptyList())
                is MenuFuzzer.Sub ->
                    Shape(
                        displayed(node.label),
                        false,
                        !node.enabled,
                        false,
                        false,
                        true,
                        null,
                        expected(node.children),
                    )
                MenuFuzzer.Divider -> Shape(null, true, false, false, false, false, null, emptyList())
            }
        }

    /** Decodes a DBusMenu label: `__` is a literal underscore; a lone `_` would be a mnemonic. */
    internal fun literalLabel(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '_') {
                if (i + 1 < raw.length && raw[i + 1] == '_') {
                    out.append('_')
                    i += 2
                    continue
                }
                return "<mnemonic in '$raw'>"
            }
            out.append(raw[i])
            i++
        }
        return out.toString()
    }

    internal fun shape(nodes: List<SniPanel.Node>): List<Shape> =
        nodes.map { n ->
            if (n.isSeparator) {
                Shape(null, true, false, false, false, false, null, shape(n.children))
            } else {
                Shape(
                    n.label?.let(::literalLabel),
                    false,
                    !n.enabled,
                    n.toggleType == "checkmark",
                    n.toggleState == 1,
                    n.isSubmenu,
                    n.shortcut?.single(),
                    shape(n.children),
                )
            }
        }

    internal fun firstDiff(
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

    /** Emits [nodes]; every clickable entry records its pre-order index into [clicks]. */
    internal fun emitRecording(
        builder: TrayMenuBuilder,
        nodes: List<MenuFuzzer.Node>,
        clicks: MutableCollection<Int>,
        counter: IntArray = intArrayOf(0),
    ) {
        for (node in nodes) {
            val index = counter[0]++
            when (node) {
                is MenuFuzzer.Plain ->
                    builder.Item(node.label, isEnabled = node.enabled, shortcut = node.shortcut) {
                        clicks +=
                            index
                    }
                is MenuFuzzer.Check ->
                    builder.CheckableItem(
                        node.label,
                        node.checked,
                        { clicks += index },
                        isEnabled = node.enabled,
                    )
                is MenuFuzzer.Sub ->
                    builder.SubMenu(node.label, isEnabled = node.enabled) {
                        emitRecording(this, node.children, clicks, counter)
                    }
                MenuFuzzer.Divider -> builder.Divider()
            }
        }
    }

    /** Pre-order (index, node, reachable): reachable = a user could click it (all ancestors enabled). */
    internal fun preorder(
        nodes: List<MenuFuzzer.Node>,
        enabledPath: Boolean = true,
        out: MutableList<Pair<MenuFuzzer.Node, Boolean>> = ArrayList(),
    ): List<Pair<MenuFuzzer.Node, Boolean>> {
        for (node in nodes) {
            out += node to enabledPath
            if (node is MenuFuzzer.Sub) preorder(node.children, enabledPath && node.enabled, out)
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    internal fun panelNode(value: Any?): SniPanel.Node {
        val (id, props, children) = value as List<Any?>
        return SniPanel.Node(
            id as Int,
            (props as Map<String, Variant>).mapValues { it.value.value },
            (children as List<Variant>).map { panelNode(it.value) },
        )
    }
}

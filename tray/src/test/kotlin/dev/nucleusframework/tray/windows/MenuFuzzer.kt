package dev.nucleusframework.tray.windows

import dev.nucleusframework.tray.menu.Key
import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import kotlin.random.Random

/** Seeded random tray menus, plus what the Windows popup must show for each of them. */
internal object MenuFuzzer {
    sealed interface Node

    data class Plain(
        val label: String,
        val enabled: Boolean,
        val shortcut: KeyShortcut?,
    ) : Node

    data class Check(
        val label: String,
        val checked: Boolean,
        val enabled: Boolean,
    ) : Node

    data class Sub(
        val label: String,
        val enabled: Boolean,
        val children: List<Node>,
    ) : Node

    data object Divider : Node

    /** What a user sees for one menu entry. */
    data class Shape(
        val text: String?,
        val separator: Boolean,
        val disabled: Boolean,
        val checked: Boolean,
        val children: List<Shape>,
    )

    private val hostileLabels =
        listOf(
            "",
            " ",
            "&",
            "Save && quit",
            "a&b",
            "&File",
            "-",
            "--",
            "tab\tinside",
            "😀 emoji",
            "👨‍👩‍👧‍👦 family",
            "lone \uD800 high",
            "lone \uDC00 low",
            "שלום RTL",
            "中文菜单",
            "nul\u0000tail",
            "x".repeat(300),
            "%s %n %d",
            "\\n not a newline",
            "line\nbreak",
        )

    fun label(random: Random): String =
        if (random.nextInt(3) == 0) hostileLabels.random(random) else "item-${random.nextInt(1000)}"

    fun menu(
        random: Random,
        depth: Int,
    ): List<Node> {
        val size =
            when (random.nextInt(10)) {
                0 -> 0
                1 -> random.nextInt(30, 80)
                else -> random.nextInt(1, 8)
            }
        return List(size) {
            when (random.nextInt(10)) {
                0, 1 -> Divider
                2, 3 -> Check(label(random), random.nextBoolean(), random.nextInt(5) != 0)
                4 -> if (depth < 4) Sub(label(random), random.nextInt(5) != 0, menu(random, depth + 1)) else Divider
                else ->
                    Plain(
                        label(random),
                        random.nextInt(5) != 0,
                        if (random.nextInt(4) == 0) KeyShortcut(Key.entries.random(random), ctrl = random.nextBoolean()) else null,
                    )
            }
        }
    }

    fun emit(
        builder: TrayMenuBuilder,
        nodes: List<Node>,
    ) {
        for (node in nodes) {
            when (node) {
                is Plain -> builder.Item(node.label, isEnabled = node.enabled, shortcut = node.shortcut) {}
                is Check -> builder.CheckableItem(node.label, node.checked, {}, isEnabled = node.enabled)
                is Sub -> builder.SubMenu(node.label, isEnabled = node.enabled) { emit(this, node.children) }
                Divider -> builder.Divider()
            }
        }
    }

    /** The label as a Win32 menu string must store it to be displayed literally. */
    private fun win32Text(label: String): String {
        val cut = label.replace("\u0000", "")
        val repaired =
            buildString {
                var i = 0
                while (i < cut.length) {
                    val c = cut[i]
                    when {
                        c.isHighSurrogate() && i + 1 < cut.length && cut[i + 1].isLowSurrogate() -> {
                            append(c).append(cut[i + 1])
                            i++
                        }
                        c.isSurrogate() -> append('�')
                        else -> append(c)
                    }
                    i++
                }
            }
        return repaired.replace("&", "&&")
    }

    fun expected(nodes: List<Node>): List<Shape> =
        nodes.map { node ->
            when (node) {
                is Plain -> {
                    val accel = node.shortcut?.let { "\t" + it.windowsText() }.orEmpty()
                    Shape(win32Text(node.label) + accel, false, !node.enabled, false, emptyList())
                }
                is Check -> Shape(win32Text(node.label), false, !node.enabled, node.checked, emptyList())
                is Sub -> Shape(win32Text(node.label), false, !node.enabled, false, expected(node.children))
                Divider -> Shape(null, true, false, false, emptyList())
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
            if (e.copy(children = emptyList()) != a.copy(children = emptyList())) return "$path[$i]: expected $e, got $a"
            firstDiff(e.children, a.children, "$path[$i]")?.let { return it }
        }
        return null
    }

    fun shape(items: List<Win32Probe.Item>): List<Shape> =
        // Win32 reports separators as grayed; that is not something a user can observe.
        items.map { Shape(it.text, it.separator, it.disabled && !it.separator, it.checked, shape(it.children)) }

    private fun KeyShortcut.windowsText(): String = toWindowsAcceleratorText()
}

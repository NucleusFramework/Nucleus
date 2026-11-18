package dev.nucleusframework.tray.linux

import dev.nucleusframework.tray.internal.LinuxTrayInitializer
import dev.nucleusframework.tray.menu.Key
import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.utils.TrayClickTracker
import dev.nucleusframework.tray.windows.MenuFuzzer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Torture suite for the Linux tray backend, driven black-box over D-Bus.
 *
 * The test JVM runs a private `dbus-daemon` and plays the panel on it ([SniPanel]): it is the
 * StatusNotifierWatcher the tray registers with, reads `IconPixmap` / `ToolTip` / `ItemIsMenu`
 * and the DBusMenu layout as Plasma or GNOME's AppIndicator extension would, and calls
 * `Activate`, `Event`, `AboutToShow` as they do on a click.
 *
 * Seeded: every random test prints its seed; rerun one with `-PtraySeed=<seed>`, and scale the
 * fuzzers with `-PtrayIterations=<n>`.
 */
class LinuxTrayTortureTest : LinuxTrayTestBase() {
    @Test
    fun `seeded menu fuzzer - the host reads exactly the menu, clicks reach exactly their item`() =
        linuxOnly {
            val seed = seed()
            println("[torture] menu fuzzer seed=$seed iterations=$iterations")
            val random = Random(seed)
            val id = "fuzz"
            val clicks = ConcurrentLinkedQueue<Int>()
            var nodes = MenuFuzzer.menu(random, 0)
            init(id) { emitRecording(this, nodes, clicks) }
            val service = panel.serviceOf(id)
            repeat(iterations) { round ->
                nodes = MenuFuzzer.menu(random, 0)
                LinuxTrayInitializer.update(
                    id,
                    LinuxFixtures.png(),
                    id,
                    null,
                    { emitRecording(this, nodes, clicks) },
                    null,
                )
                val menu =
                    try {
                        awaitMenu(service, expected(nodes))
                    } catch (e: AssertionError) {
                        fail("round $round (seed=$seed): ${e.message}", e)
                    }

                // Click a few random entries; only a plain or checkable entry a user could reach fires.
                val flat = menu.flatMap { listOf(it) + it.flatten() }
                val model = preorder(nodes)
                assertEquals(model.size, flat.size, "round $round: entry count (seed=$seed)")
                repeat(minOf(6, flat.size)) {
                    val pick = random.nextInt(flat.size)
                    val (node, reachable) = model[pick]
                    val fires =
                        reachable &&
                            when (node) {
                                is MenuFuzzer.Plain -> node.enabled
                                is MenuFuzzer.Check -> node.enabled
                                else -> false
                            }
                    clicks.clear()
                    panel.click(service, flat[pick].id)
                    if (fires) {
                        awaitTrue("click on entry $pick fires (round $round, seed=$seed)") { clicks.isNotEmpty() }
                        Thread.sleep(20)
                        assertEquals(listOf(pick), clicks.toList(), "round $round: wrong callback (seed=$seed)")
                    } else {
                        Thread.sleep(40)
                        assertTrue(clicks.isEmpty(), "round $round: entry $pick ($node) must not fire (seed=$seed)")
                    }
                }
            }
        }

    @Test
    fun `labels are literal - underscore, dash, empty, NUL, mnemonics`() =
        linuxOnly {
            val labels = listOf("_File", "snake_case_name", "__", "-", "--", "", " ", "a\u0000b", "100% _done_")
            init("labels") { labels.forEach { Item(it) {} } }
            val service = panel.serviceOf("labels")
            val menu =
                awaitMenu(
                    service,
                    labels.map { Shape(displayed(it), false, false, false, false, false, null, emptyList()) },
                )
            // Raw: every underscore doubled, so no host turns one into a mnemonic.
            assertEquals("__File", menu[0].label)
            assertEquals("snake__case__name", menu[1].label)
            // A "-" label is an item, not a separator.
            assertFalse(menu[3].isSeparator)
        }

    @Test
    fun `two trays - distinct names and ids, isolated callbacks, disposing one keeps the other`() =
        linuxOnly {
            val hits = ConcurrentLinkedQueue<String>()
            val ids = listOf("iso-a", "iso-b", "iso-c")
            val start = System.nanoTime()
            for (id in ids) {
                init(id, onLeftClick = { hits += "$id:primary" }) {
                    Item("first") { hits += "$id:first" }
                    Item("second") { hits += "$id:second" }
                }
            }
            // The old backend let one tray at a time hold a process-wide permit: the second
            // initialize blocked forever. Here three come up at once.
            val services = ids.associateWith { panel.serviceOf(it) }
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "trays took too long to appear")
            assertEquals(3, services.values.toSet().size, "every tray needs its own bus name")
            assertEquals(
                3,
                services.values
                    .map { panel.sni(it, "Id") }
                    .toSet()
                    .size,
                "every tray needs its own Id",
            )

            fun clickAll() {
                for ((id, service) in services) {
                    if (id !in created) continue
                    val menu =
                        awaitMenu(
                            service,
                            listOf(
                                "first",
                                "second",
                            ).map { Shape(it, false, false, false, false, false, null, emptyList()) },
                        )
                    panel.click(service, menu[1].id)
                    panel.activate(service, 10, 20)
                }
            }

            clickAll()
            awaitTrue("6 callbacks") { hits.size == 6 }
            assertEquals(ids.flatMap { listOf("$it:second", "$it:primary") }.toSet(), hits.toSet())

            // Disposing one tray used to drop every other tray's menu callbacks (one global list).
            LinuxTrayInitializer.dispose("iso-b")
            created.remove("iso-b")
            awaitTrue("iso-b leaves the bus") { !panel.hasOwner(services.getValue("iso-b")) }
            hits.clear()
            clickAll()
            awaitTrue("4 callbacks") { hits.size == 4 }
            assertEquals(setOf("iso-a:second", "iso-a:primary", "iso-c:second", "iso-c:primary"), hits.toSet())
        }

    @Test
    fun `checkable items sharing a label toggle independently`() =
        linuxOnly {
            val changes = ConcurrentLinkedQueue<Pair<Int, Boolean>>()
            init("checks") {
                CheckableItem("Same", false, { changes += 0 to it })
                CheckableItem("Same", false, { changes += 1 to it })
            }
            val service = panel.serviceOf("checks")
            val unchecked = Shape("Same", false, false, true, false, false, null, emptyList())
            val menu = awaitMenu(service, listOf(unchecked, unchecked))
            val since = System.nanoTime()
            panel.click(service, menu[1].id)
            awaitTrue("toggle callback") { changes.isNotEmpty() }
            assertEquals(listOf(1 to true), changes.toList())
            awaitMenu(service, listOf(unchecked, unchecked.copy(checked = true)))
            // A check-state change is a property update, not a new layout: an open menu stays open.
            awaitTrue(
                "ItemsPropertiesUpdated",
            ) { panel.signalsFrom(service, "ItemsPropertiesUpdated", since).isNotEmpty() }
            assertTrue(panel.signalsFrom(service, "LayoutUpdated", since).isEmpty(), "no LayoutUpdated for a toggle")
        }

    @Test
    fun `primary action - ItemIsMenu, Activate with the click position, menu-only trays`() =
        linuxOnly {
            val clicks = AtomicInteger()
            init("primary", onLeftClick = { clicks.incrementAndGet() }) { Item("x") {} }
            val service = panel.serviceOf("primary")
            awaitTrue("ItemIsMenu false with a primary action") { panel.sni(service, "ItemIsMenu") == false }
            panel.activate(service, 1234, 56)
            awaitTrue("primary action ran") { clicks.get() == 1 }
            assertEquals(1234 to 56, TrayClickTracker.lastClick("primary")?.let { it.x to it.y })

            // Without a primary action the host must open the menu on a left click.
            val since = System.nanoTime()
            LinuxTrayInitializer.update("primary", LinuxFixtures.png(), "primary", null, { Item("x") {} }, null)
            awaitTrue("ItemIsMenu true without a primary action") { panel.sni(service, "ItemIsMenu") == true }
            awaitTrue("PropertiesChanged(ItemIsMenu)") {
                panel.signalsFrom(service, "PropertiesChanged", since).any { s ->
                    @Suppress("UNCHECKED_CAST")
                    (s.args.getOrNull(1) as? Map<String, *>)?.containsKey("ItemIsMenu") == true
                }
            }
            panel.activate(service, 1, 1)
            Thread.sleep(100)
            assertEquals(1, clicks.get(), "Activate without a primary action does nothing")
        }

    @Test
    fun `tooltip and icon - pyramid never upscaled, ARGB order, change signals`() =
        linuxOnly {
            init(
                "icon",
                tooltip = "tip ünïcødé 😀",
                iconPath = LinuxFixtures.png(0xFFFF0000.toInt(), 40),
            ) { Item("x") {} }
            val service = panel.serviceOf("icon")
            awaitTrue("tooltip") { panel.tooltipText(service) == "tip ünïcødé 😀" }
            val pixmaps = panel.iconPixmaps(service)
            assertEquals(listOf(16, 22, 24, 32), pixmaps.map { it.first }, "levels up to the 40 px source only")
            for ((w, h, data) in pixmaps) {
                assertEquals(w, h)
                assertEquals(w * h * 4, data.size)
                // Network byte order ARGB: opaque red is FF FF 00 00.
                assertContentEquals(byteArrayOf(-1, -1, 0, 0), data.copyOfRange(0, 4))
            }

            val since = System.nanoTime()
            LinuxTrayInitializer.update(
                "icon",
                LinuxFixtures.png(0xFF0000FF.toInt(), 128),
                "other",
                null,
                { Item("x") {} },
                null,
            )
            awaitTrue("new icon") { panel.iconPixmaps(service).size == 7 }
            assertContentEquals(
                byteArrayOf(-1, 0, 0, -1),
                panel
                    .iconPixmaps(service)
                    .last()
                    .third
                    .copyOfRange(0, 4),
            )
            awaitTrue("NewIcon") { panel.signalsFrom(service, "NewIcon", since).isNotEmpty() }
            awaitTrue("NewToolTip") { panel.signalsFrom(service, "NewToolTip", since).isNotEmpty() }
            assertEquals("other", panel.tooltipText(service))
            // The menu did not change: no layout update for an icon/tooltip change.
            assertTrue(panel.signalsFrom(service, "LayoutUpdated", since).isEmpty())
        }

    @Test
    fun `garbage icons keep the current icon and never crash`() =
        linuxOnly {
            init("garbage", iconPath = LinuxFixtures.png(0xFF00FF00.toInt(), 32)) { Item("x") {} }
            val service = panel.serviceOf("garbage")
            awaitTrue("icon shown") { panel.iconPixmaps(service).isNotEmpty() }
            val before = panel.iconPixmaps(service).map { it.third.toList() }
            val bad =
                listOf(
                    "/nonexistent/icon.png",
                    "",
                    LinuxFixtures.garbage(ByteArray(0)),
                    LinuxFixtures.garbage(ByteArray(64) { it.toByte() }),
                    // A PNG signature followed by nonsense.
                    LinuxFixtures.garbage(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + ByteArray(200) { 7 }),
                    LinuxFixtures.garbage(
                        java.io
                            .File(LinuxFixtures.png(0xFF123456.toInt(), 64))
                            .readBytes()
                            .copyOf(120),
                    ),
                )
            for (path in bad) {
                LinuxTrayInitializer.update("garbage", path, "garbage", null, { Item("y") {} }, null)
            }
            awaitMenu(service, listOf(Shape("y", false, false, false, false, false, null, emptyList())))
            assertEquals(
                before,
                panel.iconPixmaps(service).map { it.third.toList() },
                "a bad icon keeps the current one",
            )

            // A tray created with a bad icon has no pixmap but is otherwise fine.
            init("garbage-first", iconPath = "/nonexistent.png") { Item("z") {} }
            val s2 = panel.serviceOf("garbage-first")
            assertTrue(panel.iconPixmaps(s2).isEmpty())
            awaitMenu(s2, listOf(Shape("z", false, false, false, false, false, null, emptyList())))
        }

    @Test
    fun `stale and impossible events never fire a callback`() =
        linuxOnly {
            val hits = ConcurrentLinkedQueue<String>()
            init("events") {
                Item("enabled") { hits += "enabled" }
                Item("disabled", isEnabled = false) { hits += "disabled" }
                Divider()
                SubMenu("sub") { Item("inner") { hits += "inner" } }
                SubMenu("off", isEnabled = false) { Item("under-disabled") { hits += "under-disabled" } }
            }
            val service = panel.serviceOf("events")
            val menu = awaitValue("menu") { panel.menu(service).takeIf { it.size == 5 } }
            for (node in menu + menu.flatMap { it.flatten() }) {
                if (node.label != "enabled" && node.label != "inner") panel.click(service, node.id)
            }
            panel.event(service, menu[0].id, "hovered")
            panel.event(service, menu[0].id, "opened")
            assertFailsWith<DBusException> { panel.click(service, 9999) }
            assertFailsWith<DBusException> {
                panel.host.call(
                    service,
                    SniPanel.MENU_PATH,
                    SniPanel.MENU_IFACE,
                    "GetLayout",
                    "iias",
                    4242,
                    -1,
                    emptyList<String>(),
                )
            }
            Thread.sleep(150)
            assertTrue(hits.isEmpty(), "fired: $hits")
            panel.click(service, menu[3].children[0].id)
            awaitTrue("inner fires") { hits.toList() == listOf("inner") }
        }

    @Test
    fun `menu opened - AboutToShow and opened of one opening fire once`() =
        linuxOnly {
            val opened = AtomicInteger()
            init("opened", onMenuOpened = { opened.incrementAndGet() }) { Item("x") {} }
            val service = panel.serviceOf("opened")
            awaitMenu(service, listOf(Shape("x", false, false, false, false, false, null, emptyList())))
            Thread.sleep(400) // past the layout-update debounce
            assertFalse(panel.aboutToShow(service, 0))
            panel.event(service, 0, "opened")
            awaitTrue("onMenuOpened") { opened.get() == 1 }
            Thread.sleep(400)
            assertEquals(1, opened.get())
            panel.aboutToShow(service, 0)
            awaitTrue("second opening") { opened.get() == 2 }
            // A submenu opening is not the menu opening.
            Thread.sleep(400)
            panel.aboutToShow(service, 1)
            Thread.sleep(150)
            assertEquals(2, opened.get())
        }

    @Test
    fun `large and deep menus`() =
        linuxOnly {
            // The old backend copied children into a 512-entry stack array: later ones were lost.
            init("large") {
                repeat(2000) { Item("item $it") {} }
            }
            val service = panel.serviceOf("large")
            awaitValue("2000 entries") { panel.menu(service).takeIf { it.size == 2000 } }

            fun deep(
                builder: TrayMenuBuilder,
                level: Int,
            ) {
                if (level == 0) {
                    builder.Item("leaf") {}
                } else {
                    builder.SubMenu("level $level") { deep(this, level - 1) }
                }
            }
            LinuxTrayInitializer.update("large", LinuxFixtures.png(), "large", null, { deep(this, 40) }, null)
            val root = awaitValue("deep menu") { panel.menu(service).takeIf { it.singleOrNull()?.label == "level 40" } }
            // 40 levels nest 120 containers, past what D-Bus carries (64): one reply holds 16
            // levels, and a host following the submenu it opens reaches the leaf.
            var fetches = 1

            // A submenu sent without its children is fetched as its own subtree, as a host does.
            fun expand(node: SniPanel.Node): SniPanel.Node {
                if (node.children.isNotEmpty() || !node.isSubmenu) return node
                val (_, sub) =
                    panel.host.call(
                        service,
                        SniPanel.MENU_PATH,
                        SniPanel.MENU_IFACE,
                        "GetLayout",
                        "iias",
                        node.id,
                        -1,
                        emptyList<String>(),
                    )
                fetches++
                @Suppress("UNCHECKED_CAST")
                return node.copy(children = ((sub as List<Any?>)[2] as List<Variant>).map { panelNode(it.value) })
            }
            var node = expand(root.single())
            var depth = 0
            while (node.children.isNotEmpty()) {
                node = expand(node.children.single())
                depth++
            }
            assertEquals(40, depth)
            assertEquals("leaf", node.label)
            assertEquals(3, fetches)
            assertTrue(panel.hasOwner(service), "a deep menu must not cost the tray its bus connection")
        }

    @Test
    fun `an empty submenu is still a submenu and the empty menu is valid`() =
        linuxOnly {
            init("empty") { SubMenu("nothing inside") {} }
            val service = panel.serviceOf("empty")
            awaitMenu(service, listOf(Shape("nothing inside", false, false, false, false, true, null, emptyList())))
            LinuxTrayInitializer.update("empty", LinuxFixtures.png(), "empty", null, null, null)
            awaitMenu(service, emptyList())
            assertEquals(SniPanel.MENU_PATH, panel.sni(service, "Menu"))
        }

    @Test
    fun `property-only changes are ItemsPropertiesUpdated, structure changes are LayoutUpdated`() =
        linuxOnly {
            init("props") {
                Item("a") {}
                Item("b") {}
            }
            val service = panel.serviceOf("props")
            awaitValue("menu") { panel.menu(service).takeIf { it.size == 2 } }
            val (rev0, _) = panel.layout(service)

            var since = System.nanoTime()
            LinuxTrayInitializer.update("props", LinuxFixtures.png(), "props", null, {
                Item("a renamed") {}
                Item("b", isEnabled = false) {}
            }, null)
            awaitTrue(
                "ItemsPropertiesUpdated",
            ) { panel.signalsFrom(service, "ItemsPropertiesUpdated", since).isNotEmpty() }
            val (rev1, root1) = panel.layout(service)
            assertEquals(rev0, rev1, "same structure keeps the revision")
            assertEquals("a renamed", root1.children[0].label)
            assertFalse(root1.children[1].enabled)

            since = System.nanoTime()
            LinuxTrayInitializer.update("props", LinuxFixtures.png(), "props", null, { Item("only") {} }, null)
            awaitTrue("LayoutUpdated") { panel.signalsFrom(service, "LayoutUpdated", since).isNotEmpty() }
            val (rev2, _) = panel.layout(service)
            assertNotEquals(rev1, rev2)

            // An identical menu says nothing at all.
            Thread.sleep(50)
            since = System.nanoTime()
            LinuxTrayInitializer.update("props", LinuxFixtures.png(), "props", null, { Item("only") {} }, null)
            Thread.sleep(200)
            assertTrue(panel.signalsFrom(service, "LayoutUpdated", since).isEmpty())
            assertTrue(panel.signalsFrom(service, "ItemsPropertiesUpdated", since).isEmpty())
        }

    @Test
    fun `GNOME - an empty menu advertises no menu path`() =
        linuxOnly {
            ProcessEnv.set("XDG_CURRENT_DESKTOP", "ubuntu:GNOME")
            try {
                init("gnome") { Item("x") {} }
                val service = panel.serviceOf("gnome")
                assertEquals(SniPanel.MENU_PATH, panel.sni(service, "Menu"))
                LinuxTrayInitializer.update("gnome", LinuxFixtures.png(), "gnome", null, null, null)
                awaitTrue("Menu = /") { panel.sni(service, "Menu") == "/" }
                LinuxTrayInitializer.update("gnome", LinuxFixtures.png(), "gnome", null, { Item("back") {} }, null)
                awaitTrue("Menu back") { panel.sni(service, "Menu") == SniPanel.MENU_PATH }
            } finally {
                ProcessEnv.set("XDG_CURRENT_DESKTOP", "NucleusTest")
            }
        }

    @Test
    fun `shortcut hints and item icons reach the host`() =
        linuxOnly {
            init("hints") {
                Item("save", shortcut = KeyShortcut(Key.S, ctrl = true, shift = true)) {}
                Item("quit", shortcut = KeyShortcut(Key.Q, meta = true, alt = true)) {}
            }
            val service = panel.serviceOf("hints")
            val menu = awaitValue("menu") { panel.menu(service).takeIf { it.size == 2 } }
            assertEquals(listOf(listOf("Control", "Shift", Key.S.linuxKeyName)), menu[0].shortcut)
            assertEquals(listOf(listOf("Alt", "Super", Key.Q.linuxKeyName)), menu[1].shortcut)
            assertNull(menu[0].iconData)
        }
}

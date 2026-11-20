package dev.nucleusframework.tray.linux

import dev.nucleusframework.tray.internal.LinuxTrayInitializer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/**
 * What a panel sees of our trays, on a private session bus.
 *
 * [watcher] owns `org.kde.StatusNotifierWatcher` and records registrations, like the watcher of
 * Plasma or of GNOME's AppIndicator extension; [host] is the panel's own connection, reading items
 * and their menus and calling into them. The tray under test reaches this bus because
 * [LinuxTestBus] points `DBUS_SESSION_BUS_ADDRESS` at it before any tray opens its connection.
 */
public class SniPanel(
    private val address: String,
) : AutoCloseable {
    data class Registration(
        val service: String,
        val sender: String?,
    )

    /** One DBusMenu entry as a host reads it from `GetLayout`. */
    data class Node(
        val id: Int,
        val props: Map<String, Any?>,
        val children: List<Node>,
    ) {
        val label: String? get() = props["label"] as String?
        val isSeparator: Boolean get() = props["type"] == "separator"
        val enabled: Boolean get() = props["enabled"] as Boolean? ?: true
        val toggleType: String? get() = props["toggle-type"] as String?
        val toggleState: Int? get() = props["toggle-state"] as Int?
        val isSubmenu: Boolean get() = props["children-display"] == "submenu"

        @Suppress("UNCHECKED_CAST")
        val shortcut: List<List<String>>? get() = props["shortcut"] as List<List<String>>?
        val iconData: ByteArray? get() = props["icon-data"] as ByteArray?

        fun flatten(): List<Node> = children.flatMap { listOf(it) + it.flatten() }
    }

    val registrations = CopyOnWriteArrayList<Registration>()
    private var watcher: SdBus? = null
    val host: SdBus = SdBus.connect(address, "host")
    private val idOfService = ConcurrentHashMap<String, String>()

    init {
        host.subscribe("type='signal',interface='$SNI_IFACE'")
        host.subscribe("type='signal',interface='$MENU_IFACE'")
        host.subscribe("type='signal',interface='org.freedesktop.DBus.Properties',member='PropertiesChanged'")
        startWatcher()
    }

    /** Starts a watcher (a panel starting, or restarting after a crash). */
    fun startWatcher() {
        check(watcher == null)
        val bus = SdBus.connect(address, "watcher")
        bus.serve(WATCHER_PATH) { member, iface, sender, args ->
            when {
                member == "RegisterStatusNotifierItem" -> {
                    // A path-only registration means "this object on the caller's connection",
                    // which is how Plasma's watcher reads it.
                    val service = args.single() as String
                    registrations += Registration(if (service.startsWith("/")) sender.orEmpty() else service, sender)
                    "" to emptyList()
                }
                iface == "org.freedesktop.DBus.Properties" && member == "Get" ->
                    when (args[1]) {
                        "IsStatusNotifierHostRegistered" -> "v" to listOf(Variant("b", true))
                        "ProtocolVersion" -> "v" to listOf(Variant("i", 0))
                        "RegisteredStatusNotifierItems" ->
                            "v" to listOf(Variant("as", registrations.map { it.service }.distinct()))
                        else -> null
                    }
                else -> null
            }
        }
        bus.requestName(WATCHER_BUS)
        watcher = bus
    }

    /** Stops the watcher: the panel crashed. Its name leaves the bus. */
    fun stopWatcher() {
        watcher?.close()
        watcher = null
    }

    fun hasOwner(name: String): Boolean =
        host
            .call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner", "s", name)
            .single() as Boolean

    fun ownerOf(name: String): String? =
        runCatching {
            host
                .call(
                    "org.freedesktop.DBus",
                    "/org/freedesktop/DBus",
                    "org.freedesktop.DBus",
                    "GetNameOwner",
                    "s",
                    name,
                ).single() as String
        }.getOrNull()

    /** Every `org.kde.StatusNotifierItem-*` name currently on the bus. */
    fun itemNames(): List<String> =
        @Suppress("UNCHECKED_CAST")
        (
            host
                .call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames")
                .single() as List<String>
        ).filter { it.startsWith("org.kde.StatusNotifierItem-") }

    /** The registered service of tray [trayId] (found by its `Id`), waiting for its registration. */
    fun serviceOf(
        trayId: String,
        timeoutMs: Long = 5_000,
    ): String {
        val itemId = LinuxTrayInitializer.debugItemId(trayId)
        return awaitValue("registration of $trayId", timeoutMs) {
            registrations.map { it.service }.distinct().firstOrNull { service ->
                hasOwner(service) &&
                    idOfService.getOrPut(service) {
                        runCatching { sni(service, "Id") as String }.getOrDefault("")
                    } == itemId
            }
        }
    }

    fun sni(
        service: String,
        name: String,
    ): Any? = host.property(service, SNI_PATH, SNI_IFACE, name)

    /** `GetLayout(0, -1, [])`: (revision, root). */
    fun layout(service: String): Pair<Long, Node> {
        val (revision, root) =
            host.call(
                service,
                MENU_PATH,
                MENU_IFACE,
                "GetLayout",
                "iias",
                0,
                -1,
                emptyList<String>(),
            )
        return revision as Long to node(root)
    }

    fun menu(service: String): List<Node> = layout(service).second.children

    @Suppress("UNCHECKED_CAST")
    private fun node(value: Any?): Node {
        val (id, props, children) = value as List<Any?>
        return Node(
            id as Int,
            (props as Map<String, Variant>).mapValues { it.value.value },
            (children as List<Variant>).map { node(it.value) },
        )
    }

    fun click(
        service: String,
        id: Int,
    ) {
        host.call(service, MENU_PATH, MENU_IFACE, "Event", "isvu", id, "clicked", Variant("i", 0), 0)
    }

    fun event(
        service: String,
        id: Int,
        event: String,
    ) {
        host.call(service, MENU_PATH, MENU_IFACE, "Event", "isvu", id, event, Variant("i", 0), 0)
    }

    fun aboutToShow(
        service: String,
        id: Int,
    ): Boolean = host.call(service, MENU_PATH, MENU_IFACE, "AboutToShow", "i", id).single() as Boolean

    fun activate(
        service: String,
        x: Int,
        y: Int,
    ) {
        host.call(service, SNI_PATH, SNI_IFACE, "Activate", "ii", x, y)
    }

    @Suppress("UNCHECKED_CAST")
    fun iconPixmaps(service: String): List<Triple<Int, Int, ByteArray>> =
        (sni(service, "IconPixmap") as List<List<Any?>>).map { Triple(it[0] as Int, it[1] as Int, it[2] as ByteArray) }

    @Suppress("UNCHECKED_CAST")
    fun tooltipText(service: String): String = (sni(service, "ToolTip") as List<Any?>)[2] as String

    /** Signals [member] sent by [service] (resolved to its unique name) since [sinceNanos]. */
    fun signalsFrom(
        service: String,
        member: String,
        sinceNanos: Long = 0,
    ): List<Signal> {
        val owner = ownerOf(service) ?: return emptyList()
        return host.signals.filter { it.sender == owner && it.member == member && it.atNanos >= sinceNanos }
    }

    override fun close() {
        stopWatcher()
        host.close()
    }

    companion object {
        const val SNI_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/StatusNotifierMenu"
        const val SNI_IFACE = "org.kde.StatusNotifierItem"
        const val MENU_IFACE = "com.canonical.dbusmenu"
        const val WATCHER_BUS = "org.kde.StatusNotifierWatcher"
        const val WATCHER_PATH = "/StatusNotifierWatcher"
    }
}

/** One private bus + panel for the whole test JVM, set up before the first tray connects. */
public object LinuxTestBus {
    val isLinux: Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .startsWith("linux")

    /** Linux with a `dbus-daemon` to start a private bus with; the suites skip otherwise. */
    val isAvailable: Boolean =
        isLinux &&
            System
                .getenv("PATH")
                .orEmpty()
                .split(':')
                .any { java.io.File(it, "dbus-daemon").canExecute() }

    private val bus: PrivateBus by lazy {
        PrivateBus.start().also { bus ->
            ProcessEnv.set("DBUS_SESSION_BUS_ADDRESS", bus.address)
            // Neither GNOME nor Plasma: the GNOME empty-menu quirk is tested explicitly.
            ProcessEnv.set("XDG_CURRENT_DESKTOP", "NucleusTest")
            Runtime.getRuntime().addShutdownHook(Thread { bus.close() })
        }
    }

    val address: String get() = bus.address

    val panel: SniPanel by lazy { SniPanel(bus.address) }
}

public object LinuxFixtures {
    private val cache = ConcurrentHashMap<Pair<Int, Int>, String>()

    /** A solid square PNG of [argb], [size] px. */
    fun png(
        argb: Int = 0xFF2E7D32.toInt(),
        size: Int = 64,
    ): String =
        cache.getOrPut(argb to size) {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.color = Color(argb, true)
            g.fillRect(0, 0, size, size)
            g.dispose()
            val file = File.createTempFile("nucleus-tray-test-", ".png").apply { deleteOnExit() }
            ImageIO.write(image, "png", file)
            file.absolutePath
        }

    fun garbage(bytes: ByteArray): String =
        File
            .createTempFile("nucleus-tray-garbage-", ".png")
            .apply {
                deleteOnExit()
                writeBytes(bytes)
            }.absolutePath
}

public fun <T : Any> awaitValue(
    what: String,
    timeoutMs: Long = 5_000,
    probe: () -> T?,
): T {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var last: Throwable? = null
    while (System.nanoTime() < deadline) {
        try {
            probe()?.let { return it }
        } catch (t: Throwable) {
            last = t
        }
        Thread.sleep(10)
    }
    throw AssertionError("timed out waiting for $what" + (last?.let { " (last error: $it)" } ?: ""), last)
}

public fun awaitTrue(
    what: String,
    timeoutMs: Long = 5_000,
    probe: () -> Boolean,
) {
    awaitValue(what, timeoutMs) { if (probe()) true else null }
}

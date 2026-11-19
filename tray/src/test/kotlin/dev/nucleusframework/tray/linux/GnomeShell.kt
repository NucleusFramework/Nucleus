package dev.nucleusframework.tray.linux

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real GNOME Shell for the tray tests: `gnome-shell --headless --unsafe-mode` on a private
 * session bus, with a virtual monitor, its own Wayland socket and XWayland, and the AppIndicator
 * extension (`ubuntu-appindicators@ubuntu.com`, the StatusNotifierItem host Ubuntu ships)
 * enabled. Settings live in memory (`GSETTINGS_BACKEND=memory`), so the user's desktop is never
 * touched. [GnomeShellDriver] reads and drives it.
 */
internal class GnomeShell private constructor(
    private val process: Process,
    val busAddress: String,
    val waylandDisplay: String,
    private val dir: File,
) : AutoCloseable {
    lateinit var x11Display: String
        private set
    lateinit var xauthority: String
        private set

    override fun close() {
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        dir.deleteRecursively()
    }

    companion object {
        const val APPINDICATOR = "ubuntu-appindicators@ubuntu.com"

        private fun onPath(name: String): Boolean =
            System
                .getenv("PATH")
                .orEmpty()
                .split(':')
                .any { File(it, name).canExecute() }

        /** gnome-shell, dbus-run-session and the AppIndicator extension are installed. */
        val isAvailable: Boolean by lazy {
            LinuxTestBus.isLinux &&
                onPath("gnome-shell") &&
                onPath("dbus-run-session") &&
                listOf(
                    "/usr/share/gnome-shell/extensions",
                    "${System.getProperty("user.home")}/.local/share/gnome-shell/extensions",
                ).any { File(it, APPINDICATOR).isDirectory }
        }

        /**
         * Starts a shell with a [width] x [height] virtual monitor at [scale] (1 or 2: the monitor's
         * scale, applied through the shell's own monitor config).
         */
        fun start(
            width: Int = 1600,
            height: Int = 1000,
            scale: Int = 1,
        ): GnomeShell {
            val dir =
                File(System.getProperty("java.io.tmpdir"), "nucleus-tray-gnome-${System.nanoTime()}").apply { mkdirs() }
            val busFile = File(dir, "bus")
            val socket = "nucleus-tray-${ProcessHandle.current().pid()}-${System.nanoTime() % 100_000}"
            val log = File(dir, "shell.log")
            val script =
                "echo \"\$DBUS_SESSION_BUS_ADDRESS\" > '${busFile.absolutePath}.tmp' && " +
                    "mv '${busFile.absolutePath}.tmp' '${busFile.absolutePath}' && " +
                    "exec gnome-shell --headless --unsafe-mode --mode=user " +
                    "--virtual-monitor ${width}x$height --wayland-display=$socket"
            val process =
                ProcessBuilder("dbus-run-session", "--", "bash", "-c", script)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .apply {
                        val env = environment()
                        env["GSETTINGS_BACKEND"] = "memory"
                        env.remove("DISPLAY")
                        env.remove("WAYLAND_DISPLAY")
                        env.remove("DBUS_SESSION_BUS_ADDRESS")
                        env["XDG_CURRENT_DESKTOP"] = "GNOME"
                        env["NO_AT_BRIDGE"] = "1"
                    }.start()
            val shell =
                try {
                    val runtimeDir = System.getenv("XDG_RUNTIME_DIR") ?: "/run/user/${uid()}"
                    waitUntil(30_000, "gnome-shell up (${log.absolutePath})") {
                        busFile.isFile && File(runtimeDir, socket).exists()
                    }
                    GnomeShell(process, busFile.readText().trim(), socket, dir)
                } catch (e: Throwable) {
                    System.err.println(log.takeIf { it.isFile }?.readText()?.takeLast(4000))
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    throw e
                }
            try {
                GnomeShellDriver(shell.busAddress).use { d ->
                    // The shell owns its D-Bus API a moment after the socket appears.
                    waitUntil(
                        30_000,
                        "org.gnome.Shell.Eval answers",
                    ) { runCatching { d.eval("1") == "1" }.getOrDefault(false) }
                    d.eval("Main.extensionManager.enableExtension('$APPINDICATOR'); 0")
                    waitUntil(15_000, "AppIndicator extension enabled") {
                        d.eval("String(Main.extensionManager.lookup('$APPINDICATOR')?.state)") == "1"
                    }
                    waitUntil(15_000, "StatusNotifierWatcher owned") { d.hasOwner("org.kde.StatusNotifierWatcher") }
                    // A fresh shell opens the Activities overview (GNOME's startup behaviour); it takes
                    // the first clicks.
                    waitUntil(15_000, "startup overview closed") {
                        d.eval(
                            "Main.layoutManager._startingUp ? 'starting' : (Main.overview.hide(), " +
                                "String(Main.overview.visible || Main.overview.animationInProgress))",
                        ) == "false"
                    }
                    if (scale != 1) d.setScale(scale)
                    val monitor = d.monitor()
                    d.warmUpPointer(monitor.centerX, monitor.centerY)
                    waitUntil(5_000, "overview still closed") {
                        d.eval("Main.overview.hide(); String(Main.overview.visible || Main.modalCount > 0)") == "false"
                    }
                    // XWayland starts with the shell; its display and cookie are in the shell's environment.
                    shell.x11Display = d.eval("imports.gi.GLib.getenv('DISPLAY') ?? ''")
                    shell.xauthority = d.eval("imports.gi.GLib.getenv('XAUTHORITY') ?? ''")
                    check(shell.x11Display.isNotEmpty()) { "the shell has no XWayland display" }
                }
            } catch (e: Throwable) {
                System.err.println(File(dir, "shell.log").takeIf { it.isFile }?.readText()?.takeLast(4000))
                shell.close()
                throw e
            }
            return shell
        }

        private fun uid(): String =
            ProcessBuilder("id", "-u")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()

        internal fun waitUntil(
            timeoutMs: Long,
            what: String,
            probe: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (runCatching(probe).getOrDefault(false)) return
                Thread.sleep(50)
            }
            check(runCatching(probe).getOrDefault(false)) { "timed out: $what" }
        }
    }
}

/**
 * Reads and drives a [GnomeShell] through `org.gnome.Shell.Eval` (unsafe mode): the panel's
 * AppIndicator actors, window actors, the work area, and a Clutter virtual pointer, the way a
 * user's mouse reaches the shell.
 */
internal class GnomeShellDriver(
    address: String,
) : AutoCloseable {
    private val bus = SdBus.connect(address, "gnome-driver")

    data class Rect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
        val centerX: Int get() = x + width / 2
        val centerY: Int get() = y + height / 2

        override fun toString(): String = "[$x,$y ${width}x$height]"
    }

    /** One panel indicator drawn by the AppIndicator extension. */
    data class Indicator(
        val busName: String,
        val rect: Rect,
        val visible: Boolean,
        val iconWidth: Int,
        /** Pixmap icons are drawn through an `St.ImageContent` ([pixmapSize] px), named ones through a gicon. */
        val hasIcon: Boolean,
        val pixmapSize: Int,
        val title: String,
        /** The extension knows whether the item has `Activate` (it introspects it first). */
        val supportsActivation: Boolean,
        val menuItems: Int,
    )

    /** One entry of an indicator's shell menu; [path] indexes it from the top level. */
    data class MenuEntry(
        val path: List<Int>,
        val kind: String,
        val label: String,
        val sensitive: Boolean,
        val visible: Boolean,
        /** GNOME's `PopupMenu.Ornament`: 0 none, 1 dot, 2 check. */
        val ornament: Int,
    )

    /** One window as Mutter sees it (stage coordinates, logical pixels). */
    data class Window(
        val title: String,
        val pid: Int,
        val overrideRedirect: Boolean,
        val x11: Boolean,
        val rect: Rect,
        val visible: Boolean,
    )

    override fun close() = bus.close()

    fun eval(js: String): String {
        val reply = bus.call("org.gnome.Shell", "/org/gnome/Shell", "org.gnome.Shell", "Eval", "s", js)
        val ok = reply[0] as Boolean
        val value = reply[1] as String
        check(ok) { "Shell.Eval failed: $value\n  for: $js" }
        // Eval returns JSON.stringify(result); every caller below returns a string or a number.
        return if (value.length >= 2 && value.startsWith('"')) unquote(value) else value
    }

    fun hasOwner(name: String): Boolean =
        bus.call(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus",
            "NameHasOwner",
            "s",
            name,
        )[0] ==
            true

    /**
     * Sets the virtual monitor's scale through `org.gnome.Mutter.DisplayConfig` (temporary
     * config), called asynchronously from inside the shell: a synchronous call would wait on the
     * very main loop that serves it.
     */
    fun setScale(scale: Int) {
        eval(
            """
            const Gio = imports.gi.Gio, GLib = imports.gi.GLib;
            global._nucleusScale = 'pending';
            const call = (method, params, then) => Gio.DBus.session.call('org.gnome.Mutter.DisplayConfig',
              '/org/gnome/Mutter/DisplayConfig', 'org.gnome.Mutter.DisplayConfig', method, params, null,
              Gio.DBusCallFlags.NONE, -1, null, (c, res) => { try { then(c.call_finish(res)); }
              catch (e) { global._nucleusScale = 'error ' + e.message; } });
            call('GetCurrentState', null, reply => {
              const [serial, monitors] = reply.deep_unpack();
              const [[connector], modes] = monitors[0];
              const mode = modes.find(m => m[6]['is-current']?.deep_unpack()) ?? modes[0];
              const params = new GLib.Variant('(uua(iiduba(ssa{sv}))a{sv})',
                [serial, 1, [[0, 0, $scale, 0, true, [[connector, mode[0], {}]]]], {}]);
              call('ApplyMonitorsConfig', params, () => { global._nucleusScale = 'ok'; });
            });
            0
            """.trimIndent(),
        )
        GnomeShell.waitUntil(10_000, "monitor scale applied") { eval("String(global._nucleusScale)") != "pending" }
        val result = eval("String(global._nucleusScale)")
        check(result == "ok") { "ApplyMonitorsConfig: $result" }
        GnomeShell.waitUntil(5_000, "scale $scale in effect") { scale() == scale.toDouble() }
    }

    /** The unique name owning [name] (the extension keys its indicators on it). */
    fun ownerOf(name: String): String =
        bus.call(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus",
            "GetNameOwner",
            "s",
            name,
        )[0] as String

    /** The well-known `org.kde.StatusNotifierItem-*` names on the bus, in creation order. */
    fun itemNames(): List<String> =
        (bus.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames")[0] as List<*>)
            .filterIsInstance<String>()
            .filter { it.startsWith("org.kde.StatusNotifierItem-") }
            .sortedBy { it.substringAfterLast('-').toIntOrNull() ?: 0 }

    fun scale(): Double = eval("String(global.display.get_monitor_scale(0))").toDouble()

    fun workArea(): Rect =
        rect(
            eval(
                "const r = global.workspace_manager.get_active_workspace().get_work_area_for_monitor(0); " +
                    "[r.x, r.y, r.width, r.height].join(',')",
            ),
        )

    fun monitor(): Rect =
        rect(eval("const r = global.display.get_monitor_geometry(0); [r.x, r.y, r.width, r.height].join(',')"))

    /** The AppIndicator actors in the top bar, whatever their status-area key. */
    fun indicators(): List<Indicator> =
        lines(
            """
            const out = [];
            for (const [key, a] of Object.entries(Main.panel.statusArea)) {
              if (!key.startsWith('appindicator-')) continue;
              const ind = a._indicator;
              const [x, y] = a.get_transformed_position();
              const [w, h] = a.get_transformed_size();
              const icon = a._icon ?? null;
              out.push([ind?.busName ?? '', Math.round(x), Math.round(y), Math.round(w), Math.round(h),
                a.visible && a.mapped, icon ? Math.round(icon.get_transformed_size()[0]) : 0,
                !!(icon?.gicon || icon?.content), icon?.content?.get_preferred_size?.()?.[1] ?? 0, (ind?.title ?? '').replace(/[|\n]/g, ' '), ind?.supportsActivation === true,
                a.menu?.numMenuItems ?? 0].join('|'));
            }
            out.join('\n')
            """.trimIndent(),
        ).map { f ->
            Indicator(
                busName = f[0],
                rect = Rect(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt()),
                visible = f[5] == "true",
                iconWidth = f[6].toInt(),
                hasIcon = f[7] == "true",
                pixmapSize = f[8].toDouble().toInt(),
                title = f[9],
                supportsActivation = f[10] == "true",
                menuItems = f[11].toInt(),
            )
        }

    fun indicator(busName: String): Indicator? = indicators().firstOrNull { it.busName == busName }

    private fun menuOf(busName: String) =
        "Object.entries(Main.panel.statusArea).find(([k, a]) => k.startsWith('appindicator-') && " +
            "a._indicator?.busName === '$busName')?.[1]?.menu"

    /** Whether [busName]'s shell menu is open. */
    fun isMenuOpen(busName: String): Boolean = eval("String(!!${menuOf(busName)}?.isOpen)") == "true"

    /** [busName]'s shell menu, depth first, open submenus included (closed ones too: GNOME builds them eagerly). */
    fun menu(busName: String): List<MenuEntry> =
        lines(
            """
            const out = [];
            const walk = (menu, prefix) => menu._getMenuItems().forEach((it, i) => {
              const path = prefix.concat([i]);
              const kind = it.constructor.name;
              out.push([path.join('.'), kind, (it.label?.text ?? '').replace(/[|\n]/g, ' '),
                it.sensitive, it.visible, it._ornament ?? 0].join('|'));
              if (it.menu && it.menu !== menu) walk(it.menu, path);
            });
            const m = ${menuOf(busName)};
            if (m) walk(m, []);
            out.join('\n')
            """.trimIndent(),
        ).map { f ->
            MenuEntry(f[0].split('.').map { it.toInt() }, f[1], f[2], f[3] == "true", f[4] == "true", f[5].toInt())
        }

    /** Where the menu entry at [path] sits (stage coordinates); its submenu must be open. */
    fun menuEntryRect(
        busName: String,
        path: List<Int>,
    ): Rect =
        rect(
            eval(
                """
                let item = null, menu = ${menuOf(busName)};
                for (const i of [${path.joinToString(",")}]) { item = menu._getMenuItems()[i]; menu = item.menu; }
                const [x, y] = item.get_transformed_position(); const [w, h] = item.get_transformed_size();
                [x, y, w, h].map(Math.round).join(',')
                """.trimIndent(),
            ),
        )

    /** Whether the submenu at [path] is expanded. */
    fun isSubmenuOpen(
        busName: String,
        path: List<Int>,
    ): Boolean =
        eval(
            """
            let item = null, menu = ${menuOf(busName)};
            for (const i of [${path.joinToString(",")}]) { item = menu._getMenuItems()[i]; menu = item.menu; }
            String(!!item.menu?.isOpen)
            """.trimIndent(),
        ) == "true"

    /** The status-area keys, for diagnostics. */
    fun statusAreaDump(): String = eval("Object.keys(Main.panel.statusArea).join(' ')")

    /** Disables then enables the AppIndicator extension: the panel side of a panel restart. */
    fun restartAppIndicatorHost() {
        eval("Main.extensionManager.disableExtension('${GnomeShell.APPINDICATOR}'); 0")
        GnomeShell.waitUntil(10_000, "watcher gone") { !hasOwner("org.kde.StatusNotifierWatcher") }
        eval("Main.extensionManager.enableExtension('${GnomeShell.APPINDICATOR}'); 0")
        GnomeShell.waitUntil(10_000, "watcher back") { hasOwner("org.kde.StatusNotifierWatcher") }
    }

    fun windows(): List<Window> =
        lines(
            """
            global.get_window_actors().map(a => { const w = a.meta_window, r = w.get_frame_rect();
              return [(w.get_title() ?? '').replace(/[|\n]/g, ' '), w.get_pid(), w.is_override_redirect(),
                w.get_client_type() === 1, r.x, r.y, r.width, r.height, a.visible].join('|'); }).join('\n')
            """.trimIndent(),
        ).map { f ->
            Window(
                f[0],
                f[1].toInt(),
                f[2] == "true",
                f[3] == "true",
                Rect(f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toInt()),
                f[8] == "true",
            )
        }

    fun closeMenus() {
        eval("Main.panel.menuManager.activeMenu?.close(); 0")
    }

    /**
     * [count] clicks of the shell's virtual pointer at ([x], [y]) stage coordinates, ~60 ms apart
     * like a hand (2 = a double click, well inside the double-click time). [button] 1 = primary,
     * 2 = middle, 3 = secondary. The press waits until the shell reports the pointer there: a
     * motion sent right after the device appears can be lost, and the press would land wherever
     * the pointer was (the Activities hot corner, at first). Returns once the last release went out.
     */
    fun click(
        x: Int,
        y: Int,
        button: Int = 1,
        count: Int = 1,
    ) {
        val before =
            eval(
                """
                const C = imports.gi.Clutter, G = imports.gi.GLib;
                $POINTER
                const p = global._nucleusPointer, t = () => G.get_monotonic_time();
                global._nucleusClicks = (global._nucleusClicks ?? 0);
                let left = ${count * 2}, tries = 0;
                p.notify_absolute_motion(t(), $x, $y);
                G.timeout_add(G.PRIORITY_DEFAULT, 60, () => {
                  const [px, py] = global.get_pointer();
                  if ((Math.abs(px - $x) > 1 || Math.abs(py - $y) > 1) && ++tries < 50) {
                    p.notify_absolute_motion(t(), $x, $y);
                    return G.SOURCE_CONTINUE;
                  }
                  p.notify_button(t(), $button, left % 2 === 0 ? C.ButtonState.PRESSED : C.ButtonState.RELEASED);
                  if (--left > 0) return G.SOURCE_CONTINUE;
                  global._nucleusClicks++; return G.SOURCE_REMOVE; });
                String(global._nucleusClicks)
                """.trimIndent(),
            ).toInt()
        GnomeShell.waitUntil(
            8_000,
            "virtual click delivered",
        ) { eval("String(global._nucleusClicks)").toInt() > before }
    }

    fun doubleClick(
        x: Int,
        y: Int,
    ) = click(x, y, count = 2)

    /** Creates the virtual pointer and parks it at ([x], [y]), so the first click has nothing left to race. */
    fun warmUpPointer(
        x: Int,
        y: Int,
    ) {
        GnomeShell.waitUntil(5_000, "virtual pointer at $x,$y") {
            eval(
                """
                const C = imports.gi.Clutter, G = imports.gi.GLib;
                $POINTER
                global._nucleusPointer.notify_absolute_motion(G.get_monotonic_time(), $x, $y);
                const [px, py] = global.get_pointer();
                String(Math.abs(px - $x) <= 1 && Math.abs(py - $y) <= 1)
                """.trimIndent(),
            ) == "true"
        }
    }

    private companion object {
        const val POINTER =
            "if (!global._nucleusPointer) global._nucleusPointer = " +
                "C.get_default_backend().get_default_seat().create_virtual_device(C.InputDeviceType.POINTER_DEVICE);"
    }

    private fun lines(js: String): List<List<String>> =
        eval(js).split('\n').filter { it.isNotBlank() }.map { it.split('|') }

    private fun rect(csv: String): Rect {
        val v = csv.split(',').map { it.trim().toDouble().toInt() }
        return Rect(v[0], v[1], v[2], v[3])
    }

    private fun unquote(json: String): String {
        val sb = StringBuilder()
        var i = 1
        while (i < json.length - 1) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length - 1) {
                when (val n = json[++i]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        sb.append(json.substring(i + 1, i + 5).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(n)
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}

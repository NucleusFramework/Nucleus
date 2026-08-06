package dev.nucleusframework.window.tao.headful

import java.util.concurrent.TimeUnit

/**
 * Thin shell around `gdbus` for the session [xdg-desktop-portal] FileChooser.
 * Used by the Wayland `xdg_foreign` headful e2e so we exercise a real portal
 * round-trip (not a mocked D-Bus transport).
 */
internal object XdgPortalFileChooser {
    fun gdbusAvailable(): Boolean =
        runCatching {
            val p =
                ProcessBuilder("gdbus", "--help")
                    .redirectErrorStream(true)
                    .start()
            p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)

    fun portalAvailable(): Boolean =
        runCatching {
            val output =
                gdbus(
                    "call",
                    "--session",
                    "--dest",
                    "org.freedesktop.portal.Desktop",
                    "--object-path",
                    "/org/freedesktop/portal/desktop",
                    "--method",
                    "org.freedesktop.DBus.Properties.Get",
                    "org.freedesktop.portal.FileChooser",
                    "version",
                    timeoutSec = 5,
                )
            output.contains("uint32") || output.contains("<uint32")
        }.getOrDefault(false)

    /**
     * Calls `FileChooser.OpenFile` with [parentWindow] (e.g. `wayland:…` or
     * `x11:…` or `""`) and returns the Request object path. The dialog may
     * flash briefly; callers should [PortalRequest.close] immediately.
     */
    fun openFile(
        parentWindow: String,
        title: String,
    ): PortalRequest {
        // Stable handle_token so the request object path is predictable and
        // still alive when we Close it (empty options let the portal pick a
        // one-char token that can vanish before we parse the reply).
        val token = "nucleus" + System.nanoTime().toString(16)
        val options = "{'handle_token': <'$token'>}"
        // gdbus prints: (objectpath '/org/freedesktop/portal/desktop/request/…',)
        val output =
            gdbus(
                "call",
                "--session",
                "--dest",
                "org.freedesktop.portal.Desktop",
                "--object-path",
                "/org/freedesktop/portal/desktop",
                "--method",
                "org.freedesktop.portal.FileChooser.OpenFile",
                parentWindow,
                title,
                options,
                timeoutSec = 15,
            )
        val path =
            OBJECTPATH_REGEX.find(output)?.groupValues?.get(1)
                ?: error("OpenFile did not return a request path:\n$output")
        check(token in path) {
            "request path does not contain our handle_token ($token): $path\n$output"
        }
        return PortalRequest(path)
    }

    class PortalRequest(
        val requestPath: String,
    ) {
        /**
         * Best-effort [Request.Close]. On several desktop portals (notably
         * xdg-desktop-portal-gnome) the Request object path returned by
         * OpenFile is not long-lived enough for a follow-up Close — the dialog
         * is still parented; the object simply isn't exported under that path
         * by the time a second call runs. Ignoring the failure keeps the e2e
         * focused on the parenting contract we care about (export + accepted
         * `parent_window`).
         */
        fun close() {
            runCatching {
                gdbus(
                    "call",
                    "--session",
                    "--dest",
                    "org.freedesktop.portal.Desktop",
                    "--object-path",
                    requestPath,
                    "--method",
                    "org.freedesktop.portal.Request.Close",
                    timeoutSec = 5,
                )
            }
        }
    }

    private fun gdbus(
        vararg args: String,
        timeoutSec: Long,
    ): String {
        val process =
            ProcessBuilder(listOf("gdbus") + args)
                .redirectErrorStream(true)
                .start()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        val text = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            error("gdbus timed out after ${timeoutSec}s: ${args.joinToString(" ")}\n$text")
        }
        if (process.exitValue() != 0) {
            error("gdbus exit ${process.exitValue()}: ${args.joinToString(" ")}\n$text")
        }
        return text
    }

    private val OBJECTPATH_REGEX =
        Regex("""objectpath\s+'([^']+)'""")
}

package dev.nucleusframework.launcher.linux

import java.util.concurrent.TimeUnit

/**
 * Whether a D-Bus session bus answers, for the tests that exercise the native bridge.
 *
 * The bridge connects with `g_bus_get_sync(G_BUS_TYPE_SESSION)`, which has no timeout. On a CI
 * runner without a working session bus it can block the test JVM until the job is cancelled, and
 * `:launcher-linux:check` never reports. The probe runs in a separate `gdbus` process that is killed
 * if it does not answer in time, so such a runner skips the native tests instead of hanging.
 *
 * `DBUS_SESSION_BUS_ADDRESS` must also be set: the bridge refuses GDBus's autolaunch fallback (see
 * `get_connection`), so a bus `gdbus` could only reach without it is no bus to the bridge.
 */
internal object SessionBus {
    private const val PROBE_TIMEOUT_SECONDS = 5L

    val isReachable: Boolean by lazy {
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return@lazy false
        runCatching {
            val probe =
                ProcessBuilder(
                    "gdbus",
                    "call",
                    "--session",
                    "--timeout",
                    "3",
                    "--dest",
                    "org.freedesktop.DBus",
                    "--object-path",
                    "/org/freedesktop/DBus",
                    "--method",
                    "org.freedesktop.DBus.GetId",
                ).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                probe.exitValue() == 0
            } else {
                probe.destroyForcibly()
                false
            }
        }.getOrDefault(false)
    }
}

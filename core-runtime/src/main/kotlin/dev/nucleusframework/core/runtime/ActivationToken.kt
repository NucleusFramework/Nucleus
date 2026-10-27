package dev.nucleusframework.core.runtime

import java.util.concurrent.atomic.AtomicReference

/**
 * The `xdg-activation` token of the latest user action that reached the app outside its windows:
 * a global hotkey, a notification click.
 *
 * A Wayland compositor only lets a window take focus with a token it handed out for a user
 * action. Nucleus modules that receive one [offer] it here, and a Nucleus window's focus request
 * [takes][take] it, so `window.requestFocus()` in a global-hotkey listener or a notification
 * action brings the window to the front with nothing to pass along (#739) — the way GtkApplication
 * hands a D-Bus activation's token to the next `present()`.
 *
 * A token is single-use, and only one recent enough to still be valid is handed out. Unused
 * elsewhere than on Wayland.
 */
public object ActivationToken {
    /** Compositors expire unused tokens; an older one would only be refused. */
    private const val MAX_AGE_NANOS = 10_000_000_000L

    private class Offered(
        val token: String,
        val atNanos: Long,
    )

    private val latest = AtomicReference<Offered?>(null)

    /**
     * Records [token] as the latest one, replacing any earlier one. Called by Nucleus modules when
     * the desktop hands them a token; an app that receives one by other means can offer it too.
     */
    @JvmStatic
    public fun offer(token: String) {
        if (token.isNotEmpty()) latest.set(Offered(token, System.nanoTime()))
    }

    /** Returns the latest token and forgets it, or null when there is none or it is too old. */
    @JvmStatic
    public fun take(): String? {
        val offered = latest.getAndSet(null) ?: return null
        return offered.token.takeIf { System.nanoTime() - offered.atNanos < MAX_AGE_NANOS }
    }
}

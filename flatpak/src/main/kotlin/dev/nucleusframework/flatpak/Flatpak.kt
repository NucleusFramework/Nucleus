package dev.nucleusframework.flatpak

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.flatpak.internal.FlatpakBackend
import dev.nucleusframework.flatpak.internal.NativeFlatpakBackend
import dev.nucleusframework.flatpak.internal.NativeFlatpakBridge
import dev.nucleusframework.flatpak.internal.UnsupportedFlatpakBackend

/**
 * Flatpak as seen from the app it sandboxes — Flathub or any other remote: whether a newer
 * version waits on the remote and installing it from inside the app ([updates]), through the
 * Flatpak portal (`org.freedesktop.portal.Flatpak`).
 *
 * ```kotlin
 * Flatpak.updates.status.collect { status ->
 *     if (status is FlatpakUpdateStatus.Available) offerUpdate()
 * }
 * ```
 *
 * Outside a Flatpak, [availability] reports [FlatpakAvailability.NotFlatpak]; on macOS and
 * Windows [FlatpakAvailability.Unsupported], and every call fails with
 * [FlatpakErrorCode.Unsupported] there.
 */
public object Flatpak {
    @Volatile
    private var override: FlatpakBackend? = null

    private val default: FlatpakBackend by lazy {
        when (Platform.Current) {
            Platform.Linux ->
                if (NativeFlatpakBridge.isAvailable) {
                    NativeFlatpakBackend(NativeFlatpakBridge)
                } else {
                    UnsupportedFlatpakBackend("The Flatpak native bridge failed to load")
                }
            else -> UnsupportedFlatpakBackend("Flatpak is not available on ${Platform.Current}")
        }
    }

    internal val backend: FlatpakBackend
        get() = override ?: default

    internal fun setBackendForTesting(backend: FlatpakBackend?) {
        override = backend
    }

    /** `true` on Linux when the bridge loaded. [availability] says whether the app runs in a Flatpak. */
    public val isSupported: Boolean
        get() = backend.isSupported

    /** The Flatpak app id (`FLATPAK_ID`), `null` outside a Flatpak. */
    public val appId: String?
        get() = System.getenv("FLATPAK_ID")?.takeIf { it.isNotEmpty() }

    /** Whether the Flatpak portal can serve this process. */
    public suspend fun availability(): FlatpakAvailability = backend.availability()

    /** Newer versions of this app on its Flatpak remote, installed from inside the app. */
    public val updates: FlatpakUpdates = FlatpakUpdates()
}

package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.ViewConfiguration
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.energymanager.AwakeHandle
import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.energymanager.EnergyManager
import dev.nucleusframework.window.tao.TaoLinuxUriHandler
import dev.nucleusframework.window.tao.clipboard.TaoLinuxClipboard
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxClipboardBridge
import java.util.logging.Logger

/**
 * Process-wide refcount for Compose `Modifier.keepScreenOn()` on the Tao
 * backend.
 *
 * Each [TaoPlatformContextBase] reports 0↔1; this object turns that into a
 * single [EnergyManager.acquireAwake] handle so several windows/popups can
 * request the screen on without releasing each other, and without dropping
 * an app-owned [EnergyManager.keepAwake] slot.
 */
internal object TaoKeepScreenOn {
    private val logger = Logger.getLogger(TaoKeepScreenOn::class.java.name)
    private val lock = Any()
    private var count = 0
    private var handle: AwakeHandle? = null

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (enabled) {
                if (++count == 1) {
                    handle = EnergyManager.acquireAwake(AwakeMode.SYSTEM_AND_DISPLAY)
                    if (!EnergyManager.isAwakeActive()) {
                        logger.fine("keepScreenOn requested but EnergyManager could not keep the display awake")
                    }
                }
            } else if (count > 0 && --count == 0) {
                handle?.close()
                handle = null
            }
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            count = 0
            handle?.close()
            handle = null
        }
    }
}

/**
 * [PlatformContext] that forwards Compose's `isKeepScreenOnEnabled` to
 * [TaoKeepScreenOn] / [EnergyManager] and reports a density-scaled
 * [viewConfiguration].
 *
 * Every Tao scene (window, popup, overlay) must extend this instead of
 * [PlatformContext.Empty] so `Modifier.keepScreenOn()` actually keeps the
 * display awake and gesture thresholds match the AWT backend.
 */
@OptIn(InternalComposeUiApi::class)
internal abstract class TaoPlatformContextBase : PlatformContext.Empty() {
    private var keepScreenOn = false

    override var isKeepScreenOnEnabled: Boolean
        get() = keepScreenOn
        set(value) {
            if (keepScreenOn == value) return
            keepScreenOn = value
            TaoKeepScreenOn.setEnabled(value)
        }

    /**
     * The owning scene's px-per-dp factor, read live so [viewConfiguration]
     * tracks display-scale changes. Hosts override with their current scale.
     */
    protected open val sceneScale: Float get() = 1f

    /**
     * Compose 1.13 takes the owner's `LocalUriHandler` from the context, so
     * popups and dialogs get it too. On Linux, Skiko's default `URIManager`
     * calls `Desktop.browse`, which starts XAWT. See [TaoLinuxUriHandler].
     * Elsewhere `xdg-open` does not exist, so the default handler stays.
     */
    override val uriHandler: UriHandler by lazy {
        if (Platform.Current == Platform.Linux) TaoLinuxUriHandler else super.uriHandler
    }

    /** The owner's `LocalClipboard`: GTK when available (#582), Compose's AWT clipboard otherwise. */
    override val clipboard: Clipboard by lazy {
        val awt = super.clipboard
        if (NativeTaoLinuxClipboardBridge.isAvailable) TaoLinuxClipboard(fallback = awt) else awt
    }

    /**
     * Density-scaled thresholds — AWT/Android parity (#615).
     * [PlatformContext.Empty] inherits [PlatformContext.DefaultViewConfiguration],
     * whose `touchSlop` is 18 RAW pixels, while the AWT backend uses
     * `18.dp.toPx()`: on a 2× display that halves every drag/scroll slop and
     * the derived mouse slop.
     */
    override val viewConfiguration: ViewConfiguration =
        object : ViewConfiguration by PlatformContext.DefaultViewConfiguration {
            override val touchSlop: Float
                get() = PlatformContext.DefaultViewConfiguration.touchSlop * sceneScale
        }
}

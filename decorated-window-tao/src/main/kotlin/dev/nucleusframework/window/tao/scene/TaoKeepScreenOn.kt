package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import dev.nucleusframework.energymanager.AwakeHandle
import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.energymanager.EnergyManager
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
 * [TaoKeepScreenOn] / [EnergyManager].
 *
 * Every Tao scene (window, popup, overlay) must extend this instead of
 * [PlatformContext.Empty] so `Modifier.keepScreenOn()` actually keeps the
 * display awake.
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
}

package dev.nucleusframework.energymanager.windows

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.energymanager.EnergyManager
import dev.nucleusframework.energymanager.PlatformEnergyManager
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Suppress("TooManyFunctions")
internal object WindowsEnergyManager : PlatformEnergyManager {
    /**
     * SetThreadExecutionState is per-thread state: Windows drops the request as
     * soon as the thread that issued it exits. Routing every awake call through
     * this single long-lived daemon thread keeps the request alive regardless
     * of which caller thread (e.g. a recycled Dispatchers.IO worker) invoked it.
     */
    private val awakeExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nucleus-awake").apply { isDaemon = true }
        }
    }

    /** Runs [block] on the dedicated awake thread and waits for its result. */
    fun <T> onAwakeThread(block: () -> T): T = awakeExecutor.submit(block).get()

    private fun callNativeOnAwakeThread(block: () -> Int): EnergyManager.Result {
        if (!NativeWindowsEnergyBridge.isLoaded) {
            return EnergyManager.Result(false, -1, "Native library not loaded")
        }
        return try {
            onAwakeThread { callNative(block) }
        } catch (e: ExecutionException) {
            EnergyManager.Result(false, -1, "Exception: ${(e.cause ?: e).message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            EnergyManager.Result(false, -1, "Interrupted: ${e.message}")
        }
    }

    private fun callNative(block: () -> Int): EnergyManager.Result {
        if (!NativeWindowsEnergyBridge.isLoaded) {
            return EnergyManager.Result(false, -1, "Native library not loaded")
        }
        return try {
            val rc = block()
            if (rc == 0) {
                EnergyManager.Result(true)
            } else {
                EnergyManager.Result(false, rc, "Native call failed with error code $rc")
            }
        } catch (e: UnsatisfiedLinkError) {
            EnergyManager.Result(false, -1, "Exception: ${e.message}")
        }
    }

    override fun isAvailable(): Boolean =
        Platform.Current == Platform.Windows &&
            NativeWindowsEnergyBridge.isLoaded &&
            runCatching { NativeWindowsEnergyBridge.nativeIsSupported() }.getOrDefault(false)

    override fun enableEfficiencyMode() = callNative { NativeWindowsEnergyBridge.nativeEnableEfficiencyMode() }

    override fun enableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeWindowsEnergyBridge.nativeEnableLightEfficiencyMode() }

    override fun disableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeWindowsEnergyBridge.nativeDisableLightEfficiencyMode() }

    override fun disableEfficiencyMode() = callNative { NativeWindowsEnergyBridge.nativeDisableEfficiencyMode() }

    override fun enableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeWindowsEnergyBridge.nativeEnableThreadEfficiencyMode() }

    override fun disableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeWindowsEnergyBridge.nativeDisableThreadEfficiencyMode() }

    override fun keepAwake(mode: AwakeMode) =
        callNativeOnAwakeThread { NativeWindowsEnergyBridge.nativeKeepAwake(mode.nativeCode) }

    override fun releaseAwake() = callNativeOnAwakeThread { NativeWindowsEnergyBridge.nativeReleaseAwake() }

    override fun isAwakeActive(): Boolean =
        NativeWindowsEnergyBridge.isLoaded &&
            runCatching { NativeWindowsEnergyBridge.nativeIsAwakeActive() }.getOrDefault(false)

    /** Mirrors the AWAKE_* constants in the native bridge. */
    private val AwakeMode.nativeCode: Int
        get() =
            when (this) {
                AwakeMode.SYSTEM_AND_DISPLAY -> 0
                AwakeMode.SYSTEM_ONLY -> 1
            }
}

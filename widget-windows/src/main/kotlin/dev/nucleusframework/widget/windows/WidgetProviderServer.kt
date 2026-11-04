package dev.nucleusframework.widget.windows

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Out-of-process COM server hosting a [WidgetProvider].
 *
 * The widget host activates the provider through the CLSID declared in the package manifest
 * (`com:ExeServer` + `uap3:AppExtension Name="com.microsoft.windows.widgets"`). Register the provider
 * at startup, both when the app is launched normally and when COM launches it for the host, so a
 * running instance serves the host instead of a second process being spawned.
 */
public object WidgetProviderServer {
    /** Conventional `com:ExeServer` argument passed when COM launches the process for the widget host. */
    public const val COM_SERVER_ARGUMENT: String = "-RegisterProcessAsComServer"

    /**
     * Whether the process was launched by COM to serve the widget host, i.e. [args] contain
     * [COM_SERVER_ARGUMENT] or COM's own `-Embedding` flag. Such a launch usually shows no window.
     */
    public fun isComServerLaunch(args: Array<String>): Boolean =
        args.any { it.equals(COM_SERVER_ARGUMENT, ignoreCase = true) || it.equals("-Embedding", ignoreCase = true) }

    /**
     * Registers [provider] as the class object for [clsid] (`CoRegisterClassObject`,
     * `CLSCTX_LOCAL_SERVER`, `REGCLS_MULTIPLEUSE`). Close the returned handle to revoke it.
     *
     * @throws WidgetException if the native library is unavailable or the registration fails.
     */
    public fun register(
        clsid: UUID,
        provider: WidgetProvider,
    ): AutoCloseable {
        if (!NativeWidgetBridge.isLoaded) {
            throw WidgetException("The widget native library is not available on this platform", E_NOT_AVAILABLE)
        }
        val dispatcher = WidgetProviderDispatcher(provider)
        val cookie =
            NativeWidgetBridge.onCom {
                NativeWidgetBridge.nativeRegisterProvider(clsid.toString(), dispatcher, dispatcher.flags)
            }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                NativeWidgetBridge.onCom { NativeWidgetBridge.nativeRevokeProvider(cookie) }
            }
        }
    }
}

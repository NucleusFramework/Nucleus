package dev.nucleusframework.nucleus.nativessl.windows

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nucleus.nativessl.debugln
import java.util.logging.Level
import java.util.logging.Logger

private const val TAG = "WindowsSslBridge"
private const val LIBRARY_NAME = "nucleus_ssl"

internal object WindowsSslBridge {
    private val logger = Logger.getLogger(WindowsSslBridge::class.java.simpleName)
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, WindowsSslBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeGetSystemCertificates(): Array<ByteArray>

    fun getSystemCertificates(): List<ByteArray> {
        if (!loaded) return emptyList()
        return try {
            nativeGetSystemCertificates().toList().also {
                debugln(TAG) { "Loaded ${it.size} certificates from Windows Crypt32" }
            }
        } catch (e: UnsatisfiedLinkError) {
            logger.log(Level.WARNING, "JNI call failed for nativeGetSystemCertificates", e)
            emptyList()
        }
    }
}

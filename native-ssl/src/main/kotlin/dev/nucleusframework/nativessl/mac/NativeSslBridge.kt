package dev.nucleusframework.nativessl.mac

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nativessl.debugln
import java.util.logging.Level
import java.util.logging.Logger

private const val TAG = "NativeSslBridge"
private const val LIBRARY_NAME = "nucleus_ssl"

internal object NativeSslBridge {
    private val logger = Logger.getLogger(NativeSslBridge::class.java.name)
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeSslBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeGetSystemCertificates(): Array<ByteArray>

    fun getSystemCertificates(): List<ByteArray> {
        if (!loaded) return emptyList()
        return try {
            nativeGetSystemCertificates().toList().also {
                debugln(TAG) { "Loaded ${it.size} certificates from macOS Security framework" }
            }
        } catch (e: UnsatisfiedLinkError) {
            logger.log(Level.WARNING, "JNI call failed for nativeGetSystemCertificates", e)
            emptyList()
        }
    }
}

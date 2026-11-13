package dev.nucleusframework.store.mac.internal

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/** The system property naming a `.storekit` file to serve the store from (development only). */
internal const val STOREKIT_CONFIGURATION_PROPERTY = "nucleus.macAppStore.storeKitConfiguration"

private const val DEFAULT_DEVELOPER_DIR = "/Applications/Xcode.app/Contents/Developer"
private const val STOREKIT_TEST_BINARY =
    "Platforms/MacOSX.platform/Developer/Library/Frameworks/StoreKitTest.framework/StoreKitTest"
private const val XCODE_SELECT_TIMEOUT_SECONDS = 5L

/**
 * A StoreKit Testing configuration to install before the first store call.
 *
 * @property configuration the `.storekit` file
 * @property framework the `StoreKitTest` binary of the active Xcode
 */
internal data class DevConfiguration(
    val configuration: String,
    val framework: String,
) {
    companion object {
        private val logger = Logger.getLogger(DevConfiguration::class.java.name)

        /** From [STOREKIT_CONFIGURATION_PROPERTY]; `null` when the app runs against the real store. */
        fun fromSystem(): DevConfiguration? {
            val path = System.getProperty(STOREKIT_CONFIGURATION_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
            val configuration = File(path).absoluteFile
            val developerDir = developerDir()
            logger.info { "StoreKit Testing: serving ${configuration.path} (Xcode at $developerDir)" }
            return DevConfiguration(configuration.path, File(developerDir, STOREKIT_TEST_BINARY).path)
        }

        // The Xcode `xcode-select` points at, as xcrun resolves it: DEVELOPER_DIR first.
        private fun developerDir(): String =
            System.getenv("DEVELOPER_DIR")?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    val process = ProcessBuilder("/usr/bin/xcode-select", "-p").redirectErrorStream(true).start()
                    val output =
                        process.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    val exited = process.waitFor(XCODE_SELECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    output.takeIf { exited && process.exitValue() == 0 }
                }.onFailure { logger.log(Level.FINE, "xcode-select -p failed", it) }
                    .getOrNull()
                    // The command-line tools alone do not ship StoreKitTest.
                    ?.takeIf { File(it, STOREKIT_TEST_BINARY).exists() }
                ?: DEFAULT_DEVELOPER_DIR
    }
}
